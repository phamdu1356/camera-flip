package com.phamdu.flipviewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerAppUnitTest {

    @Test
    fun normalizeUrl_addsHttpsAndTrailingSlash() {
        assertEquals("https://192.168.0.106:8443/", ServerUrlUtils.normalizeUrl("192.168.0.106:8443"))
    }

    @Test
    fun normalizeUrl_preservesExistingHttps() {
        assertEquals("https://192.168.0.106:8443/", ServerUrlUtils.normalizeUrl("https://192.168.0.106:8443"))
    }

    @Test
    fun normalizeUrl_preservesExistingHttp() {
        assertEquals("http://192.168.0.106:8443/", ServerUrlUtils.normalizeUrl("http://192.168.0.106:8443/"))
    }

    @Test
    fun normalizeUrl_trimsWhitespace() {
        assertEquals("https://10.0.0.5:8443/", ServerUrlUtils.normalizeUrl("   10.0.0.5:8443   "))
    }

    @Test
    fun normalizeUrl_emptyReturnsDefault() {
        assertEquals("https://192.168.0.106:8443/", ServerUrlUtils.normalizeUrl("   "))
    }

    @Test
    fun normalizeUrl_preservesPathWithoutAddingSlash() {
        assertEquals("https://192.168.0.106:8443/live", ServerUrlUtils.normalizeUrl("https://192.168.0.106:8443/live"))
    }

    @Test
    fun normalizeUrl_handlesQueryStringCorrectly() {
        assertEquals("https://192.168.0.106:8443/?token=abc", ServerUrlUtils.normalizeUrl("https://192.168.0.106:8443?token=abc"))
    }

    @Test
    fun formatBadge_extractsHostAndPort() {
        assertEquals("192.168.0.106:8443", ServerUrlUtils.formatBadge("https://192.168.0.106:8443/"))
    }

    @Test
    fun formatBadge_omitsStandardPorts() {
        assertEquals("flip.local", ServerUrlUtils.formatBadge("https://flip.local:443/"))
        assertEquals("flip.local", ServerUrlUtils.formatBadge("http://flip.local:80/"))
    }

    @Test
    fun formatBadge_handlesCustomHost() {
        assertEquals("camera.lan:8443", ServerUrlUtils.formatBadge("https://camera.lan:8443/"))
    }
}
