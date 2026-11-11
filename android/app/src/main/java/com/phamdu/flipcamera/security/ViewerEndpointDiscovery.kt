package com.phamdu.flipcamera.security

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress

/** Supplies the exact hostnames/IPs that pairing can advertise and the TLS cert can cover. */
internal object ViewerEndpointDiscovery {
    private val stableNames = setOf("localhost", "127.0.0.1")

    fun snapshot(context: Context): ViewerEndpointSnapshot {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        return snapshot(context, listOfNotNull(connectivity.activeNetwork))
    }

    fun snapshot(context: Context, networks: Iterable<Network>): ViewerEndpointSnapshot {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        val lanAddresses = networks.asSequence()
            .flatMap { network ->
                val capabilities = connectivity.getNetworkCapabilities(network) ?: return@flatMap emptySequence()
                val eligible: (InetAddress) -> Boolean = when {
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> return@flatMap emptySequence()
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> ::isTailnetAddress
                    capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> ::isLanAddress
                    else -> return@flatMap emptySequence()
                }
                connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().asSequence()
                    .map { it.address }
                    .filter(eligible)
            }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .toSet()
        return snapshot(lanAddresses)
    }

    private fun snapshot(lanAddresses: Set<String>): ViewerEndpointSnapshot =
        ViewerEndpointSnapshot(
            lanAddresses,
            (stableNames + lanAddresses).map(::normalizeViewerHost).toSet(),
        )

    fun advertisedHosts(context: Context): Set<String> = snapshot(context).advertisedHosts

    internal fun isLanAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
            return false
        }
        return address is Inet4Address && address.isSiteLocalAddress
    }

    /** Tailscale hands out IPv4 from the CGNAT block 100.64.0.0/10; only these are accepted from a VPN. */
    internal fun isTailnetAddress(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        return (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64
    }
}

internal data class ViewerEndpointSnapshot(
    val lanAddresses: Set<String>,
    val advertisedHosts: Set<String>,
)
