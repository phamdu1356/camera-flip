package com.phamdu.flipcamera.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ViewerTlsProtocolPolicyTest {
    @Test
    fun enablesOnlySupportedModernProtocolsAndRequiresTls12() {
        assertArrayEquals(
            arrayOf("TLSv1.3", "TLSv1.2"),
            selectServerTlsProtocols(arrayOf("TLSv1.3", "TLSv1.2", "TLSv1.1")),
        )
        assertArrayEquals(
            arrayOf("TLSv1.2"),
            selectServerTlsProtocols(arrayOf("TLSv1.2", "TLSv1.1")),
        )
        assertThrows(IllegalArgumentException::class.java) {
            selectServerTlsProtocols(arrayOf("TLSv1.1", "TLSv1.0"))
        }
    }
}
