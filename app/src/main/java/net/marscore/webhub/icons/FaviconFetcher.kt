package net.marscore.webhub.icons

import okhttp3.OkHttpClient
import okhttp3.Request
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Result of [FaviconFetcher.fetch]: the decoded bitmap on success, or a short lowercase machine
 * reason on failure (e.g. `"network"`, `"http_404"`, `"decode"`, `"timeout"`). On success [error]
 * is null and [bitmap] is non-null; on failure [bitmap] is null and [error] is non-null.
 *
 * Public contract (revamp-hub-ux D3): the wizard / import / hub lanes key off these fields.
 */
data class FaviconFetchResult(
    val bitmap: Bitmap?,
    val error: String?
)

/**
 * Fetches a favicon bitmap for a site URL (task 9.5, revamp-hub-ux D3).
 *
 * Strategy:
 *  1. GET `<base>/favicon.ico`. Decode the response bytes by first trying [BitmapFactory] directly
 *     (handles the common case where the URL actually serves a PNG/GIF mislabeled as .ico), then
 *     falling back to [IcoDecoder] for real ICO containers wrapping PNG payloads.
 *  2. If that yields nothing, GET the page HTML and scan `<link rel="...icon...">` hrefs (already
 *     ranked apple-touch / largest first by [parseIconLinks]), trying each candidate with the same
 *     raw-then-ICO decode until one decodes.
 *
 * Robustness fixes (task 9.5):
 *  - ICO container parsing via [IcoDecoder] (BitmapFactory can't read real .ico files).
 *  - `ignoreSsl` support for self-hosted sites with self-signed certs (mirrors the WebView-side
 *    per-child ignoreSsl the user configures). Default false keeps existing callers compiling.
 *  - A desktop Chrome `User-Agent` header on all requests (some servers 403 okhttp's default UA).
 *
 * Returns [FaviconFetchResult]: bitmap on success, or a short machine-readable error reason on
 * failure (revamp-hub-ux D3 — the wizard surfaces this to the user). Enforces a ~5s per-request
 * timeout via the OkHttp client configuration.
 */
