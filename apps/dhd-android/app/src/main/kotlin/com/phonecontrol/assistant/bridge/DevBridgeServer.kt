package com.phonecontrol.assistant.bridge

import android.util.Base64
import android.content.Context
import android.content.Intent
import com.phonecontrol.assistant.apps.InstalledAppsRepository
import com.phonecontrol.assistant.apps.InstalledUserApp
import com.phonecontrol.assistant.domain.ActionMetadata
import com.phonecontrol.assistant.domain.BackAction
import com.phonecontrol.assistant.domain.GuardRegion
import com.phonecontrol.assistant.domain.KeypressAction
import com.phonecontrol.assistant.domain.KeypressKey
import com.phonecontrol.assistant.domain.OpenAppAction
import com.phonecontrol.assistant.domain.ObservationSnapshot
import com.phonecontrol.assistant.domain.PhoneAction
import com.phonecontrol.assistant.domain.ReasoningEffort
import com.phonecontrol.assistant.domain.ScrollAction
import com.phonecontrol.assistant.domain.ScrollAmount
import com.phonecontrol.assistant.domain.ScrollDirection
import com.phonecontrol.assistant.domain.SwipeAction
import com.phonecontrol.assistant.domain.TapAction
import com.phonecontrol.assistant.domain.TypeAction
import com.phonecontrol.assistant.domain.WaitAction
import com.phonecontrol.assistant.session.ActionExecutionResult
import com.phonecontrol.assistant.session.AssistantForegroundService
import com.phonecontrol.assistant.session.SessionCoordinator
import com.phonecontrol.assistant.session.SessionState
import com.phonecontrol.assistant.shizuku.ObservationCaptureResult
import com.phonecontrol.assistant.shizuku.ShizukuObservationProvider
import com.phonecontrol.assistant.shizuku.TransportResult
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Authenticated LAN NDJSON bridge used by the development desktop companion.
 *
 * The desktop may still reach this socket through `adb forward` for local
 * development, but the normal path is a same-Wi-Fi connection to the phone's
 * LAN address. It accepts the typed development requests used by the local
 * Codex MCP adapter and runs the phone-owned policy/observation/transport
 * path. The bearer token is a development pairing boundary, not a substitute
 * for TLS or a production network protocol.
 */
