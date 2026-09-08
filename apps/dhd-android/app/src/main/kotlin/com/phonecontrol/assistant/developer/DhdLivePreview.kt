package com.phonecontrol.assistant.developer

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class DhdLivePreviewPhase {
    CONNECTING,
    LIVE,
    ERROR,
    CLOSED,
}

/** Playback state exposed to the app UI and task-display adapter. */
data class DhdLivePreviewState(
    val phase: DhdLivePreviewPhase,
    val message: String? = null,
    val attempt: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
) {
    companion object {
        fun connecting(attempt: Int = 0): DhdLivePreviewState =
            DhdLivePreviewState(
                phase = DhdLivePreviewPhase.CONNECTING,
                attempt = attempt,
            )

        fun live(attempt: Int, width: Int, height: Int): DhdLivePreviewState =
            DhdLivePreviewState(
                phase = DhdLivePreviewPhase.LIVE,
                attempt = attempt,
                width = width,
                height = height,
            )

        fun error(message: String, attempt: Int): DhdLivePreviewState =
            DhdLivePreviewState(
                phase = DhdLivePreviewPhase.ERROR,
                message = message,
                attempt = attempt,
            )

        fun closed(): DhdLivePreviewState =
            DhdLivePreviewState(phase = DhdLivePreviewPhase.CLOSED)
    }
}

/**
 * Decodes the daemon's authenticated AVC stream into the supplied read-only
 * Surface. The stream is phone-local; the companion never sees video bytes.
 *
 * The codec is owned by the decode coroutine. [close] only cancels that
 * coroutine and closes its socket, so MediaCodec stop/release never races a
 * second owner on the Surface-destruction path.
 */
