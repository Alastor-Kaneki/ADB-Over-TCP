package dev.alastorkaneki.adbovertcp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.NetworkInterface

object NetworkClassifier {
    fun describe(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val active = when {
            caps == null -> "No active internet transport"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "External transport: Wi-Fi client"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "External transport: mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "External transport: VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "External transport: Ethernet"
            else -> "External transport: other"
        }

        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .mapNotNull { iface ->
                    val addresses = iface.inetAddresses.toList()
                        .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                        .joinToString { it.hostAddress.orEmpty().substringBefore('%') }
                    if (addresses.isBlank()) null else "${classify(iface.name)} ${iface.name}: $addresses"
                }
        }.getOrDefault(emptyList())

        return buildString {
            append(active)
            if (interfaces.isNotEmpty()) append("\nInternal/device interfaces:\n${interfaces.joinToString("\n")}")
            append("\nADB target: loopback 127.0.0.1:5555")
        }
    }

    private fun classify(name: String): String = when {
        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> "[cellular]"
        name.contains("wlan") || name.contains("wifi") -> "[wifi/hotspot]"
        name.contains("ap") || name.contains("swlan") -> "[hotspot]"
        name.contains("rndis") || name.contains("usb") -> "[USB tether]"
        name.contains("tun") -> "[VPN]"
        else -> "[network]"
    }
}
