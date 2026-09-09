package com.phonecontrol.assistant.developer

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.phonecontrol.assistant.data.ConversationStore
import com.phonecontrol.assistant.execution.ForegroundAppInfo
import com.phonecontrol.assistant.execution.PhoneProcessRunner
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplayCapture
import com.phonecontrol.assistant.execution.TaskDisplayGeometry
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplaySpec
import com.phonecontrol.assistant.execution.TaskDisplayRecord
import com.phonecontrol.assistant.execution.TaskDisplayCloseResult
import com.phonecontrol.assistant.execution.TaskDisplayResolution
import com.phonecontrol.assistant.execution.TaskDisplayStatus
import com.phonecontrol.assistant.execution.TaskDisplayTarget
import com.phonecontrol.assistant.execution.isTerminal
import com.phonecontrol.assistant.execution.terminalized
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** State that the DHD preview can render without knowing the native backend. */
sealed interface TaskPreviewState {
    data object Detached : TaskPreviewState
    data class Connecting(val session: TaskDisplaySession) : TaskPreviewState
    data class Attached(val session: TaskDisplaySession) : TaskPreviewState
    data class Error(val sessionKey: String?, val message: String) : TaskPreviewState
}

/**
 * Adapts the shell-UID native display service to the execution-layer contract.
 *
 * The adapter owns the app-process mapping from coordinator session keys to the
 * native session object. This is intentional: the native manager's map is
 * private and its API returns a result rather than a nullable current-session
 * lookup. Keeping the identity map here gives observations/actions one shared
 * lease and lets Stop invalidate it before asynchronous native cleanup runs.
 */
