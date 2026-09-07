package com.phonecontrol.assistant.developer

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.Key
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.conscrypt.Conscrypt

/**
 * Persistent ADB identity owned by DHD.
 *
 * The private key is encrypted with an Android Keystore AES key. Only the
 * public half is sent during the Wireless Debugging pairing handshake.
 */
class DhdAdbKey private constructor(
    private val keyStore: DhdAdbKeyStore,
    private val displayName: String,
) {
    private val encryptionKey: Key = getOrCreateEncryptionKey()
    private val privateKey: RSAPrivateKey = getOrCreatePrivateKey()
    private val publicKey: RSAPublicKey = KeyFactory.getInstance("RSA")
        .generatePublic(
            RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4),
        ) as RSAPublicKey

    private val certificate: X509Certificate = createCertificate(privateKey, publicKey)

    /** ADB's binary RSA public-key format, followed by the display name. */
    val adbPublicKey: ByteArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
        publicKey.toAdbEncoded(displayName)
    }

    /** Client TLS identity used by the Wireless Debugging ADB endpoint. */
    val sslContext: SSLContext by lazy(LazyThreadSafetyMode.PUBLICATION) {
        SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider()).apply {
            init(arrayOf(clientKeyManager()), arrayOf(TRUST_ALL_MANAGER), SecureRandom())
        }
    }

    private fun clientKeyManager(): X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
        private val alias = "dhd-adb"

        override fun chooseClientAlias(
            keyTypes: Array<out String>,
            issuers: Array<out java.security.Principal>?,
            socket: java.net.Socket?,
        ): String? = if (keyTypes.any { it == "RSA" }) alias else null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            if (alias == this.alias) arrayOf(certificate) else null

        override fun getPrivateKey(alias: String?): PrivateKey? =
            if (alias == this.alias) privateKey else null

        override fun getClientAliases(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
        ): Array<String>? = null

        override fun getServerAliases(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
        ): Array<String>? = null

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            socket: java.net.Socket?,
        ): String? = null

        override fun chooseEngineClientAlias(
            keyTypes: Array<out String>?,
            issuers: Array<out java.security.Principal>?,
            engine: SSLEngine?,
        ): String? = if (keyTypes?.any { it == "RSA" } == true) alias else null

        override fun chooseEngineServerAlias(
            keyType: String?,
            issuers: Array<out java.security.Principal>?,
            engine: SSLEngine?,
        ): String? = null
    }

    private fun getOrCreateEncryptionKey(): Key {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.getKey(ENCRYPTION_KEY_ALIAS, null)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            ENCRYPTION_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        val aad = AAD
        keyStore.get()?.let { encrypted ->
            runCatching {
                val plaintext = decrypt(encrypted, aad)
                KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            }.getOrNull()?.let { return it }
        }

        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        }.generateKeyPair()
        val generated = keyPair.private as RSAPrivateKey
        encrypt(generated.encoded, aad)?.let(keyStore::put)
        return generated
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        ByteArray(cipher.iv.size + encrypted.size).also {
            cipher.iv.copyInto(it)
            encrypted.copyInto(it, cipher.iv.size)
        }
    }.getOrNull()

    private fun decrypt(encrypted: ByteArray, aad: ByteArray): ByteArray {
        require(encrypted.size > IV_SIZE_BYTES + TAG_SIZE_BYTES) { "Stored DHD ADB key is invalid." }
        val iv = encrypted.copyOfRange(0, IV_SIZE_BYTES)
        val ciphertext = encrypted.copyOfRange(IV_SIZE_BYTES, encrypted.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey,
            GCMParameterSpec(TAG_SIZE_BYTES * 8, iv),
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }

    private fun createCertificate(
        privateKey: RSAPrivateKey,
        publicKey: RSAPublicKey,
    ): X509Certificate {
        val certificateHolder = X509v3CertificateBuilder(
            X500Name("CN=DHD ADB"),
            BigInteger.ONE,
            Date(0),
            Date(System.currentTimeMillis() + CERTIFICATE_LIFETIME_MS),
            X500Name("CN=DHD ADB"),
            SubjectPublicKeyInfo.getInstance(publicKey.encoded),
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(privateKey))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificateHolder.encoded)) as X509Certificate
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "dhd_adb_encryption_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BYTES = 16
        private const val CERTIFICATE_LIFETIME_MS = 1000L * 60L * 60L * 24L * 365L * 25L
        private val AAD = "dhd-adb-key".toByteArray(Charsets.UTF_8)
        private val TRUST_ALL_MANAGER = @Suppress("CustomX509TrustManager") object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: java.net.Socket?,
            ) = Unit
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?,
            ) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: java.net.Socket?,
            ) = Unit
            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?,
            ) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        fun from(context: Context): DhdAdbKey = DhdAdbKey(
            PreferenceDhdAdbKeyStore(
                context.getSharedPreferences(KEY_STORE_NAME, Context.MODE_PRIVATE),
            ),
            displayName = "DHD",
        )

        private const val KEY_STORE_NAME = "dhd_adb_identity"
    }
}

interface DhdAdbKeyStore {
    fun put(bytes: ByteArray)
    fun get(): ByteArray?
}

class PreferenceDhdAdbKeyStore(
    private val preferences: SharedPreferences,
) : DhdAdbKeyStore {
    override fun put(bytes: ByteArray) {
        preferences.edit()
            .putString(KEY_PRIVATE_KEY, Base64.encodeToString(bytes, Base64.NO_WRAP))
            .apply()
    }

    override fun get(): ByteArray? = preferences.getString(KEY_PRIVATE_KEY, null)
        ?.let { encoded -> runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull() }

    private companion object {
        const val KEY_PRIVATE_KEY = "encrypted_private_key"
    }
}

private fun RSAPublicKey.toAdbEncoded(name: String): ByteArray {
    val buffer = ByteBuffer.allocate(ADB_PUBLIC_KEY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ADB_PUBLIC_KEY_SIZE_WORDS)
    buffer.putInt(modulus.remainder(TWO_TO_32).modInverse(TWO_TO_32).negate().toInt())
    modulus.toLittleEndianWords().forEach(buffer::putInt)
    TWO_TO_RSA_BITS.modPow(BigInteger.TWO, modulus).toLittleEndianWords().forEach(buffer::putInt)
    buffer.putInt(publicExponent.toInt())

    val encoded = Base64.encode(buffer.array(), Base64.NO_WRAP)
    return (String(encoded, Charsets.US_ASCII) + " $name\u0000").toByteArray(Charsets.US_ASCII)
}

private fun BigInteger.toLittleEndianWords(): IntArray {
    val words = IntArray(ADB_PUBLIC_KEY_SIZE_WORDS)
    var value = this
    repeat(ADB_PUBLIC_KEY_SIZE_WORDS) { index ->
        val remainder = value.divideAndRemainder(TWO_TO_32)
        value = remainder[0]
        words[index] = remainder[1].toInt()
    }
    return words
}

private const val ADB_PUBLIC_KEY_SIZE = 524
private const val ADB_PUBLIC_KEY_SIZE_WORDS = 64
private val TWO_TO_32 = BigInteger.ONE.shiftLeft(32)
private val TWO_TO_RSA_BITS = BigInteger.ONE.shiftLeft(2048)
