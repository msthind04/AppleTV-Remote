package dev.atvremote.app

import android.content.Context
import dev.atvremote.protocol.hap.Credentials

/**
 * Persists pairing credentials per device.
 *
 * These live in app-private storage, which is sandboxed from other apps. They
 * are stored in the clear there, which is the same posture as the desktop
 * reference implementation; anyone with root or a backup of the app's data
 * directory could reuse them, so backups are disabled in the manifest.
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pairings", Context.MODE_PRIVATE)

    fun save(key: String, credentials: Credentials) {
        prefs.edit().putString(key, credentials.serialize()).apply()
    }

    fun load(key: String): Credentials? =
        prefs.getString(key, null)?.let { runCatching { Credentials.parse(it) }.getOrNull() }

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