class DhdLivePreviewHandle internal constructor(
    val session: DhdVirtualDisplaySession,
    private val surface: Surface,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val socketReference = AtomicReference<Socket?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateFlow = MutableStateFlow(DhdLivePreviewState.connecting())
    private val decodeJob: Job

    /** Connecting becomes LIVE only after MediaCodec reports a rendered frame. */
    val state: StateFlow<DhdLivePreviewState> = stateFlow.asStateFlow()

    /** Alias for adapters that treat the handle as a playback source. */
    val playbackState: StateFlow<DhdLivePreviewState> = state

    init {
        decodeJob = scope.launch { reconnectingDecodeLoop() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stateFlow.value = DhdLivePreviewState.closed()
        socketReference.getAndSet(null)?.let { socket ->
            runCatching { socket.close() }
        }
        // The decode coroutine owns MediaCodec teardown. Cancellation also
        // wakes its packet reader and input-buffer wait loops.
        decodeJob.cancel()
        scope.cancel()
    }

    private suspend fun reconnectingDecodeLoop() {
        var lastFailure: Throwable? = null
        for (attempt in 1..DhdVirtualDisplayProtocol.MAX_CONNECTION_ATTEMPTS) {
            if (closed.get() || !currentCoroutineContext().isActive) return
            if (!surface.isValid) {
                publishError("The live preview surface is no longer valid.", attempt)
                return
            }

            stateFlow.value = DhdLivePreviewState.connecting(attempt)
            try {
                decodeOneConnection(attempt)
                if (closed.get()) return
                throw IOException("The live preview stream ended.")
            } catch (_: CancellationException) {
                if (closed.get()) return
                throw CancellationException("The live preview decoder was cancelled.")
            } catch (failure: Throwable) {
                if (closed.get()) return
                lastFailure = failure
                if (attempt == DhdVirtualDisplayProtocol.MAX_CONNECTION_ATTEMPTS) {
                    publishError(previewFailureMessage(failure), attempt)
                    return
                }
                delay(DhdVirtualDisplayProtocol.reconnectDelayMs(attempt))
            }
        }

        if (!closed.get() && stateFlow.value.phase != DhdLivePreviewPhase.ERROR) {
            publishError(
                previewFailureMessage(lastFailure ?: IOException("The live preview failed.")),
                DhdVirtualDisplayProtocol.MAX_CONNECTION_ATTEMPTS,
            )
        }
    }

    private suspend fun decodeOneConnection(attempt: Int) {
        val socket = Socket()
        socketReference.set(socket)
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = DhdVirtualDisplayProtocol.STREAM_READ_TIMEOUT_MS
            socket.connect(
                InetSocketAddress("127.0.0.1", session.streamPort),
                DhdVirtualDisplayProtocol.CONNECT_TIMEOUT_MS,
            )
            // The daemon authenticates the stream client before it writes its
            // header. Send the exact binary handshake first, then flush it so
            // the server is not left waiting while the client waits for a
            // header.
            val output = DataOutputStream(socket.getOutputStream())
            DhdVirtualDisplayProtocol.writeClientHandshake(output, session.streamToken)
            output.flush()
            val input = DataInputStream(socket.getInputStream())
            val header = DhdVirtualDisplayProtocol.readStreamHeader(input, session.streamToken)
            validateHeader(header)
            if (!surface.isValid) {
                throw IOException("The live preview surface was destroyed before decoding started.")
            }

            val codec = MediaCodec.createDecoderByType(header.codecMime)
            val sessionActive = AtomicBoolean(true)
            try {
                val format = MediaFormat.createVideoFormat(header.codecMime, header.width, header.height)
                header.csd0?.takeIf(ByteArray::isNotEmpty)?.let {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(it))
                }
                header.csd1?.takeIf(ByteArray::isNotEmpty)?.let {
                    format.setByteBuffer("csd-1", ByteBuffer.wrap(it))
                }
                codec.configure(format, surface, null, 0)
                codec.setOnFrameRenderedListener(
                    object : MediaCodec.OnFrameRenderedListener {
                        override fun onFrameRendered(
                            codec: MediaCodec,
                            presentationTimeUs: Long,
                            nanoTime: Long,
                        ) {
                            if (sessionActive.get() && !closed.get()) {
                                stateFlow.value = DhdLivePreviewState.live(
                                    attempt = attempt,
                                    width = header.width,
                                    height = header.height,
                                )
                            }
                        }
                    },
                    Handler(Looper.getMainLooper()),
                )
                codec.start()
                decodePackets(input, codec)
            } finally {
                sessionActive.set(false)
                // This is the sole MediaCodec owner. close() never touches it.
                runCatching { codec.stop() }
                runCatching { codec.release() }
            }
        } finally {
            socketReference.compareAndSet(socket, null)
            runCatching { socket.close() }
        }
    }

    private suspend fun decodePackets(
        input: DataInputStream,
        codec: MediaCodec,
    ) = coroutineScope {
        val packets = Channel<DhdVirtualDisplayProtocol.Packet>(capacity = 8)
        val readerFailure = AtomicReference<Throwable?>(null)
        val reader = launch(Dispatchers.IO) {
            try {
                while (!closed.get() && currentCoroutineContext().isActive) {
                    val packet = DhdVirtualDisplayProtocol.readPacket(input) ?: break
                    packets.send(packet)
                }
            } catch (failure: CancellationException) {
                if (!closed.get()) readerFailure.set(failure)
            } catch (failure: Throwable) {
                readerFailure.set(failure)
            } finally {
                packets.close()
            }
        }

        try {
            while (!closed.get() && currentCoroutineContext().isActive) {
                // Keep draining while the reader is blocked. This is required
                // for a static screen whose last packet is already queued.
                drainDecoder(codec)
                val received = withTimeoutOrNull(DhdVirtualDisplayProtocol.DRAIN_POLL_MS) {
                    packets.receiveCatching()
                } ?: continue
                if (received.isClosed) {
                    readerFailure.get()?.let { throw it }
                    break
                }
                val packet = received.getOrNull() ?: break
                val inputIndex = waitForInputBuffer(codec)
                if (inputIndex < 0) break
                val inputBuffer = codec.getInputBuffer(inputIndex)
                    ?: throw IOException("The AVC decoder returned no input buffer.")
                if (packet.data.size > inputBuffer.capacity()) {
                    throw IOException("The AVC packet exceeds the decoder input buffer.")
                }
                inputBuffer.clear()
                inputBuffer.put(packet.data)
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    packet.data.size,
                    packet.presentationTimeUs,
                    packet.flags,
                )
            }

            // Give the codec a short drain window after EOF/last packet so a
            // static screen still reaches the Surface even without new input.
            repeat(DhdVirtualDisplayProtocol.FINAL_DRAIN_POLLS) {
                val rendered = drainDecoder(codec)
                if (rendered == 0) delay(DhdVirtualDisplayProtocol.DRAIN_POLL_MS)
            }
        } finally {
            // DataInputStream.readFully can remain blocked until the socket
            // timeout. Close the active connection before joining the reader
            // so Surface destruction and reconnect cancellation are prompt.
            socketReference.get()?.let { socket ->
                runCatching { socket.close() }
            }
            reader.cancelAndJoin()
            packets.cancel()
        }
    }

    private suspend fun waitForInputBuffer(codec: MediaCodec): Int {
        while (!closed.get() && currentCoroutineContext().isActive) {
            val index = codec.dequeueInputBuffer(DhdVirtualDisplayProtocol.CODEC_TIMEOUT_US)
            if (index >= 0) return index
            // Backpressure can require output draining before another input
            // buffer becomes available; do that here instead of timing out.
            drainDecoder(codec)
            delay(DhdVirtualDisplayProtocol.INPUT_BUFFER_RETRY_DELAY_MS)
        }
        return -1
    }

    private fun drainDecoder(
        codec: MediaCodec,
    ): Int {
        var rendered = 0
        val info = MediaCodec.BufferInfo()
        repeat(DhdVirtualDisplayProtocol.MAX_DRAIN_OUTPUTS) {
            val outputIndex = codec.dequeueOutputBuffer(info, 0)
            when {
                outputIndex >= 0 -> {
                    codec.releaseOutputBuffer(outputIndex, true)
                    rendered++
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> return rendered
            }
        }
        return rendered
    }

    private fun validateHeader(header: DhdVirtualDisplayProtocol.StreamHeader) {
        require(header.codecMime == session.codecMime) {
            "The live preview codec does not match the display session."
        }
        require(header.width == session.width && header.height == session.height) {
            "The live preview geometry does not match the display session."
        }
        require(header.width in 1..DhdVirtualDisplayProtocol.MAX_GEOMETRY) {
            "The live preview width is outside the supported range."
        }
        require(header.height in 1..DhdVirtualDisplayProtocol.MAX_GEOMETRY) {
            "The live preview height is outside the supported range."
        }
    }

    private fun publishError(message: String, attempt: Int) {
        if (!closed.get()) stateFlow.value = DhdLivePreviewState.error(message, attempt)
    }

    private fun previewFailureMessage(error: Throwable): String =
        error.message?.trim()?.takeIf(String::isNotEmpty)
            ?: "The live preview stream could not be decoded."
}

