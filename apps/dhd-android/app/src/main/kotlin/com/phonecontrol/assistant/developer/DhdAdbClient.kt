package com.phonecontrol.assistant.developer

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

internal data class DhdAdbCommandResult(
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: String,
)

/** Minimal ADB client for the local Wireless Debugging endpoint. */
internal class DhdAdbClient(
    private val host: String,
    private val port: Int,
    private val key: DhdAdbKey,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : Closeable {
    private var socket: Socket? = null
    private var tlsSocket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    fun connect() {
        check(socket == null) { "ADB client is already connected." }
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.soTimeout = readTimeoutMs
        raw.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket = raw
        input = DataInputStream(raw.getInputStream())
        output = DataOutputStream(raw.getOutputStream())

        write(DhdAdbProtocol.A_CNXN, DhdAdbProtocol.A_VERSION, DhdAdbProtocol.A_MAXDATA, "host::")
        var response = read()
        if (response.command == DhdAdbProtocol.A_STLS) {
            write(DhdAdbProtocol.A_STLS, DhdAdbProtocol.A_STLS_VERSION, 0)
            val secure = key.sslContext.socketFactory.createSocket(
                raw,
                host,
                port,
                true,
            ) as SSLSocket
            secure.useClientMode = true
            secure.startHandshake()
            tlsSocket = secure
            input = DataInputStream(secure.inputStream)
            output = DataOutputStream(secure.outputStream)
            response = read()
        }
        if (response.command != DhdAdbProtocol.A_CNXN) {
            throw IOException("Wireless Debugging rejected DHD's ADB connection (${response.commandName()}).")
        }
    }

    fun shellV2(command: String): DhdAdbCommandResult {
        // Match the service request emitted by the ADB client for a raw
        // shell-v2 stream. Without the TERM/raw options Android's adbd may
        // accept the transport connection but close the command channel.
        val channel = open(buildDhdAdbShellV2Service(command))
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val decoder = DhdAdbProtocol.ShellV2Decoder()
        var exitCode: Int? = null

        try {
            while (true) {
                val message = read()
                when (message.command) {
                    DhdAdbProtocol.A_WRTE -> {
                        decoder.append(message.data)
                        decoder.drain().forEach { packet ->
                            when (packet.streamId) {
                                DhdAdbProtocol.SHELL_V2_STDOUT -> appendBounded(stdout, packet.payload)
                                DhdAdbProtocol.SHELL_V2_STDERR -> appendBounded(stderr, packet.payload)
                                DhdAdbProtocol.SHELL_V2_EXIT -> {
                                    // ADB's shell protocol writes the exit
                                    // status as one unsigned byte, not an
                                    // int-sized payload.
                                    require(packet.payload.size == 1) {
                                        "ADB shell exit packet has an invalid size."
                                    }
                                    exitCode = packet.payload[0].toInt() and 0xff
                                }
                                else -> throw IOException(
                                    "ADB returned an unknown shell stream ${packet.streamId}.",
                                )
                            }
                        }
                        write(DhdAdbProtocol.A_OKAY, channel.localId, message.arg0)
                    }

                    DhdAdbProtocol.A_OKAY -> {
                        // ADB can acknowledge the remote side before its next
                        // output frame. There is nothing to send here.
                    }

                    DhdAdbProtocol.A_CLSE -> {
                        write(DhdAdbProtocol.A_CLSE, channel.localId, message.arg0)
                        break
                    }

                    else -> throw IOException(
                        "ADB returned ${message.commandName()} while running a shell command.",
                    )
                }
            }
        } finally {
            closeQuietly()
        }

        return DhdAdbCommandResult(
            exitCode = exitCode,
            stdout = stdout.toByteArray(),
            stderr = String(stderr.toByteArray(), Charsets.UTF_8).trim(),
        )
    }

    fun execOut(command: String): DhdAdbCommandResult {
        // `exec-out` is the desktop adb CLI spelling. The ADB wire service
        // itself is named `exec`.
        val channel = open(buildDhdAdbExecService(command))
        val stdout = ByteArrayOutputStream()
        try {
            while (true) {
                val message = read()
                when (message.command) {
                    DhdAdbProtocol.A_WRTE -> {
                        appendBounded(stdout, message.data)
                        write(DhdAdbProtocol.A_OKAY, channel.localId, message.arg0)
                    }

                    DhdAdbProtocol.A_OKAY -> Unit

                    DhdAdbProtocol.A_CLSE -> {
                        write(DhdAdbProtocol.A_CLSE, channel.localId, message.arg0)
                        break
                    }

                    else -> throw IOException(
                        "ADB returned ${message.commandName()} while reading command output.",
                    )
                }
            }
        } finally {
            closeQuietly()
        }
        return DhdAdbCommandResult(exitCode = 0, stdout = stdout.toByteArray(), stderr = "")
    }

    private fun open(service: String): AdbChannel {
        val localId = 1
        write(DhdAdbProtocol.A_OPEN, localId, 0, service)
        val response = read()
        return when (response.command) {
            DhdAdbProtocol.A_OKAY -> AdbChannel(localId, response.arg0)
            DhdAdbProtocol.A_CLSE -> {
                write(DhdAdbProtocol.A_CLSE, localId, response.arg0)
                throw IOException("ADB rejected the local command channel.")
            }
            else -> throw IOException("ADB returned ${response.commandName()} while opening a channel.")
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)) {
        val stream = output ?: throw IOException("ADB connection is not open.")
        stream.write(DhdAdbProtocol.message(command, arg0, arg1, data))
        stream.flush()
    }

    private fun appendBounded(output: ByteArrayOutputStream, bytes: ByteArray) {
        require(bytes.size <= DhdAdbProtocol.MAX_WIRE_PAYLOAD - output.size()) {
            "ADB command output is too large."
        }
        output.write(bytes)
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) =
        write(command, arg0, arg1, (data + '\u0000').toByteArray(Charsets.UTF_8))

    private fun read(): DhdAdbMessage {
        val stream = input ?: throw IOException("ADB connection is not open.")
        val header = ByteArray(24)
        stream.readFully(header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val dataChecksum = buffer.int
        val magic = buffer.int
        require(command == (magic xor -1)) { "ADB message magic is invalid." }
        require(dataLength in 0..DhdAdbProtocol.MAX_WIRE_PAYLOAD) {
            "ADB message length is invalid: $dataLength."
        }
        val data = ByteArray(dataLength)
        if (dataLength > 0) stream.readFully(data)
        require(DhdAdbProtocol.checksum(data) == dataChecksum) { "ADB message checksum is invalid." }
        return DhdAdbMessage(command, arg0, arg1, data)
    }

    override fun close() {
        closeQuietly()
    }

    private fun closeQuietly() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { tlsSocket?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        tlsSocket = null
        socket = null
    }

    private data class AdbChannel(val localId: Int, val remoteId: Int)

    private data class DhdAdbMessage(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val data: ByteArray,
    ) {
        fun commandName(): String = when (command) {
            DhdAdbProtocol.A_CNXN -> "A_CNXN"
            DhdAdbProtocol.A_AUTH -> "A_AUTH"
            DhdAdbProtocol.A_OPEN -> "A_OPEN"
            DhdAdbProtocol.A_OKAY -> "A_OKAY"
            DhdAdbProtocol.A_WRTE -> "A_WRTE"
            DhdAdbProtocol.A_CLSE -> "A_CLSE"
            DhdAdbProtocol.A_STLS -> "A_STLS"
            else -> "0x${command.toUInt().toString(16)}"
        }
    }

    private companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 2_000
        const val DEFAULT_READ_TIMEOUT_MS = 5_000
    }
}
