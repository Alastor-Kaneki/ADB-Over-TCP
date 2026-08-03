package dev.alastorkaneki.adbovertcp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AdbMdnsDiscovery(context: Context) {
    data class Endpoint(val host: String, val port: Int) {
        val address: String
            get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
    }

    private val nsdManager = context.getSystemService(NsdManager::class.java)
    private val multicastLock = context.getSystemService(WifiManager::class.java)
        .createMulticastLock("adb-over-tcp-mdns")
        .apply { setReferenceCounted(false) }

    suspend fun awaitPairingService(timeoutMs: Long = 120_000): Endpoint =
        awaitService(PAIRING_SERVICE_TYPE, timeoutMs)

    suspend fun awaitConnectionService(timeoutMs: Long = 60_000): Endpoint =
        awaitService(CONNECTION_SERVICE_TYPE, timeoutMs)

    private suspend fun awaitService(serviceType: String, timeoutMs: Long): Endpoint =
        withTimeout(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val resolving = AtomicBoolean(false)
                lateinit var listener: NsdManager.DiscoveryListener

                fun stopDiscovery() {
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                    if (multicastLock.isHeld) runCatching { multicastLock.release() }
                }

                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(regType: String) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        if (!serviceInfo.serviceType.startsWith(serviceType.removeSuffix("."))) return
                        if (!resolving.compareAndSet(false, true)) return

                        @Suppress("DEPRECATION")
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                resolving.set(false)
                            }

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val host = resolved.host?.hostAddress
                                val port = resolved.port
                                if (host.isNullOrBlank() || port !in 1..65535) {
                                    resolving.set(false)
                                    return
                                }
                                if (continuation.isActive) {
                                    stopDiscovery()
                                    continuation.resume(Endpoint(host, port))
                                }
                            }
                        })
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                    override fun onDiscoveryStopped(serviceType: String) = Unit

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        stopDiscovery()
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                IllegalStateException("mDNS discovery failed to start: $errorCode")
                            )
                        }
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }

                continuation.invokeOnCancellation { stopDiscovery() }
                runCatching {
                    if (!multicastLock.isHeld) multicastLock.acquire()
                    nsdManager.discoverServices(
                        serviceType,
                        NsdManager.PROTOCOL_DNS_SD,
                        listener
                    )
                }.onFailure {
                    stopDiscovery()
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
            }
        }

    companion object {
        const val PAIRING_SERVICE_TYPE = "_adb-tls-pairing._tcp."
        const val CONNECTION_SERVICE_TYPE = "_adb-tls-connect._tcp."
    }
}
