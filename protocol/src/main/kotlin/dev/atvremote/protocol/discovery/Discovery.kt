package dev.atvremote.protocol.discovery

/** An Apple TV (or other Companion-capable device) found on the local network. */
data class AppleTvDevice(
    val name: String,
    val address: String,
    val port: Int,
    /** Hardware identifier, e.g. "AppleTV14,1". */
    val model: String? = null,
    /** Stable per-device identifier advertised as rpMRtID. */
    val identifier: String? = null,
) {
    /**
     * Key used to store credentials for this device.
     *
     * Deliberately excludes the port: an Apple TV rotates its ephemeral
     * Companion port, so including it would orphan the stored pairing.
     */
    val credentialKey: String get() = identifier ?: address
}

const val COMPANION_SERVICE_TYPE = "_companion-link._tcp"
const val COMPANION_SERVICE = "$COMPANION_SERVICE_TYPE.local."

/**
 * Discovery is platform-specific: the JVM uses jmDNS, Android uses NsdManager.
 * Keeping the protocol module free of either lets both consume it unchanged.
 */
interface DeviceDiscovery {
    suspend fun scan(timeoutMs: Long = 6000): List<AppleTvDevice>
}
