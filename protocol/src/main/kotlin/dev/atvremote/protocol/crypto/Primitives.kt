package dev.atvremote.protocol.crypto

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Low-level crypto primitives.
 *
 * These deliberately use BouncyCastle's lightweight API rather than JCE. On
 * Android the platform ships its own stripped-down BouncyCastle provider, and
 * registering a second one causes class-name collisions; going straight to the
 * engine classes sidesteps that entirely and keeps this module usable unchanged
 * on both the JVM and Android.
 */
object Crypto {

    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** HKDF-SHA512, always expanding to 32 bytes as HAP requires. */
    fun hkdf(salt: String, info: String, secret: ByteArray, length: Int = 32): ByteArray {
        val gen = HKDFBytesGenerator(SHA512Digest())
        gen.init(
            HKDFParameters(
                secret,
                salt.toByteArray(Charsets.UTF_8),
                info.toByteArray(Charsets.UTF_8)
            )
        )
        return ByteArray(length).also { gen.generateBytes(it, 0, length) }
    }

    fun sha512(vararg parts: ByteArray): ByteArray {
        val d = SHA512Digest()
        parts.forEach { d.update(it, 0, it.size) }
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }

    // ------------------------------------------------------------- Ed25519

    fun ed25519PublicKey(privateKey: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    fun ed25519Sign(privateKey: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKey, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    // -------------------------------------------------------------- X25519

    fun x25519PublicKey(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    fun x25519SharedSecret(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val priv = X25519PrivateKeyParameters(privateKey, 0)
        val pub = X25519PublicKeyParameters(peerPublicKey, 0)
        return ByteArray(X25519PrivateKeyParameters.SECRET_SIZE).also {
            priv.generateSecret(pub, it, 0)
        }
    }
}

/**
 * ChaCha20-Poly1305 with the nonce conventions used across Apple's pairing
 * protocols.
 *
 * Two nonce styles appear: an explicit 8-byte ASCII label (e.g. "PS-Msg05")
 * zero-padded on the left to 12 bytes, and an implicit little-endian counter
 * that increments per message once a session is live. Separate counters are
 * kept for each direction.
 */
class ChaChaCipher(
    outKey: ByteArray,
    inKey: ByteArray,
    private val nonceLength: Int = 8,
) {
    private val outKeyParam = KeyParameter(outKey)
    private val inKeyParam = KeyParameter(inKey)
    private var outCounter = 0L
    private var inCounter = 0L

    private fun padNonce(nonce: ByteArray): ByteArray =
        if (nonce.size >= NONCE_LENGTH) nonce
        else ByteArray(NONCE_LENGTH - nonce.size) + nonce

    private fun counterNonce(counter: Long): ByteArray {
        // NB: Kotlin's shr on Long masks the shift to 6 bits, so shifting by
        // >= 64 wraps around and would leak counter bytes into the high nonce
        // bytes instead of zeroing them.
        val n = ByteArray(nonceLength) { i ->
            if (i >= 8) 0 else ((counter shr (8 * i)) and 0xFF).toByte()
        }
        return if (nonceLength == NONCE_LENGTH) n else padNonce(n)
    }

    fun encrypt(data: ByteArray, nonce: ByteArray? = null, aad: ByteArray? = null): ByteArray {
        val n = if (nonce == null) counterNonce(outCounter++) else padNonce(nonce)
        return process(true, outKeyParam, n, data, aad)
    }

    fun decrypt(data: ByteArray, nonce: ByteArray? = null, aad: ByteArray? = null): ByteArray {
        val n = if (nonce == null) counterNonce(inCounter++) else padNonce(nonce)
        return process(false, inKeyParam, n, data, aad)
    }

    private fun process(
        forEncryption: Boolean,
        key: KeyParameter,
        nonce: ByteArray,
        data: ByteArray,
        aad: ByteArray?,
    ): ByteArray {
        val engine = ChaCha20Poly1305()
        engine.init(forEncryption, AEADParameters(key, TAG_BITS, nonce, aad))
        val out = ByteArray(engine.getOutputSize(data.size))
        var len = engine.processBytes(data, 0, data.size, out, 0)
        len += engine.doFinal(out, len)
        return if (len == out.size) out else out.copyOf(len)
    }

    companion object {
        const val NONCE_LENGTH = 12
        const val TAG_BITS = 128
        const val TAG_LENGTH = 16

        /** Cipher using an 8-byte counter nonce, used during pairing. */
        fun pairing(key: ByteArray) = ChaChaCipher(key, key, nonceLength = 8)
    }
}
