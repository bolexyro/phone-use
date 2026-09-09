package com.phonecontrol.assistant.execution

import android.view.Surface
import kotlinx.coroutines.flow.StateFlow

/**
 * The fixed buffer geometry used by an agent display.  The Compose preview may
 * be resized independently; changing its measured size must never change this
 * coordinate space.
 */
data class TaskDisplaySpec(
    val width: Int = DEFAULT_WIDTH,
    val height: Int = DEFAULT_HEIGHT,
    val densityDpi: Int = DEFAULT_DENSITY_DPI,
    /**
     * Density exposed to apps on the task display. This is separate from
     * [densityDpi], which is the base density used when the display buffer is
     * created; the pixel geometry used for coordinates and streaming is fixed.
     */
    val appDensityDpi: Int = DEFAULT_APP_DENSITY_DPI,
) {
    init {
        require(width > 0) { "Task display width must be positive." }
        require(height > 0) { "Task display height must be positive." }
        require(densityDpi > 0) { "Task display density must be positive." }
        require(appDensityDpi in 120..640) {
            "Task app density must be between 120 and 640 dpi."
        }
    }

    private companion object {
        const val DEFAULT_WIDTH = 720
        const val DEFAULT_HEIGHT = 1560
        // Keep the base display density aligned with the S23's compatibility
        // density. The app-visible override below changes the dp viewport
        // without changing the encoded 720x1560 pixel buffer.
        const val DEFAULT_DENSITY_DPI = 420
        // The S23's 720px-wide virtual display is only about 274dp at 420dpi.
        // A per-display 320dpi override gives apps a roughly 360dp-wide
        // viewport without changing the display's pixel geometry.
        const val DEFAULT_APP_DENSITY_DPI = 320
    }
}

/** Immutable identity and geometry for one task-owned virtual display. */
data class TaskDisplaySession(
    /** The coordinator session key that owns this display. */
    val sessionKey: String,
    /** A backend/task identity. It must change when a display is recreated. */
    val taskId: String,
    val displayId: Int,
    val streamEndpoint: String? = null,
    val geometry: TaskDisplayGeometry,
    /** The package launched on this display, when known. */
    val packageName: String = "",
) {
    init {
        require(sessionKey.isNotBlank()) { "Task display session key must not be blank." }
        require(taskId.isNotBlank()) { "Task display task ID must not be blank." }
        require(displayId > 0) { "Task display ID must be greater than the default display." }
    }
}

/** Lifecycle states exposed to the task-display manager and live viewer. */
enum class TaskDisplayStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    STOPPED,
    UNAVAILABLE,
    ENDED,
    EXPIRED,
}

/** Durable, display-scoped metadata. No screenshots or private reasoning are stored. */
data class TaskDisplayRecord(
    val sessionKey: String,
    val taskId: String,
    val packageName: String,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int,
    val status: TaskDisplayStatus,
    val createdAtEpochMs: Long,
    val terminalAtEpochMs: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val lastPurpose: String = "Preparing request",
    val error: String? = null,
) {
    init {
        require(sessionKey.isNotBlank()) { "Task display record session key must not be blank." }
        require(taskId.isNotBlank()) { "Task display record task ID must not be blank." }
        require(packageName.isNotBlank()) { "Task display record package name must not be blank." }
        require(displayId > 0) { "Task display record ID must be greater than the default display." }
        require(width > 0 && height > 0 && densityDpi > 0) { "Task display record geometry is invalid." }
        require(createdAtEpochMs >= 0) { "Task display record creation time is invalid." }
    }

    val geometry: TaskDisplayGeometry
        get() = TaskDisplayGeometry(width, height, densityDpi, rotation)

    /** Opaque generation-aware reference safe to expose to the agent. */
    val displayRef: String
        get() = taskDisplayReference(sessionKey, displayId)
}

/** A display plus its durable lifecycle metadata, resolved by logical ID. */
data class TaskDisplayTarget(
    val session: TaskDisplaySession,
    val record: TaskDisplayRecord,
) {
    val displayRef: String
        get() = record.displayRef
}

sealed interface TaskDisplayResolution {
    data class Ready(val target: TaskDisplayTarget) : TaskDisplayResolution

