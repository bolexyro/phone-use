package com.phonecontrol.assistant.developer

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object DhdAdbProtocol {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534c5453

    const val A_VERSION = 0x01000000
    const val A_MAXDATA = 4096
    const val A_STLS_VERSION = 0x01000000

    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3

    const val SHELL_V2_STDOUT = 1
    const val SHELL_V2_STDERR = 2
    const val SHELL_V2_EXIT = 3

    const val MAX_WIRE_PAYLOAD = 16 * 1024 * 1024
    const val MAX_SHELL_PACKET = 16 * 1024 * 1024

    fun message(command: Int, arg0: Int, arg1: Int, data: ByteArray = ByteArray(0)): ByteArray {
        require(data.size <= MAX_WIRE_PAYLOAD) { "ADB payload is too large." }
        val buffer = ByteBuffer
            .allocate(24 + data.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(command)
        buffer.putInt(arg0)
        buffer.putInt(arg1)
        buffer.putInt(data.size)
        buffer.putInt(checksum(data))
        buffer.putInt((command.toLong() xor 0xffffffffL).toInt())
        buffer.put(data)
        return buffer.array()
    }

    fun message(command: Int, arg0: Int, arg1: Int, data: String): ByteArray =
        message(command, arg0, arg1, (data + '\u0000').toByteArray(Charsets.UTF_8))

    fun checksum(data: ByteArray): Int {
        var sum = 0
        data.forEach { sum += it.toInt() and 0xff }
        return sum
    }

    fun decodeShellV2Packet(data: ByteArray): List<ShellV2Packet> {
        val decoder = ShellV2Decoder()
        decoder.append(data)
        return decoder.drain()
    }

    data class ShellV2Packet(val streamId: Int, val payload: ByteArray)

    /** Incremental parser because one ADB WRTE frame can split shell packets. */
    class ShellV2Decoder {
        private var pending = ByteArray(0)
        private val packets = ArrayDeque<ShellV2Packet>()
        private var totalPayload = 0

        fun append(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            require(bytes.size <= MAX_WIRE_PAYLOAD) { "ADB shell frame is too large." }
            pending = pending + bytes
            require(pending.size <= MAX_WIRE_PAYLOAD) { "ADB shell output is too large." }
            parseAvailable()
        }

        fun drain(): List<ShellV2Packet> = buildList {
            while (packets.isNotEmpty()) add(packets.removeFirst())
        }

        private fun parseAvailable() {
            var offset = 0
            while (pending.size - offset >= 5) {
                val streamId = pending[offset].toInt() and 0xff
                val length = ByteBuffer
                    .wrap(pending, offset + 1, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                require(length in 0..MAX_SHELL_PACKET) {
                    "ADB shell packet length is invalid: $length."
                }
                if (pending.size - offset < 5 + length) break
                val payload = pending.copyOfRange(offset + 5, offset + 5 + length)
                packets += ShellV2Packet(streamId, payload)
                totalPayload += length
                require(totalPayload <= MAX_WIRE_PAYLOAD) { "ADB shell output is too large." }
                offset += 5 + length
            }
            if (offset > 0) pending = pending.copyOfRange(offset, pending.size)
        }
    }

    internal fun shellV2Packet(streamId: Int, payload: ByteArray): ByteArray {
        require(streamId in 0..255) { "ADB shell stream id is invalid." }
        require(payload.size <= MAX_SHELL_PACKET) { "ADB shell packet is too large." }
        return ByteBuffer
            .allocate(5 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(streamId.toByte())
            .putInt(payload.size)
            .put(payload)
            .array()
    }
}

/** ADB wire service used by `adb shell` when it requests shell-v2 raw output. */
internal fun buildDhdAdbShellV2Service(command: String): String =
    "shell,v2,TERM=dumb,raw:$command"

/** ADB wire service used by the desktop `adb exec-out` command. */
internal fun buildDhdAdbExecService(command: String): String =
    "exec:$command"
