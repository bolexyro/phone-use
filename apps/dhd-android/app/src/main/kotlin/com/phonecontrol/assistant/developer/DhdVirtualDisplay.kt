package com.phonecontrol.assistant.developer

import android.content.Context
import android.view.Surface
import com.phonecontrol.assistant.execution.PhoneProcessResult
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val DHD_SESSION_KEY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}")
private val DHD_PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")

/** Fixed geometry for one agent-owned virtual display. */
data class DhdVirtualDisplaySpec(
    val width: Int = 720,
    val height: Int = 1560,
    // This is the physical density of the virtual display. Keep it at 420 so
    // the encoded frame and coordinate space stay stable.
    val densityDpi: Int = 420,
    val frameRate: Int = 30,
    val bitRate: Int = 2_000_000,
    // Apps on the task display receive this density through a per-display
    // window-manager override, giving them a wider dp viewport while
    // preserving the base 720x1560 pixel buffer.
    val appDensityDpi: Int = 320,
) {
    init {
        require(width in 320..2_160) { "Virtual display width is outside the supported range." }
        require(height in 320..3_840) { "Virtual display height is outside the supported range." }
        require(densityDpi in 120..640) { "Virtual display density is outside the supported range." }
        require(appDensityDpi in 120..640) { "App density is outside the supported range." }
        require(frameRate in 1..60) { "Virtual display frame rate is outside the supported range." }
        require(bitRate in 128_000..20_000_000) { "Virtual display bit rate is outside the supported range." }
    }
}

/** The daemon-owned logical display and its private, authenticated live stream. */
data class DhdVirtualDisplaySession(
    val sessionKey: String,
    val packageName: String,
    val displayId: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val frameRate: Int,
    val bitRate: Int,
    val streamPort: Int,
    val streamToken: String,
    val codecMime: String = DhdVirtualDisplayProtocol.CODEC_AVC,
    val appDensityDpi: Int = densityDpi,
) {
    init {
        require(sessionKey.isNotBlank()) { "Virtual display session key must not be blank." }
        require(packageName.matches(DHD_PACKAGE_PATTERN)) { "Virtual display package name is invalid." }
        require(displayId > 0) { "Virtual display sessions may never target the default display." }
        require(width > 0 && height > 0 && densityDpi > 0 && appDensityDpi > 0) {
            "Virtual display geometry is invalid."
        }
        require(streamPort in 1024..65535) { "Virtual display stream port is invalid." }
        require(streamToken.isNotBlank()) { "Virtual display stream token must not be blank." }
        require(codecMime == DhdVirtualDisplayProtocol.CODEC_AVC) {
            "Unsupported virtual display codec: $codecMime"
        }
    }

    val spec: DhdVirtualDisplaySpec
        get() = DhdVirtualDisplaySpec(width, height, densityDpi, frameRate, bitRate, appDensityDpi)
}

data class DhdVirtualDisplayCapture(
    val session: DhdVirtualDisplaySession,
    val png: ByteArray,
)

sealed interface DhdVirtualDisplayResult {
    data class Created(val session: DhdVirtualDisplaySession) : DhdVirtualDisplayResult
    data class Failed(val code: Code, val message: String) : DhdVirtualDisplayResult

    enum class Code {
        NOT_READY,
        INVALID_SESSION,
        DISPLAY_UNAVAILABLE,
        COMMAND_FAILED,
        STREAM_UNAVAILABLE,
        STOPPED,
    }
}

/**
 * App-process facade over DHD's shell-UID display daemon.
 *
 * The facade deliberately never infers display 0. Every operation carries the
 * session key and validates the exact display id returned by the daemon.
 */
