package com.phonecontrol.assistant.bridge

import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.session.ActionExecutionResult
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.shizuku.ObservationCaptureResult
import com.phonecontrol.assistant.shizuku.ShizukuObservationProvider
import com.phonecontrol.assistant.shizuku.TransportResult
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Localhost-only NDJSON bridge used by the development desktop companion.
 *
 * The desktop reaches this socket through `adb forward`, so no phone port is
 * exposed on the LAN. It accepts one deliberately narrow demo request and
 * runs the same phone-owned policy/observation/transport path as a future
 * Codex action stream. This is not a production network protocol.
 */
class DevBridgeServer(
    private val coordinator: SessionCoordinator,
    private val observationProvider: ShizukuObservationProvider,
    private val port: Int = DEFAULT_PORT,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                val socket = ServerSocket(
                    port,
                    1,
                    InetAddress.getByName(LOOPBACK_HOST),
                )
                serverSocket = socket
                while (isActive) {
                    val client = socket.accept()
                    launch { handleClient(client) }
                }
            } catch (_: java.net.SocketException) {
                // Closing the server socket is the normal shutdown path.
            } catch (error: Throwable) {
                android.util.Log.e(TAG, "Development bridge stopped", error)
            }
        }
    }

    fun stop() {
        started = false
        serverSocket?.close()
        serverSocket = null
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
            val line = reader.readLine()
            if (line == null) {
                write(writer, errorResponse(null, "The bridge received an empty request."))
                return
            }
            if (line.length > MAX_REQUEST_CHARS) {
                write(writer, errorResponse(null, "The bridge request is too large."))
                return
            }
            val request = try {
                parseRequest(JSONObject(line))
            } catch (error: IllegalArgumentException) {
                write(writer, errorResponse(null, error.message ?: "Invalid demo request."))
                return
            } catch (error: JSONException) {
                write(writer, errorResponse(null, "The bridge request must be valid JSON."))
                return
            }

            write(
                writer,
                JSONObject()
                    .put("type", "accepted")
                    .put("requestId", request.requestId)
                    .put("message", "Demo sequence accepted by the phone."),
            )
            try {
                runDemo(request, writer)
            } catch (error: Throwable) {
                val message = error.message ?: error::class.java.simpleName
                android.util.Log.e(TAG, "Demo request failed", error)
                coordinator.stop("Demo failed unexpectedly: $message")
                write(writer, errorResponse(request.requestId, "The phone bridge failed: $message"))
            }
        }
    }

    private suspend fun runDemo(request: DemoRequest, writer: BufferedWriter) {
        val startedSession = coordinator.start("Desktop Codex demo: ${request.purpose}")
        if (!startedSession) {
            write(writer, errorResponse(request.requestId, "The phone already has an active session."))
            return
        }

        val beforeOpen = observationProvider.capture()
        val preOpenSnapshot = when (beforeOpen) {
            is ObservationCaptureResult.Failed -> {
                failSession(writer, request, beforeOpen.message)
                return
            }

            is ObservationCaptureResult.Succeeded -> beforeOpen.snapshot
        }
        val open = OpenAppAction(
            packageName = request.packageName,
            metadata = ActionMetadata(
                purpose = "Opening ${request.packageName}",
                observationId = preOpenSnapshot.id,
                targetDescription = request.packageName,
            ),
        )
        val openResult = coordinator.executeAction(open, preOpenSnapshot)
        writeActionResult(writer, request.requestId, "open_app", openResult)
        if (!openResult.isSuccessful()) {
            failSession(writer, request, openResult.failureMessage())
            return
        }

        delay(OPEN_SETTLE_DELAY_MS)
        val afterOpen = captureWithRetry(request.packageName, request.guardRegions)
        val tapSnapshot = when (afterOpen) {
            is ObservationCaptureResult.Failed -> {
                failSession(writer, request, afterOpen.message)
                return
            }

            is ObservationCaptureResult.Succeeded -> afterOpen.snapshot
        }
        val tap = TapAction(
            x = request.x,
            y = request.y,
            metadata = ActionMetadata(
                purpose = request.purpose,
                observationId = tapSnapshot.id,
                targetDescription = request.targetDescription,
                guardRegions = request.guardRegions,
            ),
        )
        val tapResult = coordinator.executeAction(tap, tapSnapshot)
        writeActionResult(writer, request.requestId, "tap", tapResult)
        if (!tapResult.isSuccessful()) {
            failSession(writer, request, tapResult.failureMessage())
            return
        }

        delay(POST_ACTION_SETTLE_DELAY_MS)
        val afterTap = observationProvider.capture(expectedPackageName = request.packageName)
        when (afterTap) {
            is ObservationCaptureResult.Failed -> {
                failSession(writer, request, "Tap completed, but the post-action observation failed: ${afterTap.message}")
                return
            }

            is ObservationCaptureResult.Succeeded -> {
                coordinator.complete("Demo completed; the phone returned a fresh observation.")
                write(
                    writer,
                    JSONObject()
                        .put("type", "completed")
                        .put("requestId", request.requestId)
                        .put("message", "Opened ${request.packageName} and tapped ${request.x},${request.y}.")
                        .put("observationId", afterTap.snapshot.id)
                        .put("width", afterTap.snapshot.width)
                        .put("height", afterTap.snapshot.height),
                )
            }
        }
    }

    private suspend fun captureWithRetry(
        expectedPackageName: String,
        guardRegions: List<GuardRegion>,
    ): ObservationCaptureResult {
        var last: ObservationCaptureResult = ObservationCaptureResult.Failed("No capture attempted.")
        repeat(CAPTURE_ATTEMPTS) {
            last = observationProvider.capture(expectedPackageName, guardRegions)
            if (last is ObservationCaptureResult.Succeeded) return last
            delay(CAPTURE_RETRY_DELAY_MS)
        }
        return last
    }

    private fun failSession(writer: BufferedWriter, request: DemoRequest, message: String) {
        coordinator.stop("Demo stopped: $message")
        write(writer, errorResponse(request.requestId, message))
    }

    private fun writeActionResult(
        writer: BufferedWriter,
        requestId: String,
        action: String,
        result: ActionExecutionResult,
    ) {
        val successful = result.isSuccessful()
        val response = JSONObject()
            .put("type", "action_result")
            .put("requestId", requestId)
            .put("action", action)
            .put("ok", successful)
        when (result) {
            is ActionExecutionResult.TransportFinished -> {
                when (val transportResult = result.result) {
                    is TransportResult.Succeeded -> response.put("message", transportResult.message)
                    is TransportResult.Rejected -> response
                        .put("code", transportResult.code.name)
                        .put("message", transportResult.message)
                    is TransportResult.Unsupported -> response.put("message", transportResult.message)
                }
            }

            is ActionExecutionResult.ConfirmationRequired -> response
                .put("code", "CONFIRMATION_REQUIRED")
                .put("message", result.message)
            is ActionExecutionResult.PolicyRejected -> response
                .put("code", "POLICY_REJECTED")
                .put("message", result.message)
            ActionExecutionResult.SessionNotRunning -> response
                .put("code", "SESSION_NOT_RUNNING")
                .put("message", "The phone session is no longer running.")
        }
        write(writer, response)
    }

    private fun parseRequest(json: JSONObject): DemoRequest {
        require(json.optString("type") == "demo_run") {
            "Only type=demo_run is accepted by the development bridge."
        }
        val packageName = json.optString("packageName")
        require(PACKAGE_PATTERN.matches(packageName)) { "packageName is not a valid Android package name." }
        require(json.has("x") && json.has("y")) { "x and y coordinates are required." }
        val x = json.getInt("x")
        val y = json.getInt("y")
        require(x >= 0 && y >= 0) { "x and y must be non-negative." }
        val purpose = json.optString("purpose", "Developer-selected demo coordinate")
        require(purpose.isNotBlank() && purpose.length <= MAX_TEXT_CHARS) { "purpose is invalid." }
        val targetDescription = json.optString("targetDescription", "developer-selected coordinate")
        require(targetDescription.isNotBlank() && targetDescription.length <= MAX_TEXT_CHARS) {
            "targetDescription is invalid."
        }
        return DemoRequest(
            requestId = json.optString("requestId").ifBlank { UUID.randomUUID().toString() },
            packageName = packageName,
            x = x,
            y = y,
            purpose = purpose,
            targetDescription = targetDescription,
            guardRegions = parseGuardRegions(json.optJSONArray("guardRegions")),
        )
    }

    private fun parseGuardRegions(array: JSONArray?): List<GuardRegion> {
        if (array == null) return emptyList()
        require(array.length() <= MAX_GUARD_REGIONS) { "At most $MAX_GUARD_REGIONS guard regions are supported." }
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val region = array.getJSONObject(index)
                add(
                    GuardRegion(
                        left = region.getInt("left"),
                        top = region.getInt("top"),
                        right = region.getInt("right"),
                        bottom = region.getInt("bottom"),
                    ),
                )
            }
        }
    }

    private fun errorResponse(requestId: String?, message: String): JSONObject = JSONObject()
        .put("type", "error")
        .put("requestId", requestId ?: JSONObject.NULL)
        .put("ok", false)
        .put("message", message)

    private fun write(writer: BufferedWriter, json: JSONObject) {
        writer.write(json.toString())
        writer.newLine()
        writer.flush()
    }

    private data class DemoRequest(
        val requestId: String,
        val packageName: String,
        val x: Int,
        val y: Int,
        val purpose: String,
        val targetDescription: String,
        val guardRegions: List<GuardRegion>,
    )

    private companion object {
        const val TAG = "PhoneControlBridge"
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8765
        const val MAX_REQUEST_CHARS = 16_384
        const val MAX_TEXT_CHARS = 240
        const val MAX_GUARD_REGIONS = 8
        const val OPEN_SETTLE_DELAY_MS = 750L
        const val POST_ACTION_SETTLE_DELAY_MS = 350L
        const val CAPTURE_ATTEMPTS = 5
        const val CAPTURE_RETRY_DELAY_MS = 250L
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
    }
}

private fun ActionExecutionResult.isSuccessful(): Boolean = this is ActionExecutionResult.TransportFinished &&
    this.result is TransportResult.Succeeded

private fun ActionExecutionResult.failureMessage(): String = when (this) {
    is ActionExecutionResult.TransportFinished -> when (val result = result) {
        is TransportResult.Rejected -> result.message
        is TransportResult.Unsupported -> result.message
        is TransportResult.Succeeded -> result.message
    }
    is ActionExecutionResult.ConfirmationRequired -> message
    is ActionExecutionResult.PolicyRejected -> message
    ActionExecutionResult.SessionNotRunning -> "The phone session is no longer running."
}
