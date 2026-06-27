package com.example.p2p

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Prioritize Wi-Fi and Hotspot interfaces
            val sortedInterfaces = interfaces.sortedWith { o1, o2 ->
                val n1 = o1.name.lowercase()
                val n2 = o2.name.lowercase()
                val isWlan1 = n1.contains("wlan") || n1.contains("ap")
                val isWlan2 = n2.contains("wlan") || n2.contains("ap")
                when {
                    isWlan1 && !isWlan2 -> -1
                    !isWlan1 && isWlan2 -> 1
                    else -> n1.compareTo(n2)
                }
            }

            for (networkInterface in sortedInterfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    fun getAllLocalIpAddresses(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue
                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        list.add(Pair(networkInterface.displayName, address.hostAddress))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
