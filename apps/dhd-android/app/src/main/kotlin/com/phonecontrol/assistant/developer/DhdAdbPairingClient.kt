package com.phonecontrol.assistant.developer

import android.os.Build
import android.util.Log
import org.conscrypt.Conscrypt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "DhdAdbPairing"

/** Pairing failure that specifically means the code entered by the user was wrong. */
class DhdAdbInvalidPairingCodeException : Exception("The Wireless Debugging pairing code was rejected.")

private class DhdAdbPairingContext private constructor(private val nativePtr: Long) {
    val message: ByteArray
        get() = nativeMessage(nativePtr)

    fun initCipher(theirMessage: ByteArray): Boolean =
        nativeInitCipher(nativePtr, theirMessage)

    fun encrypt(input: ByteArray): ByteArray? = nativeEncrypt(nativePtr, input)

    fun decrypt(input: ByteArray): ByteArray? = nativeDecrypt(nativePtr, input)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMessage(nativePtr: Long): ByteArray
    private external fun nativeInitCipher(nativePtr: Long, theirMessage: ByteArray): Boolean
    private external fun nativeEncrypt(nativePtr: Long, input: ByteArray): ByteArray?
    private external fun nativeDecrypt(nativePtr: Long, input: ByteArray): ByteArray?
    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        init {
            try {
                Log.i(TAG, "Loading native ADB pairing library")
                System.loadLibrary("dhd_adb")
                Log.i(TAG, "Native ADB pairing library loaded")
            } catch (error: Throwable) {
                Log.e(TAG, "Native ADB pairing library could not load", error)
                throw error
            }
        }

        fun create(password: ByteArray): DhdAdbPairingContext? {
            val pointer = nativeConstructor(true, password)
            if (pointer == 0L) Log.e(TAG, "Native ADB pairing context creation returned null")
            return pointer.takeIf { it != 0L }?.let(::DhdAdbPairingContext)
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}

/** ADB TLS pairing client for Android 11 and newer. */
internal class DhdAdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val key: DhdAdbKey,
) : Closeable {
    private var socket: Socket? = null
    private var tlsSocket: SSLSocket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private var pairingContext: DhdAdbPairingContext? = null

    fun start(): Boolean {
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "Wireless Debugging pairing requires Android 11 or newer."
        }
        require(pairingCode.matches(Regex("\\d{6}"))) {
            "The Wireless Debugging pairing code must contain six digits."
        }
        setupTlsConnection()
        val context = pairingContext ?: error("ADB pairing context was not created.")

        val ourMessage = context.message
        writePacket(PairingPacketType.SPAKE2_MESSAGE, ourMessage)
        val theirMessage = readPacket(PairingPacketType.SPAKE2_MESSAGE)
        if (!context.initCipher(theirMessage)) return false

        val peerInfo = ByteBuffer.allocate(MAX_PEER_INFO_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
            put(PeerInfoType.ADB_RSA_PUBLIC_KEY.value)
            put(key.adbPublicKey.copyOf(MAX_PEER_INFO_SIZE - 1))
        }.array()
        val encryptedPeerInfo = context.encrypt(peerInfo) ?: return false
        writePacket(PairingPacketType.PEER_INFO, encryptedPeerInfo)

        val encryptedTheirPeerInfo = readPacket(PairingPacketType.PEER_INFO)
        val decrypted = context.decrypt(encryptedTheirPeerInfo)
            ?: throw DhdAdbInvalidPairingCodeException()
        if (decrypted.size != MAX_PEER_INFO_SIZE) return false
        return true
    }

    private fun setupTlsConnection() {
        val raw = Socket()
        raw.tcpNoDelay = true
        raw.soTimeout = PAIRING_READ_TIMEOUT_MS
        raw.connect(InetSocketAddress(host, port), PAIRING_CONNECT_TIMEOUT_MS)
        socket = raw
        val secure = key.sslContext.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        secure.useClientMode = true
        secure.startHandshake()
        tlsSocket = secure
        input = DataInputStream(secure.inputStream)
        output = DataOutputStream(secure.outputStream)

        val keyMaterial = Conscrypt.exportKeyingMaterial(
            secure,
            EXPORTED_KEY_LABEL,
            null,
            EXPORTED_KEY_SIZE,
        )
        val password = pairingCode.toByteArray(Charsets.UTF_8) + keyMaterial
        Log.i(TAG, "Creating native ADB pairing context")
        pairingContext = DhdAdbPairingContext.create(password)
            ?: error("The native ADB pairing implementation could not start.")
    }

    private fun writePacket(type: PairingPacketType, payload: ByteArray) {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD_SIZE) {
            "ADB pairing payload size is invalid."
        }
        val stream = output ?: error("ADB pairing connection is not open.")
        val header = ByteBuffer.allocate(PAIRING_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
            .put(CURRENT_HEADER_VERSION)
            .put(type.value)
            .putInt(payload.size)
            .array()
        stream.write(header)
        stream.write(payload)
        stream.flush()
    }

    private fun readPacket(expected: PairingPacketType): ByteArray {
        val stream = input ?: error("ADB pairing connection is not open.")
        val headerBytes = ByteArray(PAIRING_HEADER_SIZE)
        stream.readFully(headerBytes)
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        val version = header.get()
        val type = header.get()
        val payloadSize = header.int
        require(version == CURRENT_HEADER_VERSION) { "ADB pairing protocol version is unsupported." }
        require(type == expected.value) { "ADB pairing packet type is unexpected." }
        require(payloadSize in 1..MAX_PAYLOAD_SIZE) { "ADB pairing payload size is invalid." }
        return ByteArray(payloadSize).also(stream::readFully)
    }

    override fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { tlsSocket?.close() }
        runCatching { socket?.close() }
        pairingContext?.destroy()
        pairingContext = null
        input = null
        output = null
        tlsSocket = null
        socket = null
    }

    private enum class PairingPacketType(val value: Byte) {
        SPAKE2_MESSAGE(0),
        PEER_INFO(1),
    }

    private enum class PeerInfoType(val value: Byte) {
        ADB_RSA_PUBLIC_KEY(0),
    }

    private companion object {
        const val CURRENT_HEADER_VERSION: Byte = 1
        const val PAIRING_HEADER_SIZE = 6
        const val MAX_PEER_INFO_SIZE = 8192
        const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2
        const val EXPORTED_KEY_LABEL = "adb-label\u0000"
        const val EXPORTED_KEY_SIZE = 64
        const val PAIRING_CONNECT_TIMEOUT_MS = 2_000
        const val PAIRING_READ_TIMEOUT_MS = 10_000
    }
}
