package com.phamdu.flipcamera.security

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerEndpointDiscoveryTest {
    @Test
    fun onlyPrivateIpv4AddressesAreAdvertisedForTheIpv4Listener() {
        assertTrue(ViewerEndpointDiscovery.isLanAddress(InetAddress.getByName("192.168.1.5")))
        assertFalse(ViewerEndpointDiscovery.isLanAddress(InetAddress.getByName("fc00::1234")))
        assertFalse(ViewerEndpointDiscovery.isLanAddress(InetAddress.getByName("fec0::1234")))
        assertFalse(ViewerEndpointDiscovery.isLanAddress(InetAddress.getByName("fe80::1234")))
        assertFalse(ViewerEndpointDiscovery.isLanAddress(InetAddress.getByName("203.0.113.5")))
    }

    @Test
    fun onlyTailscaleCgnatIpv4IsAcceptedFromVpn() {
        assertTrue(ViewerEndpointDiscovery.isTailnetAddress(InetAddress.getByName("100.115.117.61")))
        assertTrue(ViewerEndpointDiscovery.isTailnetAddress(InetAddress.getByName("100.64.0.1")))
        assertTrue(ViewerEndpointDiscovery.isTailnetAddress(InetAddress.getByName("100.127.255.254")))
        assertFalse(ViewerEndpointDiscovery.isTailnetAddress(InetAddress.getByName("100.63.255.255")))
        assertFalse(ViewerEndpointDiscovery.isTailnetAddress(InetAddress.getByName("100.128.0.1")))
        assertFalse(ViewerEndpointDiscovery.isTailnetAddress(InetAddress.getByName("10.8.0.2")))
        assertFalse(ViewerEndpointDiscovery.isTailnetAddress(InetAddress.getByName("fd7a:115c:a1e0::6001:75b5")))
    }
}
