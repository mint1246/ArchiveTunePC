/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */
package moe.rukamori.archivetune.innertube

import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import java.util.concurrent.TimeUnit

private val SHARED_DESKTOP_STREAM_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
    .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
    .proxySelector(object : ProxySelector() {
        override fun select(uri: URI) = listOf(YouTube.streamOkHttpProxy)
        override fun connectFailed(uri: URI, address: SocketAddress, failure: IOException) = Unit
    })
    .proxyAuthenticator { route, response ->
        val username = YouTube.proxyUsername
        if (route?.proxy != YouTube.streamOkHttpProxy || route?.proxy?.type() != java.net.Proxy.Type.HTTP ||
            username.isNullOrBlank() || response.request.header("Proxy-Authorization") != null) null
        else response.request.newBuilder().header("Proxy-Authorization", Credentials.basic(username, YouTube.proxyPassword.orEmpty())).build()
    }
    .dns(object : Dns {
        override fun lookup(hostname: String) = YouTube.dns.lookup(hostname)
    })
    .build()

/**
 * Use the application's current media route for probes, VLC callback reads and downloads alike.
 *
 * Returns a client sharing the underlying [ConnectionPool] and [okhttp3.Dispatcher] so the stream
 * probe warms the exact HTTP/2 TLS socket that libVLC's ranged callback transport immediately reuses.
 */
fun desktopStreamHttpClient(): OkHttpClient = SHARED_DESKTOP_STREAM_HTTP_CLIENT.newBuilder().build()
