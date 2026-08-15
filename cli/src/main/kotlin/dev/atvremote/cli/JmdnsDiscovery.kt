package dev.atvremote.cli

import dev.atvremote.protocol.discovery.AppleTvDevice
import dev.atvremote.protocol.discovery.COMPANION_SERVICE
import dev.atvremote.protocol.discovery.DeviceDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** jmDNS-backed discovery for desktop JVM use. */
object JmdnsDiscovery : DeviceDiscovery {

    override suspend fun scan(timeoutMs: Long): List<AppleTvDevice> = withContext(Dispatchers.IO) {
        val jmdns = JmDNS.create()
        try {
            // Blocking list() issues the query and waits for responses to
            // accumulate, which is exactly one-shot scan semantics.
            jmdns.list(COMPANION_SERVICE, timeoutMs).mapNotNull { it.toDevice() }
        } finally {
            runCatching { jmdns.close() }
        }
    }

    private fun ServiceInfo.toDevice(): AppleTvDevice? {
        val address = inet4Addresses.firstOrNull()?.hostAddress
            ?: inetAddresses.firstOrNull()?.hostAddress
            ?: return null
        return AppleTvDevice(
            name = name,
            address = address,
            port = port,
            model = getPropertyString("rpMd"),
            identifier = getPropertyString("rpMRtID"),
        )
    }
}
