package dev.atvremote.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dev.atvremote.protocol.discovery.AppleTvDevice
import dev.atvremote.protocol.discovery.COMPANION_SERVICE_TYPE
import dev.atvremote.protocol.discovery.DeviceDiscovery
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

/**
 * Service discovery backed by Android's NsdManager.
 *
 * Discovery and resolution are deliberately separated: on older platform
 * versions overlapping `resolveService` calls fail with ALREADY_ACTIVE, so
 * services are collected first and then resolved one at a time.
 */
class NsdDiscovery(context: Context) : DeviceDiscovery {

    private val nsd = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as NsdManager

    override suspend fun scan(timeoutMs: Long): List<AppleTvDevice> = scan(timeoutMs) {}

    /**
     * Reports each device the moment it resolves, so the list fills in while
     * discovery is still running instead of arriving all at once at the end.
     */
    suspend fun scan(timeoutMs: Long, onResolved: (AppleTvDevice) -> Unit): List<AppleTvDevice> = withContext(Dispatchers.IO) {
        val found = ConcurrentLinkedQueue<NsdServiceInfo>()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { found.add(serviceInfo) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        try {
            nsd.discoverServices(COMPANION_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            delay(timeoutMs)
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }

        // Resolve sequentially and skip anything that does not answer in time.
        found.distinctBy { it.serviceName }.mapNotNull { info ->
            withTimeoutOrNull(3000) { resolve(info) }?.also { onResolved(it) }
        }
    }

    private suspend fun resolve(info: NsdServiceInfo): AppleTvDevice? =
        suspendCancellableCoroutine { cont: CancellableContinuation<AppleTvDevice?> ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    if (!cont.isActive) return
                    val host = serviceInfo.host?.hostAddress
                    cont.resume(
                        if (host == null) null else AppleTvDevice(
                            name = serviceInfo.serviceName,
                            address = host,
                            port = serviceInfo.port,
                            model = serviceInfo.txt("rpMd"),
                            identifier = serviceInfo.txt("rpMRtID"),
                        )
                    )
                }
            })
        }

    private fun NsdServiceInfo.txt(key: String): String? =
        attributes[key]?.toString(Charsets.UTF_8)
}
