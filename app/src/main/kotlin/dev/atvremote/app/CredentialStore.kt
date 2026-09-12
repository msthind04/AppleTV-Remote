package dev.atvremote.app

import android.content.Context
import dev.atvremote.protocol.discovery.AppleTvDevice
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

    // The last device connected to, so the list shows something the instant
    // the app opens instead of waiting on discovery.
    fun saveLastDevice(device: AppleTvDevice) {
        prefs.edit()
            .putString("last-name", device.name)
            .putString("last-address", device.address)
            .putInt("last-port", device.port)
            .putString("last-model", device.model)
            .putString("last-identifier", device.identifier)
            .apply()
    }

    fun loadLastDevice(): AppleTvDevice? {
        val name = prefs.getString("last-name", null) ?: return null
        val address = prefs.getString("last-address", null) ?: return null
        return AppleTvDevice(
            name = name,
            address = address,
            port = prefs.getInt("last-port", 0),
            model = prefs.getString("last-model", null),
            identifier = prefs.getString("last-identifier", null),
        )
    }

    fun forget(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun isPaired(key: String): Boolean = prefs.contains(key)

    /**
     * Every device that has a companion pairing, excluding the AirPlay
     * second-pairing entries and the last-device note.
     */
    fun pairedKeys(): Set<String> = prefs.all.keys
        .filter { !it.endsWith("-airplay") && !it.startsWith("last-") }
        .toSet()

    // Now-playing needs a second, independent AirPlay pairing with its own PIN.
    fun airplayKey(key: String): String = "$key-airplay"
    fun saveAirPlay(key: String, credentials: Credentials) = save(airplayKey(key), credentials)
    fun loadAirPlay(key: String): Credentials? = load(airplayKey(key))
    fun isAirPlayPaired(key: String): Boolean = isPaired(airplayKey(key))
}
