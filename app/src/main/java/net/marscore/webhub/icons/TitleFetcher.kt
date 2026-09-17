package net.marscore.webhub.icons

import okhttp3.OkHttpClient
import okhttp3.Request
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Shared HTTP plumbing for the icon-package fetchers ([FaviconFetcher], [TitleFetcher]).
 *
 * Both need a desktop-UA GET with redirect following, ~5s timeouts, and an optional trust-all TLS
 * mode for self-signed sites (mirrors the per-child `ignoreSsl` the user configures). Centralizing
 * the client builder here keeps the trust-all construction in one place (revamp-hub-ux D3 notes
 * this as an optional cleanup; churn stays minimal — [FaviconFetcher] keeps its own
 * `defaultClient`/`trustAllClient` for backward-compat with its existing test fixture, but both
 * delegate the trust-all construction to [trustAllBuilderFactory]).
 */
internal object HttpClients {

    /** Desktop Chrome UA sent on all icon-package requests (some servers 403 okhttp's default UA). */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.6778.86 Safari/537.36"

    /**
     * Base OkHttp client with 5s per-hop timeouts and redirect following; callTimeout 15s to
     * accommodate redirect chains (same rationale as [FaviconFetcher.defaultClient] — a 5s
     * callTimeout kills multi-hop chains like `/` → 302 → `/login` when the total exceeds 5s).
     */
    fun baseClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Wrap [base] with a trust-all TLS factory + permissive hostname verifier for `ignoreSsl` mode.
     * Returns [base] unchanged on any failure to construct the trust manager (best-effort).
     */
    fun trustAll(base: OkHttpClient = baseClient()): OkHttpClient = try {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, java.security.SecureRandom())
        }
        val permissiveHost: HostnameVerifier = HostnameVerifier { _, _ -> true }
        base.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier(permissiveHost)
            .build()
    } catch (e: Exception) {
        Log.w("HttpClients", "trustAll build failed, falling back: ${e.message}")
        base
    }

    /** A GET request with the shared desktop UA. */
    fun getRequest(url: String): Request =
        Request.Builder().url(url).get()
            .header("User-Agent", DESKTOP_UA)
            .build()
}

/**
 * Fetches the `<title>` of a web page (revamp-hub-ux D3).
 *
 * GETs [siteUrl] with a desktop Chrome UA, following redirects, ~5s timeouts, and the same
 * `ignoreSsl` trust-all strategy as [FaviconFetcher]. Extracts the first `<title>` tag
 * (case-insensitive), unescapes common HTML entities (named + numeric, decimal and hex), collapses
 * internal whitespace runs to single spaces, and trims. Returns null on any failure (network,
 * non-2xx, missing/blank title, undecodable body).
 *
 * No title cleaning beyond entity unescape + whitespace collapse (design Non-Goals: site suffixes
 * like " - Powered by" are left intact; the user can edit the prefilled name).
 */
object TitleFetcher {

    private const val TAG = "TitleFetcher"

    /**
     * @param siteUrl   the page URL to GET.
     * @param ignoreSsl when true, use a trust-all TLS client so self-signed certs don't abort the
     *                  fetch. Default false.
     * @return the first `<title>` text (unescaped, whitespace-collapsed, trimmed), or null on
     *         failure / missing / blank title.
     */
    suspend fun fetch(siteUrl: String, ignoreSsl: Boolean = false): String? = withContext(Dispatchers.IO) {
        val client = if (ignoreSsl) HttpClients.trustAll() else HttpClients.baseClient()
        val html: String = try {
            client.newCall(HttpClients.getRequest(siteUrl)).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string() ?: return@withContext null
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed for $siteUrl: ${e.message}")
            return@withContext null
        }
        extractTitle(html)?.let { unescapeEntities(it) }?.let { collapseWhitespace(it) }?.takeIf { it.isNotBlank() }
    }

    /** Case-insensitive extraction of the first `<title>...</title>` inner text. */
    internal fun extractTitle(html: String): String? {
        val regex = Regex(
            "<title[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val match: MatchResult = regex.find(html) ?: return null
        val inner = match.groupValues[1]
        return inner.takeIf { it.isNotBlank() }
    }

    /**
     * Unescape the common HTML entities: the five named ones that appear in titles plus numeric
     * (decimal `&#NN;` and hex `&#xNN;`). Unknown named entities are left as-is.
     */
    internal fun unescapeEntities(input: String): String {
        if (input.indexOf('&') < 0) return input
        // Named entities first (order matters: &amp; must be done last-so it doesn't double-decode
        // something like &amp;lt; — but since we process left-to-right via regex replaceAll, we do
        // &amp; last by simply listing it after the others; the named ones here don't overlap).
        var s = input
        s = s.replace("&quot;", "\"")
        s = s.replace("&apos;", "'")
        s = s.replace("&#39;", "'")
        s = s.replace("&#x27;", "'")
        s = s.replace("&lt;", "<")
        s = s.replace("&gt;", ">")
        s = s.replace("&#10;", "\n")
        s = s.replace("&#13;", "\r")
        // Numeric entities (decimal and hex), any code point.
        val hexRegex = Regex("&#x([0-9A-Fa-f]+);")
        s = hexRegex.replace(s) { m: MatchResult ->
            m.groupValues[1].toIntOrNull(16)?.let { code -> Character.toChars(code).concatToString() } ?: m.value
        }
        val decRegex = Regex("&#(\\d+);")
        s = decRegex.replace(s) { m: MatchResult ->
            m.groupValues[1].toIntOrNull()?.let { code ->
                try { Character.toChars(code).concatToString() } catch (_: Throwable) { m.value }
            } ?: m.value
        }
        // &amp; last so it doesn't interfere with the literal reconstructions above (it doesn't,
        // but this is the conventional order).
        s = s.replace("&amp;", "&")
        return s
    }

    /** Collapse all whitespace runs (incl. newlines/tabs) to a single space and trim. */
    internal fun collapseWhitespace(input: String): String =
        input.replace(Regex("\\s+"), " ").trim()
}