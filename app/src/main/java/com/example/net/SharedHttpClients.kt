package com.example.net

import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Custom OkHttp [Dns] that tries [Dns.SYSTEM] first and falls back to
 * Cloudflare (1.1.1.1) and Google (8.8.8.8) DNS-over-HTTPS (DoH) on [UnknownHostException].
 *
 * Uses bootstrap IP mappings for DoH hostnames so DoH queries succeed even when
 * ISP/device system DNS is completely unreachable.
 */
class PublicDohFallbackDns(
    private val systemLookup: (String) -> List<InetAddress> = { Dns.SYSTEM.lookup(it) },
    dohClient: OkHttpClient? = null
) : Dns {

    private val effectiveDohClient: OkHttpClient by lazy { dohClient ?: createBootstrapDohClient() }

    private data class CachedRecord(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    private val cache = ConcurrentHashMap<String, CachedRecord>()

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim()
        if (cleanHost.isEmpty()) {
            throw UnknownHostException("Hostname is blank")
        }

        // 1. Primary: system DNS
        val systemFailure: UnknownHostException? = try {
            val resolved = systemLookup(cleanHost)
            if (resolved.isNotEmpty()) {
                return resolved
            }
            UnknownHostException("System DNS returned no addresses for $cleanHost")
        } catch (e: UnknownHostException) {
            e
        }

        // 2. Direct IPv4 literal shortcut
        parseIpv4Literal(cleanHost)?.let { ipv4Bytes ->
            return listOf(InetAddress.getByAddress(cleanHost, ipv4Bytes))
        }

        // 3. Cached DoH resolution (within TTL)
        val now = System.currentTimeMillis()
        cache[cleanHost.lowercase()]?.let { cached ->
            if (now < cached.expiresAtMs && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }
        }

        // 4. Fallback: Cloudflare (1.1.1.1) + Google (8.8.8.8) DoH IPv4 A lookup
        val dohErrors = mutableListOf<String>()
        for (endpoint in buildDohCandidateUrls(cleanHost)) {
            try {
                val req = Request.Builder()
                    .url(endpoint)
                    .get()
                    .header("Accept", "application/dns-json")
                    .build()

                effectiveDohClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        dohErrors.add("$endpoint -> HTTP ${resp.code}")
                        return@use
                    }
                    val parsed = parseDohIpv4Response(cleanHost, body)
                    if (parsed.addresses.isNotEmpty()) {
                        val ttlMs = parsed.minTtlSeconds.coerceIn(MIN_CACHE_TTL_SEC, MAX_CACHE_TTL_SEC) * 1000L
                        cache[cleanHost.lowercase()] = CachedRecord(
                            addresses = parsed.addresses,
                            expiresAtMs = System.currentTimeMillis() + ttlMs
                        )
                        return parsed.addresses
                    } else {
                        dohErrors.add("$endpoint -> no A records")
                    }
                }
            } catch (e: Exception) {
                dohErrors.add("$endpoint -> ${e.message ?: e.javaClass.simpleName}")
            }
        }

        throw UnknownHostException(
            "Unable to resolve host \"$cleanHost\" via System DNS (${systemFailure?.message}) or Public DoH (${dohErrors.joinToString("; ")})"
        ).apply {
            if (systemFailure != null) initCause(systemFailure)
        }
    }

    data class DohParseResult(
        val addresses: List<InetAddress>,
        val minTtlSeconds: Long
    )

    companion object {
        private const val MIN_CACHE_TTL_SEC = 30L
        private const val MAX_CACHE_TTL_SEC = 300L
        private const val DNS_RECORD_TYPE_A = 1

        private val BOOTSTRAP_DOH_HOSTS: Map<String, List<ByteArray>> = mapOf(
            "cloudflare-dns.com" to listOf(
                byteArrayOf(1, 1, 1, 1),
                byteArrayOf(1, 0, 0, 1)
            ),
            "1.1.1.1" to listOf(byteArrayOf(1, 1, 1, 1)),
            "dns.google" to listOf(
                byteArrayOf(8, 8, 8, 8),
                byteArrayOf(8, 8, 4, 4)
            ),
            "8.8.8.8" to listOf(byteArrayOf(8, 8, 8, 8))
        )

        fun createBootstrapDohClient(): OkHttpClient {
            val bootstrapDns = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val clean = hostname.trim().lowercase()
                    val mapped = BOOTSTRAP_DOH_HOSTS[clean]
                    return if (mapped != null) {
                        mapped.map { bytes -> InetAddress.getByAddress(hostname, bytes) }
                    } else {
                        Dns.SYSTEM.lookup(hostname)
                    }
                }
            }
            return OkHttpClient.Builder()
                .dns(bootstrapDns)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
        }

        fun buildCloudflareDohUrl(hostname: String): String {
            val encoded = URLEncoder.encode(hostname.trim(), Charsets.UTF_8.name())
            return "https://cloudflare-dns.com/dns-query?name=$encoded&type=A"
        }

        fun buildCloudflareDirectIpDohUrl(hostname: String): String {
            val encoded = URLEncoder.encode(hostname.trim(), Charsets.UTF_8.name())
            return "https://1.1.1.1/dns-query?name=$encoded&type=A"
        }

        fun buildGoogleDohUrl(hostname: String): String {
            val encoded = URLEncoder.encode(hostname.trim(), Charsets.UTF_8.name())
            return "https://dns.google/resolve?name=$encoded&type=A"
        }

        fun buildGoogleDirectIpDohUrl(hostname: String): String {
            val encoded = URLEncoder.encode(hostname.trim(), Charsets.UTF_8.name())
            return "https://8.8.8.8/resolve?name=$encoded&type=A"
        }

        fun buildDohCandidateUrls(hostname: String): List<String> = listOf(
            buildCloudflareDohUrl(hostname),
            buildCloudflareDirectIpDohUrl(hostname),
            buildGoogleDohUrl(hostname),
            buildGoogleDirectIpDohUrl(hostname)
        )

        fun parseIpv4Literal(raw: String): ByteArray? {
            val parts = raw.trim().split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for (i in 0 until 4) {
                val p = parts[i]
                if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
                val num = p.toIntOrNull() ?: return null
                if (num !in 0..255) return null
                bytes[i] = num.toByte()
            }
            return bytes
        }

        fun parseDohIpv4Response(hostname: String, jsonBody: String): DohParseResult {
            if (jsonBody.isBlank()) return DohParseResult(emptyList(), MIN_CACHE_TTL_SEC)
            val root = try {
                JSONObject(jsonBody)
            } catch (_: Exception) {
                return DohParseResult(emptyList(), MIN_CACHE_TTL_SEC)
            }
            val answerArray = root.optJSONArray("Answer") ?: root.optJSONArray("answer")
                ?: return DohParseResult(emptyList(), MIN_CACHE_TTL_SEC)

            val addresses = mutableListOf<InetAddress>()
            var minTtl = MAX_CACHE_TTL_SEC
            for (i in 0 until answerArray.length()) {
                val entry = answerArray.optJSONObject(i) ?: continue
                if (entry.optInt("type", -1) != DNS_RECORD_TYPE_A) continue
                val data = entry.optString("data", "").trim()
                val ipv4Bytes = parseIpv4Literal(data) ?: continue
                addresses.add(InetAddress.getByAddress(hostname, ipv4Bytes))
                val rawTtl = if (entry.has("TTL")) entry.optLong("TTL", MIN_CACHE_TTL_SEC) else entry.optLong("ttl", MIN_CACHE_TTL_SEC)
                if (rawTtl > 0L && rawTtl < minTtl) {
                    minTtl = rawTtl
                }
            }
            return DohParseResult(
                addresses = addresses,
                minTtlSeconds = minTtl.coerceIn(MIN_CACHE_TTL_SEC, MAX_CACHE_TTL_SEC)
            )
        }
    }
}