    data class Unavailable(
        val code: String,
        val message: String,
        val record: TaskDisplayRecord? = null,
    ) : TaskDisplayResolution
}

sealed interface TaskDisplayCloseResult {
    data class Closed(val record: TaskDisplayRecord) : TaskDisplayCloseResult

    data class Rejected(
        val code: String,
        val message: String,
        val record: TaskDisplayRecord? = null,
    ) : TaskDisplayCloseResult
}

/** Stable within one native display generation; never exposes the owner key. */
fun taskDisplayReference(sessionKey: String, displayId: Int): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest("$sessionKey@$displayId".toByteArray(Charsets.UTF_8))
    return buildString(18) {
        append("dsp_")
        digest.take(7).forEach { byte -> append("%02x".format(byte.toInt() and 0xff)) }
    }
}

/**
 * Apply a terminal state without touching the display itself. Keeping this
 * calculation pure makes retention timing deterministic for the registry and
 * its injected-clock tests.
 */
fun TaskDisplayRecord.terminalized(
    status: TaskDisplayStatus,
    terminalAtEpochMs: Long,
    retentionMs: Long,
    error: String? = null,
): TaskDisplayRecord {
    require(status.isTerminal) { "Only terminal statuses may retain a task display." }
    val terminalAt = terminalAtEpochMs.coerceAtLeast(createdAtEpochMs)
    val firstTerminalAt = this.terminalAtEpochMs ?: terminalAt
    val expiresAt = when (status) {
        TaskDisplayStatus.ENDED,
        TaskDisplayStatus.EXPIRED,
        -> firstTerminalAt
        else -> firstTerminalAt + retentionMs.coerceAtLeast(0L)
    }
    return copy(
        status = status,
        terminalAtEpochMs = firstTerminalAt,
        expiresAtEpochMs = expiresAt,
        lastPurpose = lastPurpose
            .takeIf { it.isNotBlank() && it != "Preparing request" }
            ?: status.terminalPurpose(),
        error = error?.trim()?.take(MAX_TASK_DISPLAY_ERROR_CHARS),
    )
}

private fun TaskDisplayStatus.terminalPurpose(): String = when (this) {
    TaskDisplayStatus.COMPLETED -> "Task complete"
    TaskDisplayStatus.FAILED -> "Task failed"
    TaskDisplayStatus.STOPPED -> "Task stopped"
    TaskDisplayStatus.ENDED -> "Display ended"
    TaskDisplayStatus.EXPIRED -> "Display expired"
    else -> "Preparing request"
}

private const val MAX_TASK_DISPLAY_ERROR_CHARS = 4_000

data class TaskDisplayGeometry(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val rotation: Int,
) {
    init {
        require(width > 0) { "Task display width must be positive." }
        require(height > 0) { "Task display height must be positive." }
        require(densityDpi > 0) { "Task display density must be positive." }
    }
}

/** Exact frame and foreground metadata returned by a task-display capture. */
data class TaskDisplayCapture(
    val screenshot: ByteArray,
    val foreground: ForegroundAppInfo,
    val taskId: String,
    val geometry: TaskDisplayGeometry,
) {
    init {
        require(screenshot.isNotEmpty()) { "Task display captures must contain PNG bytes." }
        require(taskId.isNotBlank()) { "Task display capture task ID must not be blank." }
    }
}

/**
 * Phone-local virtual-display boundary used by observation, action, and UI
 * layers.  The implementation may render directly to a Surface or decode an
 * AVC stream into it; execution code only relies on this typed contract.
 *
 * Implementations must validate the session identity for every operation.  A
 * display ID can be reused by Android after close, so accepting a stale
 * [TaskDisplaySession] by display ID alone would allow an old action to reach
 * a new task.
 */
interface TaskDisplayBackend {
    /** All known display records, ordered newest first. */
    val displayRecords: StateFlow<List<TaskDisplayRecord>>

    /** Alias used by display-manager consumers. */
    val taskDisplays: StateFlow<List<TaskDisplayRecord>>
        get() = displayRecords

    /** Create and launch [packageName] on a display owned by [sessionKey]. */
    suspend fun create(
        sessionKey: String,
        packageName: String,
        spec: TaskDisplaySpec = TaskDisplaySpec(),
    ): TaskDisplaySession

