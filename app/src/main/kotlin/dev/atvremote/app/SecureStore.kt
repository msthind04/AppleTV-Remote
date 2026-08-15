package dev.atvremote.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Envelope encryption for values held in SharedPreferences, backed by the
 * Android Keystore.
 *
 * Pairing credentials grant complete control of an Apple TV, so they are not
 * stored as plaintext even inside app-private storage. The key material never
 * leaves the Keystore — on devices with a secure element or TEE it is not
 * extractable at all, so a copy of the app's data directory is not enough to
 * recover the credentials.
 *
 * Deliberately hand-rolled rather than using androidx.security:security-crypto,
 * which is deprecated and adds a dependency for what amounts to a few lines of
 * AES-GCM.
 */
object SecureStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "atv-credentials"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user authentication: the remote must reconnect in the
                // background without prompting for a device unlock.
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generator.generateKey()
    }

    /** Encrypt to a self-contained base64 blob of IV + ciphertext. */
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /**
     * Decrypt a blob produced by [encrypt], or return null if it is not one —
     * which is how legacy plaintext entries are detected and migrated.
     */
    fun decrypt(blob: String): String? = runCatching {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        require(raw.size > IV_LENGTH) { "ciphertext too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH),
        )
        String(
            cipher.doFinal(raw, IV_LENGTH, raw.size - IV_LENGTH),
            Charsets.UTF_8,
        )
    }.getOrNull()
}