object SharedHttpClients {
    val dns: PublicDohFallbackDns by lazy { PublicDohFallbackDns() }

    val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(dns)
            .build()
    }

    val llmClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(30L, TimeUnit.SECONDS)
            .readTimeout(180L, TimeUnit.SECONDS)
            .writeTimeout(60L, TimeUnit.SECONDS)
            .callTimeout(300L, TimeUnit.SECONDS)
            .build()
    }

    val aiHordeDirectorClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(20L, TimeUnit.SECONDS)
            .writeTimeout(20L, TimeUnit.SECONDS)
            .callTimeout(25L, TimeUnit.SECONDS)
            .build()
    }

    val sceneArtClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .dispatcher(Dispatcher().apply {
                maxRequests = 32
                maxRequestsPerHost = 16
            })
            .connectTimeout(15L, TimeUnit.SECONDS)
            .readTimeout(25L, TimeUnit.SECONDS)
            .writeTimeout(15L, TimeUnit.SECONDS)
            .build()
    }

    val modelDetectorClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(40L, TimeUnit.SECONDS)
            .writeTimeout(20L, TimeUnit.SECONDS)
            .build()
    }

    val elevenLabsClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .connectTimeout(20L, TimeUnit.SECONDS)
            .readTimeout(45L, TimeUnit.SECONDS)
            .writeTimeout(20L, TimeUnit.SECONDS)
            .build()
    }
}
