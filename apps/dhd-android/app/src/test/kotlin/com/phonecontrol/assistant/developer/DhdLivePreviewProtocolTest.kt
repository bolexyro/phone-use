package com.phonecontrol.assistant.developer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DhdLivePreviewProtocolTest {
    @Test
    fun `writes client handshake with the daemon wire format`() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { stream ->
                DhdVirtualDisplayProtocol.writeClientHandshake(stream, "secret")
                stream.flush()
            }
        }.toByteArray()

        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertEquals(DhdVirtualDisplayProtocol.STREAM_MAGIC, input.readInt())
            assertEquals(DhdVirtualDisplayProtocol.STREAM_VERSION, input.readInt())
            assertEquals(6, input.readInt())
            val token = ByteArray(6)
            input.readFully(token)
            assertEquals("secret", String(token, Charsets.UTF_8))
        }
    }

    @Test
    fun `accepts daemon headers with absent codec configuration`() {
        val header = DhdVirtualDisplayProtocol.readStreamHeader(
            input = DataInputStream(ByteArrayInputStream(headerBytes(token = "secret", csd0Length = -1, csd1Length = -1))),
            expectedToken = "secret",
        )

        assertEquals(DhdVirtualDisplayProtocol.CODEC_AVC, header.codecMime)
        assertEquals(720, header.width)
        assertEquals(1560, header.height)
        assertNull(header.csd0)
        assertNull(header.csd1)
    }

    @Test
    fun `rejects a header with the wrong stream token`() {
        assertThrows(IllegalArgumentException::class.java) {
            DhdVirtualDisplayProtocol.readStreamHeader(
                input = DataInputStream(ByteArrayInputStream(headerBytes(token = "wrong"))),
                expectedToken = "secret",
            )
        }
    }

    @Test
    fun `treats a nullable packet payload marker as end of stream`() {
        val bytes = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).apply {
                writeInt(0)
                writeLong(1L)
                writeInt(-1)
            }
        }

        assertNull(DhdVirtualDisplayProtocol.readPacket(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))))
    }

    @Test
    fun `reconnect delay is bounded`() {
        assertEquals(100L, DhdVirtualDisplayProtocol.reconnectDelayMs(1))
        assertEquals(200L, DhdVirtualDisplayProtocol.reconnectDelayMs(2))
        assertEquals(2_000L, DhdVirtualDisplayProtocol.reconnectDelayMs(20))
    }

    private fun headerBytes(
        token: String,
        csd0Length: Int = 0,
        csd1Length: Int = 0,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).apply {
            writeInt(DhdVirtualDisplayProtocol.STREAM_MAGIC)
            writeInt(DhdVirtualDisplayProtocol.STREAM_VERSION)
            writeString(token)
            writeString(DhdVirtualDisplayProtocol.CODEC_AVC)
            writeInt(720)
            writeInt(1560)
            writeInt(csd0Length)
            if (csd0Length > 0) write(ByteArray(csd0Length))
            writeInt(csd1Length)
            if (csd1Length > 0) write(ByteArray(csd1Length))
        }
    }.toByteArray()

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }
}
