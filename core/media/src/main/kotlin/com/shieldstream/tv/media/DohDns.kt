package com.shieldstream.tv.media

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Modified build: DNS-over-HTTPS resolver for the TV box, whose system DNS is poisoned
 * (stream hosts resolve to random wrong IPs). DoH server domains (dns.alidns.com / doh.pub)
 * resolve correctly on the box, so we bootstrap from those and resolve playback hosts via HTTPS.
 */
object DohDns : Dns {
    private const val CACHE_TTL_MS = 10 * 60_000L

    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    private val doh: DnsOverHttps = DnsOverHttps.Builder()
        .client(bootstrap)
        .url("https://doh.pub/dns-query".toHttpUrl())
        .bootstrapDnsHosts(
            InetAddress.getByName("1.12.12.12"),
            InetAddress.getByName("223.5.5.5"),
        )
        .includeIPv6(false)
        .build()

    private class CachedEntry(val at: Long, val addrs: List<InetAddress>)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CachedEntry>()

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[hostname]?.takeIf { now - it.at < CACHE_TTL_MS && it.addrs.isNotEmpty() }?.let { return it.addrs }
        val resolved = runCatching { doh.lookup(hostname) }
            .getOrDefault(emptyList())
            .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
            .ifEmpty {
                runCatching { Dns.SYSTEM.lookup(hostname) }
                    .getOrDefault(emptyList())
                    .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress }
            }
        if (resolved.isNotEmpty()) cache[hostname] = CachedEntry(now, resolved)
        return resolved.ifEmpty { Dns.SYSTEM.lookup(hostname) }
    }
}
