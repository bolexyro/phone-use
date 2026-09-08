package com.phonecontrol.assistant.execution

import android.view.Surface

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
) {
    init {
        require(sessionKey.isNotBlank()) { "Task display session key must not be blank." }
        require(taskId.isNotBlank()) { "Task display task ID must not be blank." }
        require(displayId > 0) { "Task display ID must be greater than the default display." }
    }
}

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
    /** Create and launch [packageName] on a display owned by [sessionKey]. */
    suspend fun create(
        sessionKey: String,
        packageName: String,
        spec: TaskDisplaySpec = TaskDisplaySpec(),
    ): TaskDisplaySession

    /** Return the current session for a coordinator key, if it still exists. */
    suspend fun current(sessionKey: String): TaskDisplaySession?

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

    /** Invalidate work immediately, then release resources asynchronously. */
    fun cancel(sessionKey: String)
    suspend fun close(session: TaskDisplaySession)

    /** Close by owner key, including a create that is still in flight. */
    suspend fun close(sessionKey: String)
}