class DevBridgeServer(
    private val context: Context,
    private val coordinator: SessionCoordinator,
    private val observationProvider: ShizukuObservationProvider,
    private val allowedPackagesProvider: () -> Set<String>,
    private val port: Int = DEFAULT_PORT,
    private val fullAccessProvider: () -> Boolean = { false },
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val installedAppsRepository = InstalledAppsRepository(context)
    val authenticationToken: String = preferences.getString(KEY_AUTH_TOKEN, null)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: UUID.randomUUID().toString().replace("-", "").also { token ->
            preferences.edit().putString(KEY_AUTH_TOKEN, token).apply()
        }
    val deviceId: String = preferences.getString(KEY_DEVICE_ID, null)
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: UUID.randomUUID().toString().also { id ->
            preferences.edit().putString(KEY_DEVICE_ID, id).apply()
        }
    val listeningPort: Int = port
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var pairingSocket: DatagramSocket? = null
    @Volatile private var started = false
    @Volatile private var lastCompanionSeenEpochMs: Long = 0L
    private val _companionConnected = MutableStateFlow(false)
    private val pairingCodeLock = Any()
    @Volatile private var pairingCodeValue: String = loadOrCreatePairingCode()
    private val codexWarmupRequested = AtomicBoolean(false)
    private val observations = Collections.synchronizedMap(
        object : LinkedHashMap<String, ObservationSnapshot>(MAX_OBSERVATIONS + 1, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ObservationSnapshot>?): Boolean =
                size > MAX_OBSERVATIONS
        },
    )

    val pairingCode: String
        get() = pairingCodeValue

    val companionConnected: StateFlow<Boolean> = _companionConnected.asStateFlow()

    fun refreshPairingCode(): String = synchronized(pairingCodeLock) {
        rotatePairingCodeLocked()
    }

    private fun loadOrCreatePairingCode(): String {
        val stored = preferences.getString(KEY_PAIRING_CODE, null)?.trim()?.uppercase()
        return if (stored != null && isValidPairingCode(stored)) {
            stored
        } else {
            val next = generatePairingCode()
            preferences.edit()
                .putString(KEY_PAIRING_CODE, next)
                .apply()
            next
        }
    }

    private fun rotatePairingCodeLocked(): String {
        val next = generatePairingCode()
        pairingCodeValue = next
        preferences.edit()
            .putString(KEY_PAIRING_CODE, next)
            .apply()
        return next
    }

    private fun generatePairingCode(): String = buildString(PAIRING_CODE_LENGTH) {
        repeat(PAIRING_CODE_LENGTH) {
            append(PAIRING_CODE_ALPHABET[secureRandom.nextInt(PAIRING_CODE_ALPHABET.length)])
        }
    }

    private fun isValidPairingCode(value: String): Boolean = value.length == PAIRING_CODE_LENGTH &&
        value.all { character -> character in PAIRING_CODE_ALPHABET }

    fun start() {
        if (started) return
        started = true
        scope.launch {
            try {
                val socket = ServerSocket(
                    port,
                    1,
                    InetAddress.getByName(LAN_BIND_HOST),
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
        scope.launch { runPairingDiscovery() }
        scope.launch { monitorCompanionPresence() }
    }

    fun stop() {
        started = false
        serverSocket?.close()
        serverSocket = null
        pairingSocket?.close()
        pairingSocket = null
        lastCompanionSeenEpochMs = 0L
        _companionConnected.value = false
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun monitorCompanionPresence() {
        while (currentCoroutineContext().isActive) {
            val lastSeen = lastCompanionSeenEpochMs
            val connected = lastSeen > 0L &&
                System.currentTimeMillis() - lastSeen <= COMPANION_PRESENCE_TIMEOUT_MS
            if (_companionConnected.value != connected) {
                _companionConnected.value = connected
            }
            delay(COMPANION_PRESENCE_CHECK_INTERVAL_MS)
        }
    }

    private fun markCompanionSeen() {
        lastCompanionSeenEpochMs = System.currentTimeMillis()
        _companionConnected.value = true
    }

    private fun runPairingDiscovery() {
        try {
            val socket = DatagramSocket(PAIRING_DISCOVERY_PORT, InetAddress.getByName(LAN_BIND_HOST))
            pairingSocket = socket
            val buffer = ByteArray(MAX_REQUEST_CHARS)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                handlePairingRequest(socket, packet)
            }
        } catch (_: SocketException) {
            // Closing the pairing socket is the normal shutdown path.
        } catch (error: Throwable) {
            android.util.Log.w(TAG, "Pairing discovery stopped", error)
        } finally {
            pairingSocket = null
        }
    }

    private fun handlePairingRequest(socket: DatagramSocket, packet: DatagramPacket) {
        val request = try {
            JSONObject(
                String(packet.data, packet.offset, packet.length, Charsets.UTF_8),
            )
        } catch (_: Throwable) {
            return
        }
        if (request.optString("type") != "dhd_pair_request" ||
            request.optInt("version", -1) != PAIRING_PROTOCOL_VERSION
        ) {
            return
        }
        val requestId = request.optString("requestId").trim()
        val candidateCode = request.optString("code").trim().uppercase()
        if (requestId.isBlank() || candidateCode != pairingCode) return

        val response = JSONObject()
            .put("type", "dhd_pair_offer")
            .put("version", PAIRING_PROTOCOL_VERSION)
            .put("requestId", requestId)
            .put("deviceId", deviceId)
            .put("port", listeningPort)
            .put("token", authenticationToken)
        val addresses = JSONArray()
        lanIpv4Addresses().forEach(addresses::put)
        response.put("addresses", addresses)

        val bytes = response.toString().toByteArray(Charsets.UTF_8)
        socket.send(DatagramPacket(bytes, bytes.size, packet.address, packet.port))
    }

    /**
     * Record that the DHD UI became visible. The desktop companion consumes
     * this one-shot bit on its next pending-request poll and prewarms Codex in
     * the background, without making the Android app wait for the desktop.
     */
    fun requestCodexWarmup() {
        codexWarmupRequested.set(true)
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
            val json = try {
                JSONObject(line)
            } catch (error: IllegalArgumentException) {
                write(writer, errorResponse(null, error.message ?: "Invalid bridge request."))
                return
            } catch (error: JSONException) {
                write(writer, errorResponse(null, "The bridge request must be valid JSON."))
                return
            }
            val requestId = json.optString("requestId").ifBlank { UUID.randomUUID().toString() }

            if (!isAuthorized(socket, json)) {
                write(
                    writer,
                    errorResponse(requestId, "The phone bridge rejected this network connection. Pair the desktop companion in DHD settings.")
                        .put("code", "AUTH_REQUIRED"),
                )
                return
            }

            write(
                writer,
                JSONObject()
                    .put("type", "accepted")
                    .put("requestId", requestId)
                    .put("message", "${json.optString("type", "bridge")} accepted by the phone."),
            )
            try {
                when (json.optString("type")) {
                    "demo_run" -> runDemo(parseRequest(json), writer)
                    "start_session" -> startSession(requestId, json, writer)
                    "status" -> status(requestId, writer)
                    "pending_request" -> pendingRequest(requestId, writer)
                    "claim_request" -> claimRequest(requestId, json, writer)
                    "pending_steer" -> pendingSteer(requestId, json, writer)
                    "claim_steer" -> claimSteer(requestId, json, writer)
                    "release_steer" -> releaseSteer(requestId, json, writer)
                    "complete_steer" -> completeSteer(requestId, json, writer)
                    "bind_codex_thread" -> bindCodexThread(requestId, json, writer)
                    "release_request" -> releaseRequest(requestId, json, writer)
                    "complete_session" -> completeSession(requestId, json, writer)
                    "fail_session" -> failSession(requestId, json, writer)
                    "allowed_apps" -> allowedApps(requestId, json, writer)
                    "browse_apps" -> browseApps(requestId, json, writer)
                    "observe" -> observe(requestId, json, writer)
                    "execute_action" -> executeAction(requestId, json, writer)
                    "request_attention" -> requestAttention(requestId, json, writer)
                    "stop_session" -> stopSession(requestId, json, writer)
                    else -> write(writer, errorResponse(requestId, "Unsupported bridge request type."))
                }
            } catch (error: Throwable) {
                val message = error.message ?: error::class.java.simpleName
                android.util.Log.e(TAG, "Bridge request failed", error)
                write(writer, errorResponse(requestId, "The phone bridge failed: $message"))
            }
        }
    }

    private fun startSession(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val request = json.optString("request").trim()
        require(request.isNotEmpty() && request.length <= MAX_REQUEST_CHARS) {
            "request must be 1-$MAX_REQUEST_CHARS characters."
        }
        val conversationId = json.optString("conversationId").trim().ifBlank { null }
        val reasoningEffort = json.optString("reasoningEffort")
            .trim()
            .ifBlank { ReasoningEffort.default.codexValue }
        if (!coordinator.start(request, conversationId, reasoningEffort)) {
            write(writer, errorResponse(requestId, "The phone already has an active session."))
            return
        }
        val state = coordinator.state.value
        val sessionId = (state as? SessionState.Running)?.sessionId
        require(sessionId != null) { "The phone session did not enter the running state." }
        // A desktop-originated session must get the same persistent foreground
        // notification as a session started from the Compose Run button.
        runCatching {
            val serviceIntent = Intent(context, AssistantForegroundService::class.java)
                .setAction(AssistantForegroundService.ACTION_START)
                .putExtra(AssistantForegroundService.EXTRA_REQUEST, request)
                .putExtra(AssistantForegroundService.EXTRA_REASONING_EFFORT, reasoningEffort)
            if (!conversationId.isNullOrBlank()) {
                serviceIntent.putExtra(AssistantForegroundService.EXTRA_CONVERSATION_ID, conversationId)
            }
            androidx.core.content.ContextCompat.startForegroundService(
                context,
                serviceIntent,
            )
        }.onFailure { error ->
            android.util.Log.w(TAG, "Could not start the foreground notification for the bridge session", error)
        }
        write(
            writer,
            JSONObject()
                .put("type", "started")
                .put("requestId", requestId)
                .put("ok", true)
                .put("sessionId", sessionId)
                .put("conversationId", (state as? SessionState.Running)?.conversationId ?: JSONObject.NULL)
                .put("reasoningEffort", (state as? SessionState.Running)?.reasoningEffort ?: ReasoningEffort.default.codexValue)
                .put("message", "Phone assistant session started."),
        )
    }

    private fun status(
        requestId: String,
        writer: BufferedWriter,
    ) {
        val state = coordinator.state.value
        val response = JSONObject()
            .put("type", "status")
            .put("requestId", requestId)
            .put("ok", true)
            .put("state", stateName(state))
            .put("active", state is SessionState.Running || state is SessionState.Paused)
            .put("companionConnected", companionConnected.value)
        when (state) {
            is SessionState.Running -> response
                .put("sessionId", state.sessionId)
                .put("conversationId", state.conversationId ?: JSONObject.NULL)
                .put("request", state.request)
                .put("currentPurpose", state.currentPurpose)
                .put("requestAvailable", coordinator.pendingRequest()?.sessionId == state.sessionId)
            is SessionState.Paused -> response
                .put("sessionId", state.sessionId)
                .put("conversationId", state.conversationId ?: JSONObject.NULL)
                .put("request", state.request)
                .put("currentPurpose", state.currentPurpose)
            else -> Unit
        }
        write(writer, response)
    }

    private fun pendingRequest(
        requestId: String,
        writer: BufferedWriter,
    ) {
        // The companion's normal pending-request poll doubles as its
        // heartbeat. The phone uses this to render the existing recovery card
        // without exposing the request or requiring another protocol.
        markCompanionSeen()
        val pending = coordinator.pendingRequest()
        val response = JSONObject()
            .put("type", "pending_request")
            .put("requestId", requestId)
            .put("ok", true)
            .put("available", pending != null)
            .put("warmupRequested", codexWarmupRequested.getAndSet(false))
        if (pending != null) {
            response
                .put("sessionId", pending.sessionId)
                .put("conversationId", pending.conversationId ?: JSONObject.NULL)
                .put("codexThreadId", pending.codexThreadId ?: JSONObject.NULL)
                .put("reasoningEffort", pending.reasoningEffort)
                .put("request", pending.request)
        }
        write(writer, response)
    }

    private fun claimRequest(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val expectedSessionId = json.optString("sessionId").trim().ifBlank { null }
        val claimed = coordinator.claimRequest(expectedSessionId)
        if (claimed == null) {
            write(
                writer,
                errorResponse(requestId, "No unclaimed running phone request matched the supplied sessionId.")
                    .put("code", "REQUEST_NOT_AVAILABLE"),
            )
            return
        }
        write(
            writer,
            JSONObject()
                .put("type", "request_claimed")
                .put("requestId", requestId)
                .put("ok", true)
                .put("sessionId", claimed.sessionId)
                .put("conversationId", claimed.conversationId ?: JSONObject.NULL)
                .put("codexThreadId", claimed.codexThreadId ?: JSONObject.NULL)
                .put("reasoningEffort", claimed.reasoningEffort)
                .put("request", claimed.request)
                .put("message", "Phone request claimed by the desktop Codex companion."),
        )
    }

    private fun pendingSteer(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        markCompanionSeen()
        val expectedSessionId = json.optString("sessionId").trim().ifBlank { null }
        val state = coordinator.state.value
        val pending = coordinator.pendingSteer(expectedSessionId)
        val response = JSONObject()
            .put("type", "pending_steer")
            .put("requestId", requestId)
            .put("ok", true)
            .put("active", state is SessionState.Running)
            .put("available", pending != null)
        if (pending != null) {
            response
                .put("steerId", pending.steerId)
                .put("sessionId", pending.sessionId)
        }
        write(writer, response)
    }

    private fun claimSteer(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val claimed = coordinator.claimSteer(sessionId, steerId)
        if (claimed == null) {
            write(
                writer,
                errorResponse(requestId, "No unclaimed steer matched the supplied session and steer id.")
                    .put("code", "STEER_NOT_AVAILABLE"),
            )
            return
        }
        write(
            writer,
            JSONObject()
                .put("type", "steer_claimed")
                .put("requestId", requestId)
                .put("ok", true)
                .put("steerId", claimed.steerId)
                .put("sessionId", claimed.sessionId)
                .put("text", claimed.text),
        )
    }

    private fun releaseSteer(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val released = coordinator.releaseSteer(sessionId, steerId)
        write(
            writer,
            JSONObject()
                .put("type", "steer_released")
                .put("requestId", requestId)
                .put("ok", released)
                .put("sessionId", sessionId)
                .put("steerId", steerId)
                .put("released", released),
        )
    }

    private fun completeSteer(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        val steerId = json.optString("steerId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        require(steerId.isNotEmpty()) { "steerId is required." }
        val completed = coordinator.completeSteer(sessionId, steerId)
        write(
            writer,
            JSONObject()
                .put("type", "steer_completed")
                .put("requestId", requestId)
                .put("ok", completed)
                .put("sessionId", sessionId)
                .put("steerId", steerId)
                .put("delivered", completed),
        )
    }

    private fun releaseRequest(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        val released = coordinator.releaseRequest(sessionId)
        write(
            writer,
            JSONObject()
                .put("type", "request_released")
                .put("requestId", requestId)
                .put("ok", true)
                .put("sessionId", sessionId)
                .put("released", released),
        )
    }

    private fun bindCodexThread(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val conversationId = json.optString("conversationId").trim()
        val codexThreadId = json.optString("codexThreadId").trim()
        require(conversationId.isNotEmpty()) { "conversationId is required." }
        require(codexThreadId.isNotEmpty()) { "codexThreadId is required." }
        val bound = coordinator.bindCodexThread(conversationId, codexThreadId)
        write(
            writer,
            JSONObject()
                .put("type", "codex_thread_bound")
                .put("requestId", requestId)
                .put("ok", bound)
                .put("conversationId", conversationId)
                .put("codexThreadId", codexThreadId),
        )
    }

    private fun failSession(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        if (coordinator.state.value.sessionIdOrNullForBridge() != sessionId) {
            write(
                writer,
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", "SESSION_NOT_RUNNING"),
            )
            return
        }
        val reason = json.optString("reason", "The desktop Codex turn failed.")
            .trim()
            .ifBlank { "The desktop Codex turn failed." }
            .take(MAX_AGENT_FEEDBACK_CHARS)
        val failed = coordinator.fail(reason)
        if (failed) {
            AssistantForegroundService.showAttentionNotification(
                context,
                "DHD stopped: $reason",
                coordinator.state.value.conversationIdOrNullForBridge(),
            )
        }
        context.stopService(Intent(context, AssistantForegroundService::class.java))
        write(
            writer,
            JSONObject()
                .put("type", "session_failed")
                .put("requestId", requestId)
                .put("ok", failed)
                .put("message", reason),
        )
    }

    private fun completeSession(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val sessionId = json.optString("sessionId").trim()
        require(sessionId.isNotEmpty()) { "sessionId is required." }
        val message = json.optString("message", "Your DHD task is ready to review.")
            .trim()
            .ifBlank { "Your DHD task is ready to review." }
            .take(MAX_TEXT_CHARS)
        val feedback = json.optString("feedback")
            .trim()
            .ifBlank { null }
            ?.take(MAX_AGENT_FEEDBACK_CHARS)
        val activeSessionId = coordinator.state.value.sessionIdOrNullForBridge()
        if (activeSessionId != sessionId) {
            write(
                writer,
                errorResponse(requestId, "The phone session is no longer active.")
                    .put("code", "SESSION_NOT_RUNNING"),
            )
            return
        }
        val completionMessage = feedback ?: message
        val completed = coordinator.complete(completionMessage, agentFeedback = feedback)
        if (completed) {
            AssistantForegroundService.showCompletionNotification(context, completionMessage, coordinator.state.value.conversationIdOrNullForBridge())
        }
        context.stopService(Intent(context, AssistantForegroundService::class.java))
        write(
            writer,
            JSONObject()
                .put("type", "session_completed")
                .put("requestId", requestId)
                .put("ok", completed)
                .put("sessionId", sessionId)
                .put("conversationId", coordinator.state.value.conversationIdOrNullForBridge() ?: JSONObject.NULL)
                .put("message", completionMessage)
                .put("feedback", feedback ?: JSONObject.NULL),
        )
    }

    private fun requestAttention(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val reason = json.optString("reason")
            .trim()
            .ifBlank { "The phone assistant needs your attention." }
            .take(MAX_TEXT_CHARS)
        if (!coordinator.requestAttention(reason)) {
            write(
                writer,
                errorResponse(requestId, "The phone assistant has no active session to interrupt.")
                    .put("code", "SESSION_NOT_RUNNING"),
            )
            return
        }
        AssistantForegroundService.showAttentionNotification(context, reason, coordinator.state.value.conversationIdOrNullForBridge())
        write(
            writer,
            JSONObject()
                .put("type", "attention_requested")
                .put("requestId", requestId)
                .put("ok", true)
                .put("message", reason),
        )
    }

    private fun allowedApps(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val fullAccess = fullAccessProvider()
        val includeAll = json.optBoolean("includeAll", false)
        if (includeAll && !fullAccess) {
            write(
                writer,
                errorResponse(requestId, "Full Access is required to enumerate all launchable apps.")
                    .put("code", "FULL_ACCESS_REQUIRED")
                    .put("fullAccess", false)
                    .put("accessMode", "allowlist")
                    .put("canListAllApps", false),
            )
            return
        }
        write(
            writer,
            buildAllowedAppsResponse(
                requestId = requestId,
                fullAccess = fullAccess,
                includeAll = includeAll,
                allowedPackages = if (fullAccess) emptySet() else allowedPackagesProvider(),
                apps = if (includeAll) installedAppsRepository.listLaunchableApps() else emptyList(),
            ),
        )
    }

    private fun browseApps(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val query = json.optString("query").trim()
        if (query.isEmpty() || query.length > MAX_APP_QUERY_CHARS) {
            write(
                writer,
                errorResponse(requestId, "App search requires a query between 1 and $MAX_APP_QUERY_CHARS characters.")
                    .put("code", "INVALID_APP_QUERY"),
            )
            return
        }

        val fullAccess = fullAccessProvider()
        val allowedPackages = if (fullAccess) emptySet() else allowedPackagesProvider()
        val candidates = installedAppsRepository.listLaunchableApps()
            .asSequence()
            .filter { fullAccess || it.packageName in allowedPackages }
            .filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
            .toList()
        val returnedApps = candidates.take(MAX_APP_BROWSE_RESULTS)
        write(
            writer,
            buildBrowseAppsResponse(
                requestId = requestId,
                query = query,
                fullAccess = fullAccess,
                apps = returnedApps,
                truncated = candidates.size > returnedApps.size,
            ),
        )
    }

    private suspend fun observe(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val expectedPackage = json.optString("expectedPackageName").trim().ifBlank { null }
        if (expectedPackage != null) {
            require(PACKAGE_PATTERN.matches(expectedPackage)) {
                "expectedPackageName is not a valid Android package name."
            }
        }
        val purpose = json.optString("purpose").trim().take(MAX_TEXT_CHARS).ifBlank { null }
        if (purpose != null) {
            coordinator.recordPurpose(
                purpose = purpose,
                targetDescription = json.optString("targetDescription").trim().take(MAX_TEXT_CHARS).ifBlank { expectedPackage },
            )
        }
        // Screenshots are still returned for the model's visual context, but
        // the assistant path no longer fingerprints guard regions or rejects
        // actions because the screen changed between calls.
        when (val captured = captureWithRetry(expectedPackage, emptyList())) {
            is ObservationCaptureResult.Failed -> write(writer, errorResponse(requestId, captured.message))
            is ObservationCaptureResult.Succeeded -> {
                remember(captured.snapshot)
                writeObservation(writer, requestId, captured.snapshot, captured.screenshot)
            }
        }
    }

    private suspend fun executeAction(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val actionJson = json.optJSONObject("action")
            ?: throw IllegalArgumentException("action must be an object.")
        val action = parsePhoneAction(actionJson)
        val observation = synchronized(observations) {
            action.metadata.observationId
                .takeIf { it.isNotBlank() }
                ?.let { observations[it] }
                ?: observations.values.lastOrNull()
        } ?: when (val captured = captureWithRetry(null, emptyList())) {
            is ObservationCaptureResult.Failed -> {
                val message = "The action could not get a current phone screenshot: ${captured.message}"
                write(
                    writer,
                    errorResponse(requestId, message)
                        .put("action", wireActionName(action))
                        .put("code", observationFailureCode(captured.message))
                        .put("outcome", "failed")
                        .put("executed", false),
                )
                return
            }

            is ObservationCaptureResult.Succeeded -> captured.snapshot.also(::remember)
        }
        val result = coordinator.executeAction(action, observation)
        writeActionResult(writer, requestId, wireActionName(action), result)
        if (!result.isSuccessful()) {
            val response = JSONObject()
                .put("type", "completed")
                .put("requestId", requestId)
                .put("ok", false)
                .put("action", wireActionName(action))
                .put("message", result.failureMessage())
            result.failureCode()?.let { response.put("code", it) }
            write(writer, response)
            return
        }

        if (action is OpenAppAction) {
            delay(OPEN_SETTLE_DELAY_MS)
        } else if (action !is WaitAction) {
            delay(POST_ACTION_SETTLE_DELAY_MS)
        }
        val expectedPackage = when (action) {
            is OpenAppAction -> action.packageName
            else -> observation.packageName
        }
        when (val captured = captureWithRetry(expectedPackage, emptyList())) {
            is ObservationCaptureResult.Failed -> {
                coordinator.stop("Post-action observation failed: ${captured.message}")
                write(
                    writer,
                    JSONObject()
                        .put("type", "completed")
                        .put("requestId", requestId)
                        .put("ok", false)
                        .put("action", wireActionName(action))
                        .put("outcome", "unknown")
                        .put("code", "POST_OBSERVATION_FAILED")
                        .put("message", "The action may have run, but the phone could not produce a post-action screenshot: ${captured.message}"),
                )
            }

            is ObservationCaptureResult.Succeeded -> {
                remember(captured.snapshot)
                write(
                    writer,
                    JSONObject()
                        .put("type", "completed")
                        .put("requestId", requestId)
                        .put("ok", true)
                        .put("action", wireActionName(action))
                        .put("message", result.successMessage())
                        .put("observation", snapshotJson(captured.snapshot))
                        .put("screenshotBase64", Base64.encodeToString(captured.screenshot, Base64.NO_WRAP))
                        .put("screenshotMimeType", "image/png"),
                )
            }
        }
    }

    private fun stopSession(
        requestId: String,
        json: JSONObject,
        writer: BufferedWriter,
    ) {
        val reason = json.optString("reason", "Stopped by the desktop assistant.")
            .trim()
            .ifBlank { "Stopped by the desktop assistant." }
            .take(MAX_TEXT_CHARS)
        val stopped = coordinator.stop(reason)
        context.stopService(Intent(context, AssistantForegroundService::class.java))
        write(
            writer,
            JSONObject()
                .put("type", "stopped")
                .put("requestId", requestId)
                .put("ok", true)
                .put("wasActive", stopped)
                .put("message", reason),
        )
    }

    private fun parsePhoneAction(json: JSONObject): PhoneAction {
        val metadata = parseMetadata(json.optJSONObject("metadata"))
        return when (json.optString("type")) {
            "open_app" -> {
                val packageName = json.optString("packageName")
                require(PACKAGE_PATTERN.matches(packageName)) { "packageName is not a valid Android package name." }
                OpenAppAction(packageName, metadata)
            }

            "tap" -> TapAction(
                x = json.getInt("x"),
                y = json.getInt("y"),
                metadata = metadata,
            )

            "type" -> TypeAction(json.getString("text"), metadata)

            "swipe" -> SwipeAction(
                startX = json.getInt("startX"),
                startY = json.getInt("startY"),
                endX = json.getInt("endX"),
                endY = json.getInt("endY"),
                durationMs = json.optLong("durationMs", 350L),
                metadata = metadata,
            )

            "scroll" -> ScrollAction(
                direction = enumValue<ScrollDirection>(json.getString("direction")),
                amount = enumValue<ScrollAmount>(json.getString("amount")),
                metadata = metadata,
            )

            "back" -> BackAction(metadata)

            "keypress" -> KeypressAction(
                key = enumValue<KeypressKey>(json.getString("key")),
                metadata = metadata,
            )

            "wait" -> WaitAction(json.getLong("durationMs"), metadata)

            else -> throw IllegalArgumentException(
                "Unsupported action type. Use open_app, tap, type, swipe, scroll, back, keypress, or wait.",
            )
        }
    }

    private fun parseMetadata(json: JSONObject?): ActionMetadata {
        require(json != null) { "action.metadata is required." }
        val purpose = json.optString("purpose").trim()
        // observationId and guardRegions remain accepted for compatibility with
        // older callers, but are optional while freshness enforcement is off.
        val observationId = json.optString("observationId").trim().take(MAX_TEXT_CHARS)
        val targetDescription = json.optString("targetDescription").trim()
        require(purpose.isNotEmpty() && purpose.length <= MAX_TEXT_CHARS) {
            "metadata.purpose must be 1-$MAX_TEXT_CHARS characters."
        }
        require(targetDescription.isNotEmpty() && targetDescription.length <= MAX_TEXT_CHARS) {
            "metadata.targetDescription must be 1-$MAX_TEXT_CHARS characters."
        }
        return ActionMetadata(
            purpose = purpose,
            observationId = observationId,
            targetDescription = targetDescription,
            guardRegions = parseGuardRegions(json.optJSONArray("guardRegions")),
        )
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String): T =
        enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unsupported enum value: $value")

    private fun wireActionName(action: PhoneAction): String = when (action) {
        is OpenAppAction -> "open_app"
        is TapAction -> "tap"
        is TypeAction -> "type"
        is SwipeAction -> "swipe"
        is ScrollAction -> "scroll"
        is BackAction -> "back"
        is KeypressAction -> "keypress"
        is WaitAction -> "wait"
    }

    private fun remember(snapshot: ObservationSnapshot) {
        synchronized(observations) {
            observations[snapshot.id] = snapshot
        }
    }

    private fun writeObservation(
        writer: BufferedWriter,
        requestId: String,
        snapshot: ObservationSnapshot,
        screenshot: ByteArray,
    ) {
        write(
            writer,
            JSONObject()
                .put("type", "observation")
                .put("requestId", requestId)
                .put("ok", true)
                .put("observation", snapshotJson(snapshot))
                .put("screenshotBase64", Base64.encodeToString(screenshot, Base64.NO_WRAP))
                .put("screenshotMimeType", "image/png"),
        )
    }

    private fun snapshotJson(snapshot: ObservationSnapshot): JSONObject = JSONObject()
        .put("id", snapshot.id)
        .put("packageName", snapshot.packageName)
        .put("activityName", snapshot.activityName ?: JSONObject.NULL)
        .put("displayId", snapshot.displayId)
        .put("rotation", snapshot.rotation)
        .put("width", snapshot.width)
        .put("height", snapshot.height)
        .put("screenshotFingerprint", snapshot.screenshotFingerprint)

    private fun stateName(state: SessionState): String = when (state) {
        SessionState.Idle -> "idle"
        is SessionState.Running -> "running"
        is SessionState.Paused -> "paused"
        is SessionState.Stopped -> "stopped"
        is SessionState.Completed -> "completed"
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
        expectedPackageName: String?,
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

    private fun observationFailureCode(message: String): String =
        if (message.contains("Shizuku", ignoreCase = true) &&
            message.contains("unavailable", ignoreCase = true)
        ) {
            "SHIZUKU_UNAVAILABLE"
        } else {
            "OBSERVATION_FAILED"
        }

    private fun write(writer: BufferedWriter, json: JSONObject) {
        writer.write(json.toString())
        writer.newLine()
        writer.flush()
    }

    private fun isAuthorized(socket: Socket, json: JSONObject): Boolean {
        // adb forward presents the desktop peer as loopback. Keep this local
        // development path compatible without requiring a token, while every
        // actual LAN peer must prove possession of the paired token.
        if (socket.inetAddress.isLoopbackAddress) return true
        val candidate = json.optString("authToken").trim().toByteArray(Charsets.UTF_8)
        val expected = authenticationToken.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(candidate, expected)
    }

    /** Return currently usable IPv4 addresses that the desktop can dial. */
    fun lanIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { networkInterface ->
                networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isVirtual
            }
            ?.flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
            ?.filterIsInstance<Inet4Address>()
            ?.filter { address -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
            ?.mapNotNull(Inet4Address::getHostAddress)
            ?.distinct()
            ?.sorted()
            ?.toList()
            ?: emptyList()
    }.getOrDefault(emptyList())

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
        const val LAN_BIND_HOST = "0.0.0.0"
        const val DEFAULT_PORT = 8765
        const val PAIRING_DISCOVERY_PORT = 8766
        const val PAIRING_PROTOCOL_VERSION = 1
        const val PREFERENCES_NAME = "dhd_companion_link"
        const val KEY_AUTH_TOKEN = "bridge_auth_token"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PAIRING_CODE = "pairing_code"
        const val PAIRING_CODE_LENGTH = 8
        const val PAIRING_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        const val COMPANION_PRESENCE_TIMEOUT_MS = 5_000L
        const val COMPANION_PRESENCE_CHECK_INTERVAL_MS = 1_000L
        const val MAX_REQUEST_CHARS = 16_384
        const val MAX_TEXT_CHARS = 240
        const val MAX_AGENT_FEEDBACK_CHARS = 4_000
        const val MAX_APP_QUERY_CHARS = 120
        const val MAX_APP_BROWSE_RESULTS = 25
        const val MAX_GUARD_REGIONS = 8
        const val MAX_OBSERVATIONS = 64
        const val OPEN_SETTLE_DELAY_MS = 750L
        const val POST_ACTION_SETTLE_DELAY_MS = 350L
        const val CAPTURE_ATTEMPTS = 5
        const val CAPTURE_RETRY_DELAY_MS = 250L
        val PACKAGE_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")
        val secureRandom = SecureRandom()
    }
}

internal fun buildAllowedAppsResponse(
    requestId: String,
    fullAccess: Boolean,
    allowedPackages: Set<String>,
    includeAll: Boolean = false,
    apps: List<InstalledUserApp> = emptyList(),
): JSONObject {
    val response = JSONObject()
        .put("type", "allowed_apps")
        .put("requestId", requestId)
        .put("ok", true)
        .put("fullAccess", fullAccess)
        .put("accessMode", if (fullAccess) "full_access" else "allowlist")
        .put("canListAllApps", fullAccess)

    if (fullAccess && includeAll) {
        response
            .put("apps", JSONArray(apps.map(::buildAppResponse)))
            .put("count", apps.size)
            .put("message", "Full Access is enabled. Returned all launchable apps on the phone.")
    } else if (fullAccess) {
        response.put("message", "Full Access is enabled. You can use any launchable app on the phone.")
    } else {
        val packages = allowedPackages.toList().sorted()
        response
            .put("allowedPackages", JSONArray(packages))
            .put("count", packages.size)
    }

    return response
}

internal fun buildBrowseAppsResponse(
    requestId: String,
    query: String,
    fullAccess: Boolean,
    apps: List<InstalledUserApp>,
    truncated: Boolean,
): JSONObject = JSONObject()
    .put("type", "browse_apps")
    .put("requestId", requestId)
    .put("ok", true)
    .put("query", query)
    .put("fullAccess", fullAccess)
    .put("accessMode", if (fullAccess) "full_access" else "allowlist")
    .put("apps", JSONArray(apps.map(::buildAppResponse)))
    .put("count", apps.size)
    .put("truncated", truncated)

private fun buildAppResponse(app: InstalledUserApp): JSONObject = JSONObject()
    .put("appLabel", app.label)
    .put("packageName", app.packageName)

private fun ActionExecutionResult.isSuccessful(): Boolean = this is ActionExecutionResult.TransportFinished &&
    this.result is TransportResult.Succeeded

private fun ActionExecutionResult.failureMessage(): String = when (this) {
    is ActionExecutionResult.TransportFinished -> when (val result = result) {
        is TransportResult.Rejected -> result.message
        is TransportResult.Unsupported -> result.message
        is TransportResult.Succeeded -> result.message
    }
    is ActionExecutionResult.PolicyRejected -> message
    ActionExecutionResult.SessionNotRunning -> "The phone session is no longer running."
}

private fun ActionExecutionResult.successMessage(): String = when (this) {
    is ActionExecutionResult.TransportFinished -> when (val result = result) {
        is TransportResult.Succeeded -> result.message
        is TransportResult.Rejected -> result.message
        is TransportResult.Unsupported -> result.message
    }
    is ActionExecutionResult.PolicyRejected -> message
    ActionExecutionResult.SessionNotRunning -> "The phone session is no longer running."
}

private fun ActionExecutionResult.failureCode(): String? = when (this) {
    is ActionExecutionResult.TransportFinished -> when (val result = result) {
        is TransportResult.Rejected -> result.code.name
        is TransportResult.Unsupported -> "UNSUPPORTED_ACTION"
        is TransportResult.Succeeded -> null
    }
    is ActionExecutionResult.PolicyRejected -> "POLICY_REJECTED"
    ActionExecutionResult.SessionNotRunning -> "SESSION_NOT_RUNNING"
}

private fun SessionState.sessionIdOrNullForBridge(): String? = when (this) {
    SessionState.Idle -> null
    is SessionState.Running -> sessionId
    is SessionState.Paused -> sessionId
    is SessionState.Stopped -> sessionId
    is SessionState.Completed -> sessionId
}

private fun SessionState.conversationIdOrNullForBridge(): String? = when (this) {
    SessionState.Idle -> null
    is SessionState.Running -> conversationId
    is SessionState.Paused -> conversationId
    is SessionState.Stopped -> conversationId
    is SessionState.Completed -> conversationId
}