class DhdTaskDisplayBackend(
    context: Context,
    private val nativeManager: DhdVirtualDisplayManager,
    private val processRunner: PhoneProcessRunner,
    private val conversationStore: ConversationStore? = null,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
    private val terminalRetentionMs: Long = TERMINAL_RETENTION_MS,
) : TaskDisplayBackend {
    private val appContext = context.applicationContext
    private val stateLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = LinkedHashMap<String, BoundSession>()
    /** Coordinator run key -> native display owner keys claimed by that run. */
    private val runBindings = mutableMapOf<String, MutableSet<String>>()
    private val bindingsLock = Any()
    private val cancelledKeys = ConcurrentHashMap.newKeySet<String>()
    private val operationLocks = ConcurrentHashMap<String, Mutex>()
    private val liveHandles = mutableMapOf<String, LiveHandle>()
    private val previewStateJobs = mutableMapOf<String, Job>()
    private val _activeSession = MutableStateFlow<TaskDisplaySession?>(null)
    private val _previewState = MutableStateFlow<TaskPreviewState>(TaskPreviewState.Detached)
    private val _previewStates = MutableStateFlow<Map<String, TaskPreviewState>>(emptyMap())
    private val _displayRecords = MutableStateFlow<List<TaskDisplayRecord>>(emptyList())
    private val expiryJobs = mutableMapOf<String, Job>()
    private val recordsLock = Any()
    private val reconciliationJob: Job

    init {
        restorePersistedRecords()
        reconciliationJob = scope.launch { reconcileNativeSessionsWithRetry() }
    }

    /** The one task display currently shown by the DHD task UI, if any. */
    val activeSession: StateFlow<TaskDisplaySession?> = _activeSession.asStateFlow()

    /** Attach/detach/error status for the read-only AVC decoder surface. */
    val previewState: StateFlow<TaskPreviewState> = _previewState.asStateFlow()

    /** Per-session preview state used by the full-screen viewer and manager. */
    val previewStates: StateFlow<Map<String, TaskPreviewState>> = _previewStates.asStateFlow()

    override val displayRecords: StateFlow<List<TaskDisplayRecord>> = _displayRecords.asStateFlow()

    override suspend fun create(
        sessionKey: String,
        packageName: String,
        spec: TaskDisplaySpec,
    ): TaskDisplaySession {
        val operationLock = operationLocks.getOrPut(sessionKey) { Mutex() }
        return operationLock.withLock {
            reconciliationJob.join()
            if (cancelledKeys.contains(sessionKey)) {
                throw TaskDisplayException("The task display session was stopped before creation.")
            }
            stateLock.withLock {
                if (sessions.containsKey(sessionKey)) {
                    throw TaskDisplayException("The task display session is already active.")
                }
            }
            val nativeSpec = DhdVirtualDisplaySpec(
                width = spec.width,
                height = spec.height,
                densityDpi = spec.densityDpi,
                appDensityDpi = spec.appDensityDpi,
            )
            val result = nativeManager.create(sessionKey, packageName, nativeSpec)
            val nativeSession = when (result) {
                is DhdVirtualDisplayResult.Created -> result.session
                is DhdVirtualDisplayResult.Failed -> {
                    throw TaskDisplayException(result.message)
                }
            }
            val taskSession = nativeSession.toTaskSession(appContext)
            val bound = BoundSession(nativeSession, taskSession)
            val record = taskSession.toRecord(
                status = TaskDisplayStatus.RUNNING,
                createdAtEpochMs = nowEpochMs(),
                lastPurpose = conversationStore?.currentPurpose(sessionKey)
                    ?.take(MAX_RECORD_PURPOSE_CHARS)
                    ?.ifBlank { null }
                    ?: "Preparing request",
            )
            val shouldClose = stateLock.withLock {
                if (cancelledKeys.contains(sessionKey)) {
                    true
                } else {
                    sessions[sessionKey] = bound
                    bindRunKey(sessionKey, sessionKey)
                    _activeSession.value = taskSession
                    publishPreviewStateLocked(
                        sessionKey,
                        TaskPreviewState.Connecting(taskSession),
                    )
                    // Publish while the state lock is held. A concurrent
                    // stop can then only retain the already-visible record
                    // after this RUNNING record exists; it cannot be
                    // overwritten by a late create completion.
                    publishRecord(record)
                    false
                }
            }
            if (shouldClose) {
                nativeManager.close(sessionKey)
                throw TaskDisplayException("The task display session was stopped while it was starting.")
            }
            taskSession
        }
    }

    override suspend fun current(sessionKey: String): TaskDisplaySession? {
        // Surface callbacks can arrive while the Activity is being recreated;
        // wait for startup reconciliation so a retained display is attachable
        // on the first callback instead of requiring a manual retry.
        reconciliationJob.join()
        return stateLock.withLock {
            // A terminal display remains viewable, but [withSession] still
            // rejects it for agent actions after [cancel] has installed the
            // tombstone.
            sessions[sessionKey]?.taskSession
                ?: synchronized(bindingsLock) {
                    // A retained display keeps its native owner key when a
                    // later coordinator run claims it. Resolve the logical
                    // run key back to that owner so subsequent observe,
                    // execute, and foreground calls stay on the same display.
                    runBindings[sessionKey]
                        .orEmpty()
                        .asSequence()
                        .mapNotNull { ownerKey -> sessions[ownerKey]?.taskSession }
                        .lastOrNull()
                }
        }
    }

    override suspend fun activeDisplaySessions(): List<TaskDisplaySession> {
        reconciliationJob.join()
        return stateLock.withLock { sessions.values.map { it.taskSession } }
    }

    override suspend fun isDisplayClaimedByRun(displayId: Int, runSessionKey: String): Boolean {
        if (displayId <= 0 || runSessionKey.isBlank()) return false
        reconciliationJob.join()
        val ownerKey = stateLock.withLock {
            sessions.values
                .firstOrNull { it.taskSession.displayId == displayId }
                ?.taskSession
                ?.sessionKey
        } ?: return false
        return synchronized(bindingsLock) {
            runBindings[runSessionKey]?.contains(ownerKey) == true
        }
    }

    override suspend fun resolveDisplay(
        displayId: Int,
        claimForSessionKey: String?,
        expectedDisplayRef: String?,
    ): TaskDisplayResolution {
        if (displayId <= 0) {
            return TaskDisplayResolution.Unavailable(
                code = "INVALID_DISPLAY_ID",
                message = "The selected task display reference is invalid; the physical display is never controlled by DHD.",
            )
        }
        reconciliationJob.join()
        val bound = stateLock.withLock {
            sessions.values.firstOrNull { it.taskSession.displayId == displayId }
        }
        val record = findRecordByDisplayId(displayId)
        if (bound == null) {
            return unavailableForRecord(displayId, record)
        }
        val target = TaskDisplayTarget(bound.taskSession, record ?: bound.taskSession.toRecord(
            status = TaskDisplayStatus.RUNNING,
            createdAtEpochMs = nowEpochMs(),
        ))
        if (expectedDisplayRef != null && expectedDisplayRef != target.displayRef) {
            return TaskDisplayResolution.Unavailable(
                code = "DISPLAY_REFERENCE_CHANGED",
                message = "The selected task display no longer matches the supplied displayRef. Call dhd_list_displays or dhd_observe again and use the current display.",
                record = record,
            )
        }
        if (claimForSessionKey == null || claimForSessionKey == bound.taskSession.sessionKey) {
            return TaskDisplayResolution.Ready(target)
        }
        return claimDisplayForRun(target, claimForSessionKey)
    }

    override suspend fun resolveDefaultDisplay(
        claimForSessionKey: String?,
    ): TaskDisplayResolution {
        reconciliationJob.join()
        if (claimForSessionKey != null) {
            current(claimForSessionKey)?.let { currentSession ->
                return resolveDisplay(currentSession.displayId, claimForSessionKey)
            }
        }
        val now = nowEpochMs()
        val candidates = stateLock.withLock {
            sessions.values.mapNotNull { bound ->
                val record = findRecordByDisplayId(bound.taskSession.displayId)
                val eligible = record == null ||
                    (record.status.isTerminal &&
                        record.status != TaskDisplayStatus.ENDED &&
                        record.status != TaskDisplayStatus.EXPIRED &&
                        (record.expiresAtEpochMs == null || record.expiresAtEpochMs > now))
                if (eligible) TaskDisplayTarget(
                    bound.taskSession,
                    record ?: bound.taskSession.toRecord(
                        status = TaskDisplayStatus.RUNNING,
                        createdAtEpochMs = now,
                    ),
                ) else null
            }
        }
        if (candidates.isEmpty()) {
            return TaskDisplayResolution.Unavailable(
                code = "TASK_DISPLAY_UNAVAILABLE",
                message = "No active or retained task display is available. Call dhd_open_app to create one.",
            )
        }
        if (candidates.size > 1) {
            return TaskDisplayResolution.Unavailable(
                code = "DISPLAY_SELECTION_REQUIRED",
                message = "More than one task display is available. Call dhd_list_displays, choose a displayRef, then retry the tool with that displayRef.",
            )
        }
        val candidate = candidates.single()
        return if (claimForSessionKey == null) {
            TaskDisplayResolution.Ready(candidate)
        } else {
            claimDisplayForRun(candidate, claimForSessionKey)
        }
    }

    private suspend fun claimDisplayForRun(
        target: TaskDisplayTarget,
        runSessionKey: String,
    ): TaskDisplayResolution {
        val ownerKey = target.session.sessionKey
        val operationLock = operationLocks.getOrPut(ownerKey) { Mutex() }
        return operationLock.withLock {
            val currentRecord = findRecord(ownerKey) ?: target.record
            if (currentRecord.status == TaskDisplayStatus.ENDED ||
                currentRecord.status == TaskDisplayStatus.EXPIRED
            ) {
                return@withLock unavailableForRecord(target.session.displayId, currentRecord)
            }
            if (currentRecord.expiresAtEpochMs != null && currentRecord.expiresAtEpochMs <= nowEpochMs()) {
                return@withLock TaskDisplayResolution.Unavailable(
                    code = "DISPLAY_EXPIRED",
                    message = "The selected task display expired after its retention period. Call dhd_open_app with the app package to create a new display.",
                    record = currentRecord,
                )
            }
            val alreadyBoundToRun = synchronized(bindingsLock) {
                runBindings[runSessionKey]?.contains(ownerKey) == true
            }
            val isOwnedByAnotherRun = (currentRecord.status == TaskDisplayStatus.RUNNING ||
                currentRecord.status == TaskDisplayStatus.PAUSED) &&
                !alreadyBoundToRun
            if (isOwnedByAnotherRun && ownerKey != runSessionKey) {
                return@withLock TaskDisplayResolution.Unavailable(
                    code = "DISPLAY_IN_USE",
                    message = "The selected task display is being used by another active DHD run. Wait for it to finish or stop that run before selecting this display.",
                    record = currentRecord,
                )
            }
            cancelledKeys.remove(ownerKey)
            bindRunKey(runSessionKey, ownerKey)
            if (currentRecord.status.isTerminal) {
                expiryJobs.remove(ownerKey)?.cancel()
                publishRecord(
                    currentRecord.copy(
                        status = TaskDisplayStatus.RUNNING,
                        terminalAtEpochMs = null,
                        expiresAtEpochMs = null,
                        lastPurpose = DEFAULT_PURPOSE,
                        error = null,
                    ),
                )
            }
            TaskDisplayResolution.Ready(
                TaskDisplayTarget(
                    target.session,
                    findRecord(ownerKey) ?: currentRecord.copy(status = TaskDisplayStatus.RUNNING),
                ),
            )
        }
    }

    private fun unavailableForRecord(
        displayId: Int,
        record: TaskDisplayRecord?,
    ): TaskDisplayResolution.Unavailable {
        if (record == null) {
            return TaskDisplayResolution.Unavailable(
                code = "DISPLAY_NOT_FOUND",
                message = "No DHD task display matches the selected displayRef. Call dhd_list_displays to see the available displays.",
            )
        }
        return when {
            record.status == TaskDisplayStatus.EXPIRED ||
                (record.expiresAtEpochMs != null && record.expiresAtEpochMs <= nowEpochMs()) ->
                TaskDisplayResolution.Unavailable(
                    code = "DISPLAY_EXPIRED",
                    message = "The selected task display expired after its retention period. Call dhd_open_app with the app package to create a new display.",
                    record = record,
                )
            record.status == TaskDisplayStatus.ENDED ->
                TaskDisplayResolution.Unavailable(
                    code = "DISPLAY_ENDED",
                    message = "The selected task display was explicitly ended. Call dhd_open_app with the app package to create a new display.",
                    record = record,
                )
            else ->
                TaskDisplayResolution.Unavailable(
                    code = "DISPLAY_UNAVAILABLE",
                    message = "The selected task display is no longer backed by a native DHD session. Call dhd_open_app with the app package to create a new display.",
                    record = record,
                )
        }
    }

    private fun findRecordByDisplayId(displayId: Int): TaskDisplayRecord? = synchronized(recordsLock) {
        _displayRecords.value.firstOrNull { it.displayId == displayId }
    }

    override suspend fun capture(session: TaskDisplaySession): TaskDisplayCapture {
        // Captures are read-only and are also used to verify a retained display
        // from the manager after the run's action tombstone is installed.
        return withDisplayLease(session) {
            val bound = stateLock.withLock {
                sessions[session.sessionKey]
                    ?.takeIf { it.taskSession == session }
                    ?: throw TaskDisplayException("The task display session is no longer active.")
            }
            val capture = nativeManager.capture(bound.nativeSession)
            if (capture.session.sessionKey != session.sessionKey ||
                capture.session.displayId != session.displayId
            ) {
                throw TaskDisplayException("The task display changed while it was being captured.")
            }
            val foreground = resolveForeground(session)
                ?: throw TaskDisplayException(
                    "The foreground app on the selected task display could not be identified.",
                )
            val geometry = session.geometry.copy(
                width = capture.session.width,
                height = capture.session.height,
                densityDpi = capture.session.densityDpi,
                rotation = foreground.rotation,
            )
            TaskDisplayCapture(
                screenshot = capture.png,
                foreground = foreground.copy(
                    displayId = session.displayId,
                    width = capture.session.width,
                    height = capture.session.height,
                    rotation = geometry.rotation,
                ),
                taskId = session.taskId,
                geometry = geometry,
            )
        }
    }

    override suspend fun <T> withSession(
        session: TaskDisplaySession,
        block: suspend () -> T,
    ): T {
        val operationLock = operationLocks.getOrPut(session.sessionKey) { Mutex() }
        return operationLock.withLock {
            if (cancelledKeys.contains(session.sessionKey)) {
                throw TaskDisplayException("The task display session was stopped.")
            }
            stateLock.withLock {
                if (sessions[session.sessionKey]?.taskSession != session) {
                    throw TaskDisplayException("The task display session is no longer active.")
                }
            }
            block()
        }
    }

    override suspend fun attachLiveSurface(session: TaskDisplaySession, surface: Surface) {
        try {
            withDisplayLease(session) {
                val bound = stateLock.withLock {
                    sessions[session.sessionKey]
                        ?.takeIf { it.taskSession == session }
                        ?: throw TaskDisplayException("The task display session is no longer active.")
                }
                stateLock.withLock {
                    previewStateJobs.remove(session.sessionKey)?.cancel()
                    liveHandles.remove(session.sessionKey)?.handle?.close()
                }
                val handle = nativeManager.attachLiveSurface(bound.nativeSession, surface)
                stateLock.withLock {
                    if (sessions[session.sessionKey]?.taskSession != session) {
                        handle.close()
                        throw TaskDisplayException("The task display session ended during preview attach.")
                    }
                    liveHandles[session.sessionKey] = LiveHandle(surface, handle)
                    publishPreviewStateLocked(
                        session.sessionKey,
                        TaskPreviewState.Connecting(session),
                    )
                    previewStateJobs[session.sessionKey] = observePreviewState(session, handle)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val message = error.message ?: error::class.java.simpleName
            publishPreviewState(
                session.sessionKey,
                TaskPreviewState.Error(session.sessionKey, message),
            )
            throw error
        }
    }

    override suspend fun detachLiveSurface(session: TaskDisplaySession, surface: Surface) {
        val matchingSurface = stateLock.withLock {
            liveHandles[session.sessionKey]
                ?.takeIf { it.surface === surface }
        }
        if (matchingSurface == null) return
        try {
            withDisplayLease(session) {
                val handle = stateLock.withLock {
                    liveHandles[session.sessionKey]
                        ?.takeIf { it.surface === surface }
                        ?.also {
                            liveHandles.remove(session.sessionKey)
                            previewStateJobs.remove(session.sessionKey)?.cancel()
                        }
                }
                // A newer Surface may have won the lease while this stale
                // destroy callback was waiting. It owns the native stream;
                // never detach it or publish Detached for the replacement.
                if (handle == null) return@withDisplayLease
                try {
                    nativeManager.detachLiveSurface(session.nativeOrThrow())
                } finally {
                    handle?.handle?.close()
                }
                stateLock.withLock {
                    if (_previewState.value.sessionKeyOrNull() == session.sessionKey
                    ) {
                        publishPreviewStateLocked(
                            session.sessionKey,
                            TaskPreviewState.Detached,
                        )
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            // Surface destruction is best-effort. A stale detach must never
            // affect the newly attached surface, and the caller has no useful
            // recovery action once its Surface is gone.
        }
    }

    override suspend fun retryLiveSurface(sessionKey: String) {
        val session = current(sessionKey) ?: return
        val surface = stateLock.withLock {
            liveHandles[sessionKey]?.surface
        } ?: return
        // attachLiveSurface serializes the replacement with any in-flight
        // detach and closes the failed decoder before opening a fresh AVC
        // connection on the same TextureView surface.
        attachLiveSurface(session, surface)
    }

    override fun cancel(sessionKey: String) {
        cancelledKeys += sessionKey
        // Tombstone both layers synchronously. This closes the race where an
        // action/create has crossed into the daemon but cleanup has not yet
        // acquired its per-key operation lease. The display itself remains
        // alive until retain() schedules expiry or the user explicitly closes
        // it from the task-display manager.
        nativeManager.cancel(sessionKey)
    }

    override fun cancelForRun(sessionKey: String) {
        // A retained display can be claimed by a later coordinator run while
        // keeping its original native owner key. Invalidate every owner bound
        // to this run so an in-flight action cannot outlive the run that
        // authorized it.
        ownerKeysForRun(sessionKey).forEach(::cancel)
    }

    override suspend fun retain(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String?,
    ) {
        require(status.isTerminal) { "Only terminal statuses may retain a task display." }
        val bound = stateLock.withLock { sessions[sessionKey] }
        val existing = findRecord(sessionKey)
        if (existing?.status == TaskDisplayStatus.ENDED || existing?.status == TaskDisplayStatus.EXPIRED) {
            return
        }
        val base = existing ?: bound?.taskSession?.toRecord(
            status = status,
            createdAtEpochMs = nowEpochMs(),
        ) ?: return
        val retained = base.terminalized(
            status = status,
            terminalAtEpochMs = nowEpochMs(),
            retentionMs = terminalRetentionMs,
            error = error,
        )
        publishRecord(retained)
        scheduleExpiry(retained)
    }

    override suspend fun retainForRun(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String?,
    ) {
        boundOwnerKeysForRun(sessionKey).forEach { ownerKey ->
            retain(ownerKey, status, error)
        }
    }

    override suspend fun updateStatus(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String?,
    ) {
        val existing = findRecord(sessionKey) ?: return
        if (existing.status.isTerminal && !status.isTerminal) return
        publishRecord(
            existing.copy(
                status = status,
                error = error?.trim()?.take(MAX_RECORD_ERROR_CHARS),
            ),
        )
    }

    override suspend fun updateStatusForRun(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String?,
    ) {
        boundOwnerKeysForRun(sessionKey).forEach { ownerKey ->
            updateStatus(ownerKey, status, error)
        }
    }

    override fun updatePurpose(sessionKey: String, purpose: String) {
        val safePurpose = purpose.trim().take(MAX_RECORD_PURPOSE_CHARS).ifBlank { return }
        val existing = findRecord(sessionKey) ?: return
        publishRecord(existing.copy(lastPurpose = safePurpose))
    }

    override fun updatePurposeForRun(sessionKey: String, purpose: String) {
        boundOwnerKeysForRun(sessionKey).forEach { ownerKey ->
            updatePurpose(ownerKey, purpose)
        }
    }

    override suspend fun close(session: TaskDisplaySession) {
        close(session.sessionKey, expected = session)
    }

    override suspend fun close(sessionKey: String) {
        close(sessionKey, expected = null)
    }

    private suspend fun close(sessionKey: String, expected: TaskDisplaySession?) {
        val operationLock = operationLocks.getOrPut(sessionKey) { Mutex() }
        operationLock.withLock {
            val shouldClose = stateLock.withLock {
                val current = sessions[sessionKey]
                if (expected != null && current?.taskSession != expected) {
                    false
                } else {
                    sessions.remove(sessionKey)
                    previewStateJobs.remove(sessionKey)?.cancel()
                    liveHandles.remove(sessionKey)?.handle?.close()
                    if (_activeSession.value?.sessionKey == sessionKey) {
                        _activeSession.value = sessions.values.lastOrNull()?.taskSession
                    }
                    if (_previewState.value.sessionKeyOrNull() == sessionKey) {
                        publishPreviewStateLocked(sessionKey, TaskPreviewState.Detached)
                    }
                    true
                }
            }
            if (shouldClose || expected == null) {
                // Native close is by owner key so it also cancels a create that
                // has not returned yet. It is safe after a stopped create too.
                runCatching { nativeManager.close(sessionKey) }
            }
            if (!shouldClose && expected != null) return@withLock
            unbindOwner(sessionKey)
            expiryJobs.remove(sessionKey)?.cancel()
            val existing = findRecord(sessionKey)
            if (existing != null) {
                publishRecord(
                    existing.copy(
                        status = TaskDisplayStatus.ENDED,
                        terminalAtEpochMs = existing.terminalAtEpochMs ?: nowEpochMs(),
                        expiresAtEpochMs = nowEpochMs(),
                        lastPurpose = existing.lastPurpose.ifBlank { "Display ended" },
                    ),
                )
            }
        }
    }

    override suspend fun closeTaskDisplay(
        displayId: Int,
        expectedDisplayRef: String?,
    ): TaskDisplayCloseResult {
        if (displayId <= 0) {
            return TaskDisplayCloseResult.Rejected(
                code = "INVALID_DISPLAY_ID",
                message = "The selected task display reference is invalid; the physical display is never controlled by DHD.",
            )
        }
        reconciliationJob.join()
        val record = findRecordByDisplayId(displayId)
            ?: return TaskDisplayCloseResult.Rejected(
                code = "DISPLAY_NOT_FOUND",
                message = "No DHD task display matches the selected displayRef. Call dhd_list_displays to see the available displays.",
            )
        if (expectedDisplayRef != null && expectedDisplayRef != record.displayRef) {
            return TaskDisplayCloseResult.Rejected(
                code = "DISPLAY_REFERENCE_CHANGED",
                message = "The selected task display no longer matches the supplied displayRef. Call dhd_list_displays and retry with the current display.",
                record = record,
            )
        }
        if (record.status == TaskDisplayStatus.ENDED) {
            return TaskDisplayCloseResult.Rejected(
                code = "DISPLAY_ENDED",
                message = "The selected task display has already been ended.",
                record = record,
            )
        }
        if (record.status == TaskDisplayStatus.EXPIRED) {
            return TaskDisplayCloseResult.Rejected(
                code = "DISPLAY_EXPIRED",
                message = "The selected task display has already expired and cannot be closed again.",
                record = record,
            )
        }
        val bound = stateLock.withLock {
            sessions.values.firstOrNull { it.taskSession.displayId == displayId }
        }
        if (bound != null) {
            close(bound.taskSession)
        } else {
            // The persisted record can outlive a native session. Closing by
            // its exact owner key is still safe and never targets display 0.
            close(record.sessionKey)
        }
        val closed = findRecord(record.sessionKey) ?: record.copy(
            status = TaskDisplayStatus.ENDED,
            terminalAtEpochMs = record.terminalAtEpochMs ?: nowEpochMs(),
            expiresAtEpochMs = nowEpochMs(),
        )
        return TaskDisplayCloseResult.Closed(closed)
    }

    /** Serialize preview attach/detach without applying the action tombstone. */
    private suspend fun <T> withDisplayLease(
        session: TaskDisplaySession,
        block: suspend () -> T,
    ): T {
        val operationLock = operationLocks.getOrPut(session.sessionKey) { Mutex() }
        return operationLock.withLock {
            stateLock.withLock {
                if (sessions[session.sessionKey]?.taskSession != session) {
                    throw TaskDisplayException("The task display session is no longer active.")
                }
            }
            block()
        }
    }

    private fun restorePersistedRecords() {
        val persisted = conversationStore?.listTaskDisplays().orEmpty()
        if (persisted.isEmpty()) return
        val now = nowEpochMs()
        val restored = persisted.map { record ->
            when {
                record.status == TaskDisplayStatus.EXPIRED || record.status == TaskDisplayStatus.ENDED -> record
                record.expiresAtEpochMs != null && record.expiresAtEpochMs <= now -> record.copy(
                    status = TaskDisplayStatus.EXPIRED,
                    error = record.error ?: "The retained display expired.",
                )
                else -> record
            }
        }
        synchronized(recordsLock) {
            _displayRecords.value = sortRecords(restored)
        }
        restored.zip(persisted).forEach { (next, previous) ->
            if (next != previous) conversationStore?.upsertTaskDisplay(next)
        }
    }

    private suspend fun reconcileNativeSessionsWithRetry() {
        var lastFailure: Throwable? = null
        repeat(RECONCILIATION_ATTEMPTS) { attempt ->
            try {
                reconcileNativeSessions()
                return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                lastFailure = error
                if (attempt + 1 < RECONCILIATION_ATTEMPTS) {
                    delay(RECONCILIATION_RETRY_DELAY_MS * (1L shl attempt))
                }
            }
        }
        markReconciliationUnavailable(
            lastFailure?.message ?: "The native display could not be reconciled.",
        )
    }

    private suspend fun reconcileNativeSessions() {
        val persisted = displayRecords.value
        val expectedKeys = persisted.map { it.sessionKey }.toSet()
        val native = nativeManager.reconcile(expectedKeys)
        val now = nowEpochMs()
        persisted.forEach { record ->
            val nativeSession = native[record.sessionKey]
            if (nativeSession == null) {
                val next = when {
                    record.status == TaskDisplayStatus.EXPIRED -> record
                    record.status == TaskDisplayStatus.ENDED -> record
                    record.status.isTerminal && record.expiresAtEpochMs != null &&
                        record.expiresAtEpochMs <= now -> record.copy(
                        status = TaskDisplayStatus.EXPIRED,
                        error = record.error ?: "The retained display expired.",
                    )
                    else -> record.copy(
                        status = TaskDisplayStatus.UNAVAILABLE,
                        error = record.error ?: "The native display was unavailable after app restart.",
                    )
                }
                if (next != record) publishRecord(next)
                if (next.status != TaskDisplayStatus.ENDED && next.status != TaskDisplayStatus.EXPIRED) {
                    scheduleExpiry(next)
                }
                return@forEach
            }

            val taskSession = nativeSession.toTaskSession(appContext)
            val sameIdentity = taskSession.displayId == record.displayId &&
                taskSession.taskId == record.taskId &&
                taskSession.packageName == record.packageName &&
                taskSession.geometry.width == record.width &&
                taskSession.geometry.height == record.height &&
                taskSession.geometry.densityDpi == record.densityDpi
            val expired = record.status == TaskDisplayStatus.EXPIRED ||
                (record.expiresAtEpochMs != null && record.expiresAtEpochMs <= now)
            val ended = record.status == TaskDisplayStatus.ENDED
            if (!sameIdentity || expired || ended) {
                runCatching { nativeManager.close(record.sessionKey) }
                val next = when {
                    expired -> record.copy(
                        status = TaskDisplayStatus.EXPIRED,
                        error = record.error ?: "The retained display expired.",
                    )
                    ended -> record
                    else -> record.copy(
                        status = TaskDisplayStatus.UNAVAILABLE,
                        error = record.error ?: "The native display did not match the persisted task identity.",
                    )
                }
                publishRecord(next)
                if (next.status != TaskDisplayStatus.ENDED && next.status != TaskDisplayStatus.EXPIRED) {
                    scheduleExpiry(next)
                }
                return@forEach
            }

            stateLock.withLock {
                sessions[record.sessionKey] = BoundSession(nativeSession, taskSession)
                bindRunKey(record.sessionKey, record.sessionKey)
                // Keep the terminal action tombstone, but allow an unexpired
                // running/attention display to be used by the coordinator
                // after an Activity/process restart.
                if (record.status.isTerminal) {
                    cancelledKeys += record.sessionKey
                } else {
                    cancelledKeys.remove(record.sessionKey)
                }
            }
            scheduleExpiry(record)
        }
        stateLock.withLock {
            val liveRecords = persisted.filter {
                it.status == TaskDisplayStatus.RUNNING || it.status == TaskDisplayStatus.PAUSED
            }
            val candidateRecords = liveRecords.ifEmpty {
                persisted.filter {
                    it.status.isTerminal &&
                        it.status != TaskDisplayStatus.ENDED &&
                        it.status != TaskDisplayStatus.EXPIRED
                }
            }
            _activeSession.value = candidateRecords
                .mapNotNull { sessions[it.sessionKey]?.taskSession }
                .maxByOrNull { session -> candidateRecords.first { it.sessionKey == session.sessionKey }.createdAtEpochMs }
        }
    }

    private fun markReconciliationUnavailable(message: String) {
        val now = nowEpochMs()
        displayRecords.value.forEach { record ->
            val next = when {
                record.status == TaskDisplayStatus.ENDED ||
                    record.status == TaskDisplayStatus.EXPIRED -> record
                record.status.isTerminal && record.expiresAtEpochMs != null &&
                    record.expiresAtEpochMs <= now -> record.copy(
                    status = TaskDisplayStatus.EXPIRED,
                    error = record.error ?: "The retained display expired.",
                )
                else -> record.copy(
                    status = TaskDisplayStatus.UNAVAILABLE,
                    error = record.error ?: message,
                )
            }
            if (next != record) publishRecord(next)
            if (next.status != TaskDisplayStatus.ENDED && next.status != TaskDisplayStatus.EXPIRED) {
                scheduleExpiry(next)
            }
        }
    }

    private fun scheduleExpiry(record: TaskDisplayRecord) {
        val expiresAt = record.expiresAtEpochMs ?: return
        expiryJobs.remove(record.sessionKey)?.cancel()
        expiryJobs[record.sessionKey] = scope.launch {
            val remaining = expiresAt - nowEpochMs()
            if (remaining > 0) delay(remaining)
            expire(record.sessionKey, expiresAt)
        }
    }

    private suspend fun expire(sessionKey: String, expectedExpiry: Long) {
        val record = findRecord(sessionKey) ?: return
        if ((record.terminalAtEpochMs == null && !record.status.isTerminal) ||
            record.expiresAtEpochMs != expectedExpiry ||
            expectedExpiry > nowEpochMs()
        ) return
        closeInternal(
            sessionKey,
            finalStatus = TaskDisplayStatus.EXPIRED,
            expectedExpiry = expectedExpiry,
        )
    }

    private suspend fun closeInternal(
        sessionKey: String,
        finalStatus: TaskDisplayStatus,
        expectedExpiry: Long? = null,
    ) {
        val operationLock = operationLocks.getOrPut(sessionKey) { Mutex() }
        operationLock.withLock {
            if (expectedExpiry != null) {
                val current = findRecord(sessionKey)
                if (current == null ||
                    current.expiresAtEpochMs != expectedExpiry ||
                    !current.status.isTerminal ||
                    current.status == TaskDisplayStatus.ENDED ||
                    current.status == TaskDisplayStatus.EXPIRED ||
                    expectedExpiry > nowEpochMs()
                ) {
                    return@withLock
                }
            }
            cancelledKeys += sessionKey
            stateLock.withLock {
                sessions.remove(sessionKey)
                previewStateJobs.remove(sessionKey)?.cancel()
                liveHandles.remove(sessionKey)?.handle?.close()
                if (_activeSession.value?.sessionKey == sessionKey) {
                    _activeSession.value = sessions.values.lastOrNull()?.taskSession
                }
                if (_previewState.value.sessionKeyOrNull() == sessionKey) {
                    publishPreviewStateLocked(sessionKey, TaskPreviewState.Detached)
                }
            }
            unbindOwner(sessionKey)
            runCatching { nativeManager.close(sessionKey) }
            expiryJobs.remove(sessionKey)?.cancel()
            findRecord(sessionKey)?.let { existing ->
                publishRecord(
                    existing.copy(
                        status = finalStatus,
                        terminalAtEpochMs = existing.terminalAtEpochMs ?: nowEpochMs(),
                        expiresAtEpochMs = existing.expiresAtEpochMs ?: nowEpochMs(),
                    ),
                )
            }
        }
    }

    private fun publishRecord(record: TaskDisplayRecord) {
        synchronized(recordsLock) {
            val next = _displayRecords.value
                .filterNot { it.sessionKey == record.sessionKey } + record
            _displayRecords.value = sortRecords(next)
            conversationStore?.upsertTaskDisplay(record)
        }
    }

    private fun findRecord(sessionKey: String): TaskDisplayRecord? = synchronized(recordsLock) {
        _displayRecords.value.firstOrNull { it.sessionKey == sessionKey }
    }

    /** Keep one native owner associated with at most one logical run. */
    private fun bindRunKey(runKey: String, ownerKey: String) {
        synchronized(bindingsLock) {
            runBindings.forEach { (boundRunKey, owners) ->
                if (boundRunKey != runKey) owners.remove(ownerKey)
            }
            runBindings.values.removeAll { it.isEmpty() }
            runBindings.getOrPut(runKey) { linkedSetOf() }.add(ownerKey)
        }
    }

    private fun ownerKeysForRun(runKey: String): List<String> = synchronized(bindingsLock) {
        val owners = runBindings[runKey].orEmpty()
        if (owners.isNotEmpty()) {
            owners.toList()
        } else if (runBindings.any { (otherRunKey, boundOwners) ->
                otherRunKey != runKey && runKey in boundOwners
            }
        ) {
            // This key is the native owner of a display that has already been
            // claimed by another run. A late stop from the old run must not
            // cancel the new run's display.
            emptyList()
        } else {
            // Preserve the create-before-bind cancellation race: a run that
            // has not published a display still needs a tombstone by its own
            // key so a late native create is closed safely.
            listOf(runKey)
        }
    }

    private fun boundOwnerKeysForRun(runKey: String): List<String> = synchronized(bindingsLock) {
        runBindings[runKey]?.toList().orEmpty()
    }

    private fun unbindOwner(ownerKey: String) {
        synchronized(bindingsLock) {
            runBindings.values.forEach { it.remove(ownerKey) }
            runBindings.values.removeAll { it.isEmpty() }
        }
    }

    private fun sortRecords(records: List<TaskDisplayRecord>): List<TaskDisplayRecord> =
        records.sortedWith(compareByDescending<TaskDisplayRecord> { it.createdAtEpochMs }.thenBy { it.sessionKey })

    private fun publishPreviewState(sessionKey: String, state: TaskPreviewState) {
        synchronized(recordsLock) {
            publishPreviewStateValue(sessionKey, state)
        }
    }

    private fun publishPreviewStateLocked(sessionKey: String, state: TaskPreviewState) {
        publishPreviewStateValue(sessionKey, state)
    }

    private fun publishPreviewStateValue(sessionKey: String, state: TaskPreviewState) {
        // The legacy single-preview flow feeds the inline assistant card. A
        // retained display opened from the manager may attach concurrently;
        // keep that viewer in the per-session map without replacing the
        // active task's inline state.
        val activeKey = _activeSession.value?.sessionKey
        if (activeKey == null || activeKey == sessionKey ||
            _previewState.value.sessionKeyOrNull() == sessionKey
        ) {
            _previewState.value = state
        }
        _previewStates.value = _previewStates.value.toMutableMap().apply {
            if (state is TaskPreviewState.Detached) remove(sessionKey) else put(sessionKey, state)
        }
    }

    private suspend fun resolveForeground(session: TaskDisplaySession): ForegroundAppInfo? {
        val result = processRunner.run(listOf("dumpsys", "activity", "activities"))
        if (result.timedOut || result.exitCode != 0) return null
        val text = result.stdout.toString(Charsets.UTF_8)
        val focused = parseDisplayFocusedWindow(text, session.displayId) ?: return null
        val display = appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(session.displayId)
        val rotation = display?.rotation ?: return null
        return ForegroundAppInfo(
            packageName = focused.packageName,
            activityName = focused.activityName,
            displayId = session.displayId,
            rotation = rotation,
            width = session.geometry.width,
            height = session.geometry.height,
        )
    }

    /**
     * Mirror the decoder's state without exposing the native handle to the
     * execution layer. The identity check is essential: a late CLOSED/ERROR
     * emission from an old decoder must not overwrite a replacement surface.
     */
    private fun observePreviewState(
        session: TaskDisplaySession,
        handle: DhdLivePreviewHandle,
    ): Job = scope.launch {
        handle.state.collectLatest { state ->
            stateLock.withLock {
                if (liveHandles[session.sessionKey]?.handle !== handle) return@withLock
                publishPreviewStateLocked(session.sessionKey, when (state.phase) {
                    DhdLivePreviewPhase.CONNECTING -> TaskPreviewState.Connecting(session)
                    DhdLivePreviewPhase.LIVE -> TaskPreviewState.Attached(session)
                    DhdLivePreviewPhase.ERROR -> TaskPreviewState.Error(
                        sessionKey = session.sessionKey,
                        message = state.message ?: "The live preview decoder failed.",
                    )
                    DhdLivePreviewPhase.CLOSED -> TaskPreviewState.Detached
                })
            }
        }
    }

    private data class FocusedComponent(val packageName: String, val activityName: String)

    private fun parseDisplayFocusedWindow(text: String, displayId: Int): FocusedComponent? {
        var currentDisplay: Int? = null
        var candidate: FocusedComponent? = null
        for (line in text.lineSequence()) {
            DISPLAY_ID_REGEX.find(line)?.let { match ->
                currentDisplay = match.groupValues
                    .drop(1)
                    .firstOrNull(String::isNotBlank)
                    ?.toIntOrNull()
            }
            val marker = line.contains("topResumedActivity", ignoreCase = true) ||
                line.contains("mResumedActivity", ignoreCase = true) ||
                line.contains("mCurrentFocus", ignoreCase = true) ||
                line.contains("mFocusedApp", ignoreCase = true)
            if (!marker || currentDisplay != displayId) continue
            COMPONENT_REGEX.find(line)?.let { match ->
                val packageName = match.groupValues[1]
                val rawActivity = match.groupValues[2]
                val activityName = if (rawActivity.startsWith('.')) packageName + rawActivity else rawActivity
                candidate = FocusedComponent(packageName, activityName)
            }
        }
        return candidate
    }

    private fun DhdVirtualDisplaySession.toTaskSession(context: Context): TaskDisplaySession {
        val rotation = context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId)
            ?.rotation
            ?: Surface.ROTATION_0
        return TaskDisplaySession(
            sessionKey = sessionKey,
            // Native currently exposes the owner key + logical display ID;
            // combining them gives an immutable identity across display-ID
            // reuse and remains distinct when a backend recreates a session.
            taskId = "$sessionKey@$displayId",
            packageName = packageName,
            displayId = displayId,
            streamEndpoint = "127.0.0.1:$streamPort",
            geometry = TaskDisplayGeometry(width, height, densityDpi, rotation),
        )
    }

    private fun TaskDisplaySession.toRecord(
        status: TaskDisplayStatus,
        createdAtEpochMs: Long,
        terminalAtEpochMs: Long? = null,
        expiresAtEpochMs: Long? = null,
        lastPurpose: String = DEFAULT_PURPOSE,
        error: String? = null,
    ): TaskDisplayRecord = TaskDisplayRecord(
        sessionKey = sessionKey,
        taskId = taskId,
        packageName = packageName.ifBlank { "unknown" },
        displayId = displayId,
        width = geometry.width,
        height = geometry.height,
        densityDpi = geometry.densityDpi,
        rotation = geometry.rotation,
        status = status,
        createdAtEpochMs = createdAtEpochMs,
        terminalAtEpochMs = terminalAtEpochMs,
        expiresAtEpochMs = expiresAtEpochMs,
        lastPurpose = lastPurpose,
        error = error,
    )

    private suspend fun TaskDisplaySession.nativeOrThrow(): DhdVirtualDisplaySession = stateLock.withLock {
        sessions[sessionKey]
            ?.takeIf { it.taskSession == this }
            ?.nativeSession
            ?: throw TaskDisplayException("The task display session is no longer active.")
    }

    private data class BoundSession(
        val nativeSession: DhdVirtualDisplaySession,
        val taskSession: TaskDisplaySession,
    )

    private data class LiveHandle(
        val surface: Surface,
        val handle: DhdLivePreviewHandle,
    )

    private fun TaskPreviewState.sessionKeyOrNull(): String? = when (this) {
        TaskPreviewState.Detached -> null
        is TaskPreviewState.Connecting -> session.sessionKey
        is TaskPreviewState.Attached -> session.sessionKey
        is TaskPreviewState.Error -> sessionKey
    }

    class TaskDisplayException(message: String) : IOException(message)

    companion object {
        const val TERMINAL_RETENTION_MS: Long = 30 * 60 * 1000L
        private const val DEFAULT_PURPOSE = "Preparing request"
        private const val MAX_RECORD_PURPOSE_CHARS = 240
        private const val MAX_RECORD_ERROR_CHARS = 4_000
        private const val RECONCILIATION_ATTEMPTS = 4
        private const val RECONCILIATION_RETRY_DELAY_MS = 250L
        private val DISPLAY_ID_REGEX = Regex(
            "(?:\\bdisplayId\\s*[:=]?\\s*(\\d+))|(?:\\bmDisplayId\\s*[:=]?\\s*(\\d+))|(?:\\bDisplay\\s*#?\\s*(\\d+)\\b)",
            RegexOption.IGNORE_CASE,
        )
        private val COMPONENT_REGEX = Regex(
            "\\b([A-Za-z][A-Za-z0-9_.$]*)/(\\.?[A-Za-z0-9_.$]+)",
        )
    }
}
