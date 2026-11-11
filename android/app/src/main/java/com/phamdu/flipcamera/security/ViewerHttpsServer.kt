package com.phamdu.flipcamera.security

import android.content.Context
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.http.HttpHeaders
import io.ktor.server.response.respond
import io.ktor.http.HttpStatusCode
import io.netty.handler.ssl.SslHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler

/** HTTPS-only Ktor server. The caller owns its lifecycle and supplies the read-only routes. */
internal class ViewerHttpsServer(
    private val tls: ViewerTlsMaterial,
    private val bindHosts: Set<String>,
    private val port: Int,
    allowedHosts: Set<String>,
    private val installRoutes: Application.() -> Unit,
) : AutoCloseable {
    private val allowedHosts = allowedHosts.map(::normalizeViewerHost).toSet()
    val advertisedHosts: Set<String> get() = allowedHosts
    val certificateFingerprint: String get() = tls.sha256Fingerprint

    init {
        require(this.allowedHosts.isNotEmpty()) { "At least one advertised HTTPS host is required" }
        require(this.allowedHosts.none { it.isBlank() || it == "*" }) {
            "Blank and wildcard Host allowlist entries are not permitted"
        }
    }

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    suspend fun start(): Int {
        val started = synchronized(this) {
            check(server == null) { "Viewer HTTPS server is already running" }
            embeddedServer(
                Netty,
                configure = {
                    connectionGroupSize = 1
                    workerGroupSize = 4
                    callGroupSize = 4
                    runningLimit = 64
                    maxHeaderSize = 8_192
                    maxInitialLineLength = 2_048
                    // A read-only timeout also cancels a healthy outbound MJPEG response.
                    // Apply an idle deadline to both directions in the pipeline instead.
                    requestReadTimeoutSeconds = 0
                    responseWriteTimeoutSeconds = 15
                    this@ViewerHttpsServer.bindHosts.sorted().forEach { bindHost ->
                        connector {
                            host = bindHost
                            port = this@ViewerHttpsServer.port
                        }
                    }
                    enableHttp2 = false
                    channelPipelineConfig = {
                        val engine = tls.sslContext.createSSLEngine().apply {
                            useClientMode = false
                            enabledProtocols = selectServerTlsProtocols(supportedProtocols)
                        }
                        // Ktor's sslConnector asks Netty to copy the private key into an
                        // exportable keystore. Insert Android's non-exportable Keystore-backed
                        // SSL engine directly before the HTTP codec instead.
                        addFirst("flip-idle-close", object : ChannelInboundHandlerAdapter() {
                            override fun userEventTriggered(context: ChannelHandlerContext, event: Any) {
                                if (event is IdleStateEvent && event.state() == IdleState.ALL_IDLE) {
                                    context.close()
                                } else {
                                    super.userEventTriggered(context, event)
                                }
                            }
                        })
                        addFirst("flip-idle", IdleStateHandler(0, 0, 15))
                        addFirst("flip-tls", SslHandler(engine))
                    }
                },
                module = {
                    installHostAllowlist(this@ViewerHttpsServer.allowedHosts)
                    installRoutes()
                },
            ).also { server = it }
        }
        try {
            started.start(wait = false)
            return started.engine.resolvedConnectors().first().port
        } catch (failure: Throwable) {
            synchronized(this) { if (server === started) server = null }
            started.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
            throw failure
        }
    }

    override fun close() {
        val stopping = synchronized(this) { server.also { server = null } }
        stopping?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
    }

    private fun Application.installHostAllowlist(allowedHosts: Set<String>) {
        intercept(ApplicationCallPipeline.Plugins) {
            val requestedHost = parseHostAuthority(call.request.headers[HttpHeaders.Host])?.host
            if (requestedHost == null || requestedHost !in allowedHosts) {
                call.respond(HttpStatusCode(421, "Misdirected Request"))
                finish()
            }
        }
    }

    companion object {
        /** Keeps the TLS SAN, Host allowlist, and addresses shown by pairing in one set. */
        fun forCurrentLan(
            context: Context,
            port: Int,
            installRoutes: Application.() -> Unit,
        ): ViewerHttpsServer = forSnapshot(
            context,
            port,
            ViewerEndpointDiscovery.snapshot(context),
            installRoutes,
        )

        /** Uses the same interface snapshot for the certificate and the UI's advertised URLs. */
        fun forSnapshot(
            context: Context,
            port: Int,
            endpoints: ViewerEndpointSnapshot,
            installRoutes: Application.() -> Unit,
        ): ViewerHttpsServer {
            require(endpoints.lanAddresses.isNotEmpty()) { "At least one LAN endpoint is required" }
            val tls = ViewerTlsIdentity(context, endpoints.advertisedHosts).loadOrCreate()
            // Production binds each approved IPv4 address individually. A zero port is
            // reserved for local instrumentation tests, which connect through loopback.
            val bindHosts = if (port == 0) setOf("127.0.0.1") else endpoints.lanAddresses + "127.0.0.1"
            return ViewerHttpsServer(
                tls = tls,
                bindHosts = bindHosts,
                port = port,
                allowedHosts = endpoints.advertisedHosts,
                installRoutes = installRoutes,
            )
        }
    }
}

internal fun selectServerTlsProtocols(supportedProtocols: Array<String>): Array<String> {
    val supported = supportedProtocols.toSet()
    val enabled = listOf("TLSv1.3", "TLSv1.2").filter(supported::contains)
    require("TLSv1.2" in enabled) { "TLS 1.2 is required for the HTTPS viewer" }
    return enabled.toTypedArray()
}