class FaviconFetcher(
    private val client: OkHttpClient = defaultClient(ignoreSsl = false)
) {

    /**
     * @param siteUrl   the page URL (scheme + host [+ port]); path is ignored for base derivation.
     * @param ignoreSsl when true, use a trust-all TLS client so self-signed certs don't abort the
     *                  fetch. Default false (keeps old callers compiling).
     * @return a [FaviconFetchResult] — [FaviconFetchResult.bitmap] non-null on success, otherwise
     *         [FaviconFetchResult.error] carries a short lowercase reason string.
     */
    suspend fun fetch(siteUrl: String, ignoreSsl: Boolean = false): FaviconFetchResult =
        withContext(Dispatchers.IO) {
            val effectiveClient = if (ignoreSsl) trustAllClient() else client
            val base = runCatching { URI(siteUrl) }.getOrNull()
                ?: return@withContext FaviconFetchResult(null, "bad_url")
            val baseUrl = "${base.scheme}://${base.host}${base.port.takeIf { it > 0 }?.let { ":$it" } ?: ""}"

            // 1. Try /favicon.ico directly (raw-then-ICO decode).
            fetchAndDecode(effectiveClient, "$baseUrl/favicon.ico")?.let {
                return@withContext FaviconFetchResult(it, null)
            }

            // 2. Fetch HTML and parse <link rel=*icon*> hrefs (ranked apple-touch/largest first).
            // Round-2 fix: capture the FINAL (post-redirect) document URL and resolve <link> hrefs
            // against it, not against the request baseUrl. Sites like code-server redirect the root
            // (`/` → 302 → `/login`) and serve the icon-declaring HTML from the redirected page;
            // resolving relative `./_static/...` hrefs against the request baseUrl (no path) only
            // coincidentally matches when the redirect target is a shallow single-segment path
            // (e.g. `/login`). For deeper redirects the original baseUrl would yield wrong URLs.
            // Using the real document URL makes candidate resolution correct regardless of redirect
            // depth, and removes a class of "all candidates fail → decode" failures on real devices
            // where the redirect landing page lives under a non-root path.
            val (html, docUrl) = fetchText(effectiveClient, baseUrl)
            if (html == null || docUrl == null) {
                return@withContext FaviconFetchResult(null, "network")
            }
            val candidates = parseIconLinks(html, docUrl)
            for (c in candidates) {
                fetchAndDecode(effectiveClient, c.url)?.let {
                    return@withContext FaviconFetchResult(it, null)
                }
            }
            FaviconFetchResult(null, "decode")
        }

    /**
     * GET [url], then decode the body: try BitmapFactory on the raw bytes first (handles
     * mislabeled PNG/GIF served at .ico URLs), then fall back to [IcoDecoder] for true ICOs.
     * Returns the bitmap, or null on any failure (HTTP non-2xx, network error, undecodable bytes).
     */
    private fun fetchAndDecode(http: OkHttpClient, url: String): Bitmap? {
        val bytes = fetchBytes(http, url) ?: return null
        // Raw decode first — works for PNG/JPEG/GIF/WebP served at any URL.
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        // Fallback: real ICO container (BitmapFactory can't parse these).
        return IcoDecoder.decode(bytes)
    }

    private fun fetchBytes(http: OkHttpClient, url: String): ByteArray? {
        return try {
            http.newCall(buildGetRequest(url)).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchBytes failed for $url: ${e.message}")
            null
        }
    }

    /**
     * GET [url] (following redirects) and return the response body text plus the FINAL document
     * URL (after any redirects OkHttp followed). Returns (null, null) on any failure (network
     * error, non-2xx final response, empty body).
     *
     * The final URL is surfaced so callers can resolve page-relative `<link>` hrefs against the
     * document that actually contains them (post-redirect), not the original request URL —
     * important for sites that redirect the root to a deeper landing page.
     */
    private fun fetchText(http: OkHttpClient, url: String): Pair<String?, String?> {
        return try {
            http.newCall(buildGetRequest(url)).execute().use { resp ->
                if (!resp.isSuccessful) return Pair(null, null)
                val finalUrl = resp.request.url.toString()
                val body = resp.body?.string() ?: return Pair(null, null)
                Pair(body, finalUrl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchText failed for $url: ${e.message}")
            Pair(null, null)
        }
    }

    private fun buildGetRequest(url: String): Request =
        Request.Builder().url(url).get()
            .header("User-Agent", DESKTOP_UA)
            .build()

    internal fun parseIconLinks(html: String, baseUrl: String): List<IconCandidate> {
        val result = mutableListOf<IconCandidate>()
        // Match <link ... rel="...icon..." ... href="..."> with attributes in any order.
        val linkRegex = Regex(
            "<link[^>]*\\srel\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>",
            RegexOption.IGNORE_CASE
        )
        for (m in linkRegex.findAll(html)) {
            val tag = m.value
            val rel = m.groupValues[1].lowercase()
            if (!rel.contains("icon")) continue
            val hrefMatch = Regex("href\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)
            val href = hrefMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: continue
            // Skip SVG candidates: BitmapFactory cannot decode them and they'd waste a request
            // before failing. This also ensures an SVG-only site yields the "decode" error rather
            // than silently consuming a candidate slot.
            if (href.lowercase().endsWith(".svg")) continue
            val resolved = resolveUrl(baseUrl, href) ?: continue
            val sizesMatch = Regex("sizes\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)
            val size = parseSize(sizesMatch?.groupValues?.get(1))
            val priority = when {
                rel.contains("apple-touch") -> 0
                rel.contains("shortcut") -> 2
                else -> 1
            }
            result.add(IconCandidate(resolved, priority, size))
        }
        // apple-touch first, then larger sizes first, preserving source order otherwise.
        return result.sortedWith(compareBy({ it.priority }, { -it.size }))
    }

    private fun parseSize(s: String?): Int {
        if (s.isNullOrBlank()) return 0
        // "192x192" or "any"
        val m = Regex("(\\d+)x\\d+").find(s)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun resolveUrl(base: String, href: String): String? {
        return try {
            URI(base).resolve(href).toString()
        } catch (e: Exception) {
            null
        }
    }

    /** Lazily build a trust-all client when [ignoreSsl] is requested at call time. */
    private fun trustAllClient(): OkHttpClient {
        // Reuse the base client's timeouts but swap the TLS/hostname trust.
        return try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustAll, java.security.SecureRandom())
            }
            val permissiveHost: HostnameVerifier = HostnameVerifier { _, _ -> true }
            defaultClient(ignoreSsl = false).newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
                .hostnameVerifier(permissiveHost)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "trustAll client build failed, falling back to default: ${e.message}")
            defaultClient(ignoreSsl = false)
        }
    }

    internal data class IconCandidate(val url: String, val priority: Int, val size: Int)

    companion object {
        private const val TAG = "FaviconFetcher"

        /** Desktop Chrome UA sent on all requests (some servers 403 okhttp's default UA). */
        internal const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.6778.86 Safari/537.36"

        /**
         * OkHttp client for favicon fetching.
         *
         * Round-2 fix: the call-level timeout is 15s (raised from 5s) to accommodate redirect
         * chains on real devices. OkHttp's `callTimeout` bounds the ENTIRE call including
         * auto-followed redirects, so a 5s callTimeout silently kills a redirect chain whose
         * total exceeds 5s even when each individual hop is well under the 5s per-hop
         * `readTimeout`. On a real device with a cold TLS handshake + a redirect (e.g.
         * vscode.marscore.net:31410 `/` → 302 → `/login`), the chain total can exceed 5s on a
         * slow mobile network directly hitting the proxy — yielding "network" and a failed icon
         * fetch. The per-hop `connectTimeout`/`readTimeout` stay at 5s (honoring the design's
         * "各 5s 超时" intent: 5s per request, with the whole redirect chain allowed up to 15s).
         * Verified by FaviconFetcherCallTimeoutTest (multi-hop chain totaling >5s succeeds once
         * callTimeout is raised above the chain total).
         */
        fun defaultClient(ignoreSsl: Boolean = false): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}