    /** Return the current session for a coordinator key, if it still exists. */
    suspend fun current(sessionKey: String): TaskDisplaySession?

    /** Resolve one exact display ID and optionally claim it for a live run. */
    suspend fun resolveDisplay(
        displayId: Int,
        claimForSessionKey: String? = null,
        expectedDisplayRef: String? = null,
    ): TaskDisplayResolution = TaskDisplayResolution.Unavailable(
        code = "TASK_DISPLAY_UNAVAILABLE",
        message = "The task display backend is unavailable.",
    )

    /** Resolve the current display, or the only unexpired retained display. */
    suspend fun resolveDefaultDisplay(
        claimForSessionKey: String? = null,
    ): TaskDisplayResolution = TaskDisplayResolution.Unavailable(
        code = "TASK_DISPLAY_UNAVAILABLE",
        message = "No task display is available.",
    )

    /** All native-backed sessions, including retained terminal displays. */
    suspend fun activeDisplaySessions(): List<TaskDisplaySession> = emptyList()

    /** Whether [displayId] is currently claimed by the supplied coordinator run. */
    suspend fun isDisplayClaimedByRun(displayId: Int, runSessionKey: String): Boolean = false

    /** Capture the exact full-resolution frame and foreground app. */
    suspend fun capture(session: TaskDisplaySession): TaskDisplayCapture

    /**
     * Serialize a display-scoped command with capture, surface changes, and
     * close. Implementations use this lease to prevent a display ID from
     * being released/reused while an old input is still in flight.
     */
    suspend fun <T> withSession(
        session: TaskDisplaySession,
        block: suspend () -> T,
    ): T = block()

    /** Attach/detach the read-only live preview surface. */
    suspend fun attachLiveSurface(session: TaskDisplaySession, surface: Surface)
    suspend fun detachLiveSurface(session: TaskDisplaySession, surface: Surface)

    /** Restart decoding on the currently attached preview surface after an error. */
    suspend fun retryLiveSurface(sessionKey: String) = Unit

    /** Invalidate agent work immediately while leaving the display viewable. */
    fun cancel(sessionKey: String)

    /** Invalidate every display currently claimed by one coordinator run. */
    fun cancelForRun(sessionKey: String) = cancel(sessionKey)

    /** Mark a run terminal while retaining its display for the viewer. */
    suspend fun retain(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String? = null,
    ) = Unit

    /** Retain every display currently claimed by one coordinator run. */
    suspend fun retainForRun(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String? = null,
    ) = retain(sessionKey, status, error)

    /** Update a non-terminal lifecycle state while the run remains active. */
    suspend fun updateStatus(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String? = null,
    ) = Unit

    /** Update every display currently claimed by one coordinator run. */
    suspend fun updateStatusForRun(
        sessionKey: String,
        status: TaskDisplayStatus,
        error: String? = null,
    ) = updateStatus(sessionKey, status, error)

    /** Update the sanitized purpose shown in the live viewer footer. */
    fun updatePurpose(sessionKey: String, purpose: String) = Unit

    /** Update every display currently claimed by one coordinator run. */
    fun updatePurposeForRun(sessionKey: String, purpose: String) = updatePurpose(sessionKey, purpose)

    suspend fun close(session: TaskDisplaySession)

    /** Close by owner key, including a create that is still in flight. */
    suspend fun close(sessionKey: String)

    /** Explicitly end a display from the task-display manager. */
    suspend fun closeTaskDisplay(sessionKey: String) = close(sessionKey)

    /** Explicitly end one display selected by logical ID and generation ref. */
    suspend fun closeTaskDisplay(
        displayId: Int,
        expectedDisplayRef: String? = null,
    ): TaskDisplayCloseResult = TaskDisplayCloseResult.Rejected(
        code = "DISPLAY_UNAVAILABLE",
        message = "The task display backend is unavailable.",
    )
}

val TaskDisplayStatus.isTerminal: Boolean
    get() = this == TaskDisplayStatus.COMPLETED ||
        this == TaskDisplayStatus.FAILED ||
        this == TaskDisplayStatus.STOPPED ||
        this == TaskDisplayStatus.ENDED ||
        this == TaskDisplayStatus.EXPIRED
