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
    private val bootstrap = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
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

    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<InetAddress>>()

    override fun lookup(hostname: String): List<InetAddress> {
        cache[hostname]?.let { if (it.isNotEmpty()) return it }
        val resolved = runCatching { doh.lookup(hostname) }
            .getOrDefault(emptyList())
            .ifEmpty { runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList()) }
        if (resolved.isNotEmpty()) cache[hostname] = resolved
        return resolved
    }
}
