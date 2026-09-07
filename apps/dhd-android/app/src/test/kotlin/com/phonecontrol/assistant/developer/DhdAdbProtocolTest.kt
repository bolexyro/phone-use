package com.phonecontrol.assistant.developer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DhdAdbProtocolTest {
    @Test
    fun `uses Android adb shell v2 wire service options`() {
        assertEquals(
            "shell,v2,TERM=dumb,raw:echo hi",
            buildDhdAdbShellV2Service("echo hi"),
        )
    }

    @Test
    fun `uses exec wire service for exec out`() {
        assertEquals("exec:screencap -p", buildDhdAdbExecService("screencap -p"))
    }

    @Test
    fun `encodes ADB headers with little endian fields and checksum`() {
        val service = buildDhdAdbShellV2Service("echo hi")
        val bytes = DhdAdbProtocol.message(
            DhdAdbProtocol.A_OPEN,
            arg0 = 7,
            arg1 = 0,
            data = service,
        )
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals(DhdAdbProtocol.A_OPEN, buffer.int)
        assertEquals(7, buffer.int)
        assertEquals(0, buffer.int)
        assertEquals(service.length + 1, buffer.int)
        assertEquals(DhdAdbProtocol.checksum((service + '\u0000').toByteArray()), buffer.int)
        assertEquals(DhdAdbProtocol.A_OPEN xor -1, buffer.int)
        assertArrayEquals((service + '\u0000').toByteArray(), bytes.copyOfRange(24, bytes.size))
    }

    @Test
    fun `shell v2 decoder handles split frames and multiple streams`() {
        val stdout = DhdAdbProtocol.shellV2Packet(
            DhdAdbProtocol.SHELL_V2_STDOUT,
            "hello".toByteArray(),
        )
        val stderr = DhdAdbProtocol.shellV2Packet(
            DhdAdbProtocol.SHELL_V2_STDERR,
            "warning".toByteArray(),
        )
        val exit = DhdAdbProtocol.shellV2Packet(
            DhdAdbProtocol.SHELL_V2_EXIT,
            byteArrayOf(0),
        )
        val decoder = DhdAdbProtocol.ShellV2Decoder()

        decoder.append(stdout.copyOfRange(0, 3))
        assertEquals(0, decoder.drain().size)
        decoder.append(stdout.copyOfRange(3, stdout.size) + stderr + exit)

        val packets = decoder.drain()
        assertEquals(3, packets.size)
        assertEquals(DhdAdbProtocol.SHELL_V2_STDOUT, packets[0].streamId)
        assertArrayEquals("hello".toByteArray(), packets[0].payload)
        assertEquals(DhdAdbProtocol.SHELL_V2_STDERR, packets[1].streamId)
        assertArrayEquals("warning".toByteArray(), packets[1].payload)
        assertEquals(DhdAdbProtocol.SHELL_V2_EXIT, packets[2].streamId)
        assertArrayEquals(byteArrayOf(0), packets[2].payload)
    }

    @Test
    fun `quotes typed ADB arguments without allowing shell expansion`() {
        assertEquals(
            "'input' 'text' 'O'\"'\"'Reilly%sShop'",
            buildDhdAdbShellCommand(listOf("input", "text", "O'Reilly%sShop")),
        )
    }

    @Test
    fun `rejects empty and multiline ADB arguments`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildDhdAdbShellCommand(emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            quoteDhdAdbShellArgument("line\nbreak")
        }
    }
}
