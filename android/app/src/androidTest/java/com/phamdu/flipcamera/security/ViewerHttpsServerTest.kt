package com.phamdu.flipcamera.security

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import kotlinx.coroutines.runBlocking
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

@RunWith(AndroidJUnit4::class)
class ViewerHttpsServerTest {
    @Test
    fun ktorNettyServesHttpsWithAndroidKeystoreCertificate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = ViewerHttpsServer.forCurrentLan(context, 0) {
            routing {
                get("/__health") { call.respondText("ok") }
            }
        }
        try {
            val port = runBlocking { server.start() }
            val advertisedHost = ViewerEndpointDiscovery.snapshot(context).lanAddresses.firstOrNull() ?: "127.0.0.1"
            val tls = ViewerTlsIdentity(context).loadOrCreate()
            val clientContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(CertificatePinTrustManager(tls.certificate)), null)
            }
            val accepted = httpsRequest(clientContext, port, advertisedHost, formatHostHeader(advertisedHost, port))
            val peerCertificate = accepted.second
            assertTrue(peerCertificate.encoded.contentEquals(tls.certificate.encoded))
            assertTrue("Expected a successful HTTPS response, got: ${accepted.first}", accepted.first.startsWith("HTTP/1.1 200"))
            assertTrue(accepted.first.endsWith("ok"))

            val rejected = httpsRequest(clientContext, port, advertisedHost, "attacker.example:$port").first
            assertTrue("Unexpected Host should be rejected with 421, got: $rejected", rejected.startsWith("HTTP/1.1 421"))
        } finally {
            server.close()
        }
    }

    private fun formatHostHeader(host: String, port: Int): String =
        if (host.contains(':')) "[$host]:$port" else "$host:$port"

    private fun httpsRequest(
        clientContext: SSLContext,
        port: Int,
        peerHost: String,
        hostHeader: String,
    ): Pair<String, X509Certificate> {
        val transport = java.net.Socket(InetAddress.getByName("127.0.0.1"), port)
        (clientContext.socketFactory.createSocket(transport, peerHost, port, true) as SSLSocket).use { client ->
            client.soTimeout = 5_000
            client.sslParameters = client.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            client.startHandshake()
            val peerCertificate = client.session.peerCertificates.first() as X509Certificate
            client.getOutputStream().write(
                "GET /__health HTTP/1.1\r\nHost: $hostHeader\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII),
            )
            client.getOutputStream().flush()
            val response = client.getInputStream().bufferedReader(Charsets.US_ASCII).readText()
            return response to peerCertificate
        }
    }

    private class CertificatePinTrustManager(
        private val expected: X509Certificate,
    ) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf(expected)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || !chain.first().encoded.contentEquals(expected.encoded)) {
                throw CertificateException("Unexpected TLS certificate")
            }
        }
    }
}
