package com.phonecontrol.assistant.developer

import com.phonecontrol.assistant.execution.PhoneProcessResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** Client for the long-lived shell-UID DHD maintenance daemon. */
internal class DhdMaintenanceClient(
    private val port: Int,
    private val token: String,
    private val connectTimeoutMs: Int = 500,
    private val readTimeoutMs: Int = 20_000,
) {
    fun execute(command: List<String>, binaryOutput: Boolean = false): PhoneProcessResult {
        require(command.isNotEmpty()) { "A maintenance command must not be empty." }
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.soTimeout = readTimeoutMs
            socket.connect(InetSocketAddress(LOOPBACK, port), connectTimeoutMs)
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            DhdMaintenanceProtocol.writeRequest(output, token, command, binaryOutput)
            output.flush()
            val response = DhdMaintenanceProtocol.readResponse(input)
            return PhoneProcessResult(
                exitCode = response.exitCode.takeUnless { it == DhdMaintenanceProtocol.EXIT_CODE_UNAVAILABLE },
                stdout = response.stdout,
                stderr = String(response.stderr, Charsets.UTF_8).trim(),
                timedOut = response.timedOut,
            )
        }
    }

    fun isReady(): Boolean = runCatching {
        execute(listOf("true")).exitCode == 0
    }.getOrDefault(false)

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private val random = SecureRandom()

        fun newToken(): String = buildString {
            repeat(32) { append("0123456789abcdef"[random.nextInt(16)]) }
        }

        fun newPort(): Int = 38_000 + random.nextInt(20_000)
    }
}