class DhdVirtualDisplayManager(
    context: Context,
    private val controller: DhdAdbController,
) {
    private val appContext = context.applicationContext
    private val stateMutex = Mutex()
    private val sessions = LinkedHashMap<String, DhdVirtualDisplaySession>()
    /** Cancellation tombstones are process-local and intentionally retained. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val operationLocks = ConcurrentHashMap<String, Mutex>()
    private val daemonResetMutex = Mutex()
    private var daemonResetComplete = false

    suspend fun create(
        sessionKey: String,
        packageName: String,
        spec: DhdVirtualDisplaySpec = DhdVirtualDisplaySpec(),
    ): DhdVirtualDisplayResult {
        if (!DHD_SESSION_KEY_PATTERN.matches(sessionKey)) {
            return DhdVirtualDisplayResult.Failed(
                DhdVirtualDisplayResult.Code.INVALID_SESSION,
                "Virtual display session key is invalid.",
            )
        }
        if (!DHD_PACKAGE_PATTERN.matches(packageName)) {
            return DhdVirtualDisplayResult.Failed(
                DhdVirtualDisplayResult.Code.INVALID_SESSION,
                "Virtual display package name is invalid.",
            )
        }

        val resetFailure = try {
            ensureDaemonReset()
            null
        } catch (error: Throwable) {
            error
        }
        if (resetFailure != null) {
            return DhdVirtualDisplayResult.Failed(
                DhdVirtualDisplayResult.Code.NOT_READY,
                "The native display service could not clear stale sessions: " +
                    (resetFailure.message ?: resetFailure::class.java.simpleName),
            )
        }

        val operationLock = operationLocks.getOrPut(sessionKey) { Mutex() }
        return operationLock.withLock {
            val validation = stateMutex.withLock {
                when {
                    sessionKey in cancelled -> DhdVirtualDisplayResult.Failed(
                        DhdVirtualDisplayResult.Code.STOPPED,
                        "The virtual display session was stopped before creation.",
                    )
                    sessions.containsKey(sessionKey) -> DhdVirtualDisplayResult.Failed(
                        DhdVirtualDisplayResult.Code.INVALID_SESSION,
                        "A virtual display session with this key is already active.",
                    )
                    else -> null
                }
            }
            if (validation != null) return@withLock validation

            val result = try {
                controller.execute(
                    command = listOf(
                        DhdVirtualDisplayProtocol.COMMAND,
                        DhdVirtualDisplayProtocol.CREATE,
                        sessionKey,
                        packageName,
                        spec.width.toString(),
                        spec.height.toString(),
                        spec.densityDpi.toString(),
                        spec.frameRate.toString(),
                        spec.bitRate.toString(),
                        spec.appDensityDpi.toString(),
                    ),
                )
            } catch (cancelledError: CancellationException) {
                if (cancelled.contains(sessionKey)) {
                    runCatching {
                        controller.execute(
                            listOf(DhdVirtualDisplayProtocol.COMMAND, DhdVirtualDisplayProtocol.CLOSE, sessionKey),
                        )
                    }
                }
                throw cancelledError
            }
            val session = parseCreatedSession(result, sessionKey, packageName)
            if (session == null) {
                val stopped = cancelled.contains(sessionKey)
                if (stopped) {
                    // Creation may have crossed the stop request. Always clean
                    // up by key so a late daemon response cannot orphan a display.
                    controller.execute(
                        listOf(DhdVirtualDisplayProtocol.COMMAND, DhdVirtualDisplayProtocol.CLOSE, sessionKey),
                    )
                    return@withLock DhdVirtualDisplayResult.Failed(
                        DhdVirtualDisplayResult.Code.STOPPED,
                        "The virtual display session was stopped while it was starting.",
                    )
                }
                return@withLock DhdVirtualDisplayResult.Failed(
                    failureCode(result),
                    failureMessage(result, "The virtual display could not be created."),
                )
            }

            val stopAfterCreate = stateMutex.withLock {
                if (sessionKey in cancelled) {
                    true
                } else {
                    sessions[sessionKey] = session
                    false
                }
            }
            if (stopAfterCreate) {
                controller.execute(
                    listOf(DhdVirtualDisplayProtocol.COMMAND, DhdVirtualDisplayProtocol.CLOSE, sessionKey),
                )
                DhdVirtualDisplayResult.Failed(
                    DhdVirtualDisplayResult.Code.STOPPED,
                    "The virtual display session was stopped while it was starting.",
                )
            } else {
                DhdVirtualDisplayResult.Created(session)
            }
        }
    }

    suspend fun attachLiveSurface(
        session: DhdVirtualDisplaySession,
        surface: Surface,
    ): DhdLivePreviewHandle {
        require(surface.isValid) { "The live preview surface is invalid." }
        require(isCurrentSession(session)) { "The virtual display session is not active." }
        val result = controller.execute(
            listOf(
                DhdVirtualDisplayProtocol.COMMAND,
                DhdVirtualDisplayProtocol.ATTACH,
                session.sessionKey,
            ),
        )
        if (result.exitCode != 0 || result.timedOut) {
            throw IOException(failureMessage(result, "The virtual display stream could not be attached."))
        }
        return DhdLivePreviewHandle(session, surface)
    }

    suspend fun detachLiveSurface(session: DhdVirtualDisplaySession) {
        if (!isCurrentSession(session)) return
        controller.execute(
            listOf(
                DhdVirtualDisplayProtocol.COMMAND,
                DhdVirtualDisplayProtocol.DETACH,
                session.sessionKey,
            ),
        )
    }

    suspend fun capture(session: DhdVirtualDisplaySession): DhdVirtualDisplayCapture {
        require(isCurrentSession(session)) { "The virtual display session is not active." }
        val result = controller.execute(
            command = listOf(
                DhdVirtualDisplayProtocol.COMMAND,
                DhdVirtualDisplayProtocol.CAPTURE,
                session.sessionKey,
            ),
            binaryOutput = true,
        )
        if (result.exitCode != 0 || result.timedOut || result.stdout.isEmpty()) {
            throw IOException(failureMessage(result, "The virtual display could not be captured."))
        }
        return DhdVirtualDisplayCapture(session, result.stdout.copyOf())
    }

    suspend fun close(sessionKey: String) {
        if (!DHD_SESSION_KEY_PATTERN.matches(sessionKey)) return
        // Set the tombstone before waiting for an in-flight create. The late
        // create response is then closed by key and can never be published.
        cancelled += sessionKey
        val operationLock = operationLocks.getOrPut(sessionKey) { Mutex() }
        operationLock.withLock {
            stateMutex.withLock {
                sessions.remove(sessionKey)
            }
            runCatching {
                controller.execute(
                    listOf(DhdVirtualDisplayProtocol.COMMAND, DhdVirtualDisplayProtocol.CLOSE, sessionKey),
                )
            }
        }
    }

    /** Synchronous cancellation hook for a stop button before coroutine cleanup. */
    fun cancel(sessionKey: String) {
        if (DHD_SESSION_KEY_PATTERN.matches(sessionKey)) cancelled += sessionKey
    }

    suspend fun closeAll() {
        val keys = stateMutex.withLock {
            val current = sessions.keys.toList()
            sessions.clear()
            current
        }
        cancelled += keys
        runCatching {
            controller.execute(
                listOf(DhdVirtualDisplayProtocol.COMMAND, DhdVirtualDisplayProtocol.CLOSE_ALL),
            )
        }
    }

    /**
     * The daemon outlives the app process so it can keep a display alive while
     * the UI is recreated. On a fresh manager, however, any daemon sessions
     * belong to the crashed/stopped app and must be reclaimed before a new
     * task is created. The reset is performed once per manager instance.
     */
    private suspend fun ensureDaemonReset() {
        daemonResetMutex.withLock {
            if (daemonResetComplete) return
            val result = controller.execute(
                listOf(DhdVirtualDisplayProtocol.COMMAND, DhdVirtualDisplayProtocol.CLOSE_ALL),
            )
            if (result.timedOut || result.exitCode != 0) {
                throw IOException(
                    result.stderr.ifBlank { "exit ${result.exitCode}" },
                )
            }
            daemonResetComplete = true
        }
    }

    private suspend fun isCurrentSession(session: DhdVirtualDisplaySession): Boolean = stateMutex.withLock {
        sessions[session.sessionKey] == session && session.displayId > 0
    }

    private fun parseCreatedSession(
        result: PhoneProcessResult,
        expectedSessionKey: String,
        expectedPackageName: String,
    ): DhdVirtualDisplaySession? {
        if (result.exitCode != 0 || result.timedOut || result.stdout.isEmpty()) return null
        return runCatching {
            val json = org.json.JSONObject(String(result.stdout, Charsets.UTF_8))
            require(json.optString("type") == DhdVirtualDisplayProtocol.CREATED_TYPE)
            val session = DhdVirtualDisplaySession(
                sessionKey = json.getString("sessionKey"),
                packageName = json.getString("packageName"),
                displayId = json.getInt("displayId"),
                width = json.getInt("width"),
                height = json.getInt("height"),
                densityDpi = json.getInt("densityDpi"),
                frameRate = json.getInt("frameRate"),
                bitRate = json.getInt("bitRate"),
                streamPort = json.getInt("streamPort"),
                streamToken = json.getString("streamToken"),
                codecMime = json.optString("codecMime", DhdVirtualDisplayProtocol.CODEC_AVC),
                appDensityDpi = json.optInt("appDensityDpi", json.getInt("densityDpi")),
            )
            require(session.sessionKey == expectedSessionKey)
            require(session.packageName == expectedPackageName)
            session
        }.getOrNull()
    }

    private fun failureCode(result: PhoneProcessResult): DhdVirtualDisplayResult.Code = when {
        result.timedOut -> DhdVirtualDisplayResult.Code.COMMAND_FAILED
        result.stderr.contains("not ready", ignoreCase = true) -> DhdVirtualDisplayResult.Code.NOT_READY
        result.stderr.contains("display", ignoreCase = true) -> DhdVirtualDisplayResult.Code.DISPLAY_UNAVAILABLE
        else -> DhdVirtualDisplayResult.Code.COMMAND_FAILED
    }

    private fun failureMessage(result: PhoneProcessResult, fallback: String): String =
        result.stderr.ifBlank { fallback }

    private companion object {
    }
}
