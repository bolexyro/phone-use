package com.phonecontrol.assistant.developer

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import com.phonecontrol.assistant.execution.ForegroundAppInfo
import com.phonecontrol.assistant.execution.PhoneProcessRunner
import com.phonecontrol.assistant.execution.TaskDisplayBackend
import com.phonecontrol.assistant.execution.TaskDisplayCapture
import com.phonecontrol.assistant.execution.TaskDisplayGeometry
import com.phonecontrol.assistant.execution.TaskDisplaySession
import com.phonecontrol.assistant.execution.TaskDisplaySpec
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
) : TaskDisplayBackend {
    private val appContext = context.applicationContext
    private val stateLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = LinkedHashMap<String, BoundSession>()
    private val cancelledKeys = ConcurrentHashMap.newKeySet<String>()
    private val operationLocks = ConcurrentHashMap<String, Mutex>()
    private val liveHandles = mutableMapOf<String, LiveHandle>()
    private val previewStateJobs = mutableMapOf<String, Job>()
    private val _activeSession = MutableStateFlow<TaskDisplaySession?>(null)
    private val _previewState = MutableStateFlow<TaskPreviewState>(TaskPreviewState.Detached)

    /** The one task display currently shown by the DHD task UI, if any. */
    val activeSession: StateFlow<TaskDisplaySession?> = _activeSession.asStateFlow()

    /** Attach/detach/error status for the read-only AVC decoder surface. */
    val previewState: StateFlow<TaskPreviewState> = _previewState.asStateFlow()

    override suspend fun create(
        sessionKey: String,
        packageName: String,
        spec: TaskDisplaySpec,
    ): TaskDisplaySession {
        val operationLock = operationLocks.getOrPut(sessionKey) { Mutex() }
        return operationLock.withLock {
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
            val shouldClose = stateLock.withLock {
                if (cancelledKeys.contains(sessionKey)) {
                    true
                } else {
                    sessions[sessionKey] = bound
                    _activeSession.value = taskSession
                    _previewState.value = TaskPreviewState.Connecting(taskSession)
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

    override suspend fun current(sessionKey: String): TaskDisplaySession? = stateLock.withLock {
        if (cancelledKeys.contains(sessionKey)) null else sessions[sessionKey]?.taskSession
    }

    override suspend fun capture(session: TaskDisplaySession): TaskDisplayCapture {
        return withSession(session) {
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
                    "The foreground app on display ${session.displayId} could not be identified.",
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
            withSession(session) {
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
                    if (cancelledKeys.contains(session.sessionKey) || sessions[session.sessionKey]?.taskSession != session) {
                        handle.close()
                        throw TaskDisplayException("The task display session stopped during preview attach.")
                    }
                    liveHandles[session.sessionKey] = LiveHandle(surface, handle)
                    _previewState.value = TaskPreviewState.Connecting(session)
                    previewStateJobs[session.sessionKey] = observePreviewState(session, handle)
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val message = error.message ?: error::class.java.simpleName
            _previewState.value = TaskPreviewState.Error(session.sessionKey, message)
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
            withSession(session) {
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
                if (handle == null) return@withSession
                try {
                    nativeManager.detachLiveSurface(session.nativeOrThrow())
                } finally {
                    handle?.handle?.close()
                }
                stateLock.withLock {
                    if (_previewState.value.sessionKeyOrNull() == session.sessionKey
                    ) {
                        _previewState.value = TaskPreviewState.Detached
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

    override fun cancel(sessionKey: String) {
        cancelledKeys += sessionKey
        // Tombstone the native manager synchronously as well. This closes the
        // race where create has crossed into the daemon but adapter cleanup
        // has not acquired its per-key operation lease yet.
        nativeManager.cancel(sessionKey)
        scope.launch { close(sessionKey) }
    }

    override suspend fun close(session: TaskDisplaySession) {
        close(session.sessionKey, expected = session)
    }

    override suspend fun close(sessionKey: String) {
        close(sessionKey, expected = null)
    }

    private suspend fun close(sessionKey: String, expected: TaskDisplaySession?) {
        cancelledKeys += sessionKey
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
                    if (_activeSession.value?.sessionKey == sessionKey) _activeSession.value = null
                    if (_previewState.value.sessionKeyOrNull() == sessionKey) {
                        _previewState.value = TaskPreviewState.Detached
                    }
                    true
                }
            }
            if (shouldClose || expected == null) {
                // Native close is by owner key so it also cancels a create that
                // has not returned yet. It is safe after a stopped create too.
                runCatching { nativeManager.close(sessionKey) }
            }
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
                _previewState.value = when (state.phase) {
                    DhdLivePreviewPhase.CONNECTING -> TaskPreviewState.Connecting(session)
                    DhdLivePreviewPhase.LIVE -> TaskPreviewState.Attached(session)
                    DhdLivePreviewPhase.ERROR -> TaskPreviewState.Error(
                        sessionKey = session.sessionKey,
                        message = state.message ?: "The live preview decoder failed.",
                    )
                    DhdLivePreviewPhase.CLOSED -> TaskPreviewState.Detached
                }
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
            displayId = displayId,
            streamEndpoint = "127.0.0.1:$streamPort",
            geometry = TaskDisplayGeometry(width, height, densityDpi, rotation),
        )
    }

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
        private val DISPLAY_ID_REGEX = Regex(
            "(?:\\bdisplayId\\s*[:=]?\\s*(\\d+))|(?:\\bmDisplayId\\s*[:=]?\\s*(\\d+))|(?:\\bDisplay\\s*#?\\s*(\\d+)\\b)",
            RegexOption.IGNORE_CASE,
        )
        private val COMPONENT_REGEX = Regex(
            "\\b([A-Za-z][A-Za-z0-9_.$]*)/(\\.?[A-Za-z0-9_.$]+)",
        )
    }
}
