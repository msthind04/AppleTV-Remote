package dev.atvremote.app

import android.content.Context
import dev.atvremote.protocol.hap.Credentials

/**
 * Persists pairing credentials per device.
 *
 * Values are encrypted with a key held in the Android Keystore (see
 * [SecureStore]) before being written to app-private storage. Credentials
 * grant complete control of an Apple TV, so app-private storage alone is not
 * treated as sufficient: on a rooted device, or from a backup of the data
 * directory, plaintext would be trivially recoverable.
 *
 * Backups are additionally disabled in the manifest, since Keystore-wrapped
 * ciphertext cannot be decrypted after a restore onto different hardware.
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pairings", Context.MODE_PRIVATE)

    fun save(key: String, credentials: Credentials) {
        prefs.edit().putString(key, SecureStore.encrypt(credentials.serialize())).apply()
    }

    fun load(key: String): Credentials? {
        val stored = prefs.getString(key, null) ?: return null

        SecureStore.decrypt(stored)?.let { plaintext ->
            return runCatching { Credentials.parse(plaintext) }.getOrNull()
        }

        // Written by a build that predates encryption: parse it, then rewrite
        // it encrypted so the plaintext does not survive.
        val legacy = runCatching { Credentials.parse(stored) }.getOrNull() ?: return null
        save(key, legacy)
        return legacy
    }

    fun forget(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun isPaired(key: String): Boolean = prefs.contains(key)

    // Now-playing needs a second, independent AirPlay pairing with its own PIN.
    fun airplayKey(key: String): String = "$key-airplay"
    fun saveAirPlay(key: String, credentials: Credentials) = save(airplayKey(key), credentials)
    fun loadAirPlay(key: String): Credentials? = load(airplayKey(key))
    fun isAirPlayPaired(key: String): Boolean = isPaired(airplayKey(key))
}