internal object DhdVirtualDisplayProtocol {
    const val COMMAND = "dhd-display"
    const val CREATE = "create"
    const val ATTACH = "attach"
    const val DETACH = "detach"
    const val CAPTURE = "capture"
    const val CLOSE = "close"
    const val CLOSE_ALL = "close-all"
    const val CREATED_TYPE = "dhd_display_created"
    const val CODEC_AVC = "video/avc"
    const val STREAM_MAGIC = 0x44485631 // DHV1
    const val STREAM_VERSION = 1
    const val STREAM_READ_TIMEOUT_MS = 30_000
    const val CONNECT_TIMEOUT_MS = 2_000
    const val MAX_CONNECTION_ATTEMPTS = 3
    const val INPUT_BUFFER_RETRY_DELAY_MS = 8L
    const val CODEC_TIMEOUT_US = 20_000L
    const val DRAIN_POLL_MS = 16L
    const val FINAL_DRAIN_POLLS = 12
    const val MAX_DRAIN_OUTPUTS = 32
    const val MAX_GEOMETRY = 4_096

    data class StreamHeader(
        val codecMime: String,
        val width: Int,
        val height: Int,
        val csd0: ByteArray?,
        val csd1: ByteArray?,
    )

    data class Packet(
        val flags: Int,
        val presentationTimeUs: Long,
        val data: ByteArray,
    )

    fun writeClientHandshake(output: DataOutputStream, token: String) {
        val tokenBytes = token.toByteArray(Charsets.UTF_8)
        require(tokenBytes.isNotEmpty() && tokenBytes.size <= 128) {
            "DHD display stream token is outside the supported range."
        }
        output.writeInt(STREAM_MAGIC)
        output.writeInt(STREAM_VERSION)
        output.writeInt(tokenBytes.size)
        output.write(tokenBytes)
    }

    fun readStreamHeader(input: DataInputStream, expectedToken: String): StreamHeader {
        require(input.readInt() == STREAM_MAGIC) { "Invalid DHD display stream magic." }
        require(input.readInt() == STREAM_VERSION) { "Unsupported DHD display stream version." }
        val token = readString(input, 128)
        require(
            MessageDigest.isEqual(
                token.toByteArray(Charsets.UTF_8),
                expectedToken.toByteArray(Charsets.UTF_8),
            ),
        ) { "DHD display stream authentication failed." }
        val codec = readString(input, 64)
        require(codec == CODEC_AVC) { "Unsupported DHD display stream codec: $codec" }
        val width = input.readInt()
        val height = input.readInt()
        require(width in 1..MAX_GEOMETRY && height in 1..MAX_GEOMETRY) {
            "Invalid DHD display stream geometry."
        }
        val csd0 = readBytes(input, 1 shl 20)
        val csd1 = readBytes(input, 1 shl 20)
        return StreamHeader(codec, width, height, csd0, csd1)
    }

    fun readPacket(input: DataInputStream): Packet? {
        val flags: Int
        try {
            flags = input.readInt()
        } catch (_: EOFException) {
            return null
        }
        val pts = input.readLong()
        val data = readBytes(input, 4 * 1024 * 1024) ?: return null
        return Packet(flags, pts, data)
    }

    private fun readString(input: DataInputStream, maxBytes: Int): String {
        val length = input.readInt()
        require(length in 0..maxBytes) { "DHD display stream string is too long." }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readBytes(input: DataInputStream, maxBytes: Int): ByteArray? {
        val length = input.readInt()
        if (length == -1) return null
        require(length in 0..maxBytes) { "DHD display stream packet is too large." }
        if (length == 0) return ByteArray(0)
        return ByteArray(length).also(input::readFully)
    }

    fun reconnectDelayMs(attempt: Int): Long =
        if (attempt >= 5) 2_000L else (100L shl (attempt - 1).coerceAtLeast(0))
}
