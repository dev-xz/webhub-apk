package net.marscore.webhub.shell

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.ServiceWorkerClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * ServiceWorker request cookie compensation (round-2 problem SW-401).
 *
 * Problem: when the shell uses androidx.webkit MULTI_PROFILE for per-child isolation, Chromium
 * routes ServiceWorker (SW) network requests through a path that does **not** carry the isolated
 * profile's cookies. For cookie-auth sites (e.g. self-hosted code-server), the SW script fetch
 * returns 401 and the SW fails to register — breaking in-app embedded previews (VS Code webviews,
 * image previews, ...). The main page itself loads fine because normal WebView requests DO carry
 * the profile cookies.
 *
 * Fix: install a [ServiceWorkerClient] on the WebView's profile controller that re-issues the SW
 * request with the profile's cookies attached, then wraps the response as a [WebResourceResponse].
 *
 * Behaviour contract (see AGENTS.md shell lane):
 *  - Only GET/HEAD are intercepted; everything else returns null (default handling).
 *  - If the profile has no cookie for the URL, returns null — no point re-issuing an unauthenticated
 *    request; let Chromium's default fetch happen.
 *  - On any exception, returns null and logs [Log.w]. **Never** let the interceptor break page
 *    loading — this is a pure best-effort shim.
 *  - Non-2xx responses (e.g. 401) are returned **as-is** rather than swallowed: the SW registration
 *    needs to observe the real status to fail correctly, and the cookies may simply be stale.
 *  - 10s connect/read timeout per the spec.
 *  - Follows redirects (default HttpURLConnection behavior) so the cookies still apply across
 *    same-site hops; response headers/status reflect the final hop.
 *
 * The "should we intercept" decision is split into [shouldIntercept] (pure, testable — only depends
 * on the request method + a [CookieSource]) and the network round-trip [intercept] (not unit-tested
 * — real network stays out of the suite per AGENTS.md).
 *
 * [CookieSource] is a tiny indirection so the decision can be unit-tested with a fake cookie
 * manager instead of Robolectric's WebView cookie machinery.
 */
internal class ServiceWorkerInterceptor(
    private val cookieSource: CookieSource,
    private val userAgent: String,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) {

    /** Minimal view of [CookieManager] the interceptor needs (for test fakes). */
    internal fun interface CookieSource {
        /** `CookieManager.getCookie(url)` equivalent: `name=value; name2=value2` or null. */
        fun getCookie(url: String): String?
    }

    /** Real [CookieManager] wrapper. */
    internal class RealCookieSource(private val cm: CookieManager) : CookieSource {
        override fun getCookie(url: String): String? = try {
            cm.getCookie(url)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Pure decision: should the interceptor handle [request]?
     *
     * True iff the method is GET or HEAD AND the profile has at least one cookie for the request
     * URL. Returns the resolved URL string on true, null on false. Exposed for unit testing.
     */
    internal fun shouldIntercept(request: WebResourceRequest): String? {
        val method = request.method?.uppercase(Locale.ROOT) ?: return null
        if (method != "GET" && method != "HEAD") return null
        val url = request.url ?: return null
        val urlStr = url.toString()
        val cookies = try {
            cookieSource.getCookie(urlStr)
        } catch (_: Throwable) {
            return null
        }
        if (cookies.isNullOrBlank()) return null
        return urlStr
    }

    /**
     * Perform the cookie-bearing fetch and wrap as [WebResourceResponse]. Returns null on any
     * failure (caller falls back to default). Non-2xx responses are returned truthfully.
     */
    internal fun intercept(request: WebResourceRequest): WebResourceResponse? {
        val urlStr = shouldIntercept(request) ?: return null
        val method = request.method?.uppercase(Locale.ROOT) ?: "GET"
        return try {
            fetch(urlStr, method)?.toWebResourceResponse()
        } catch (e: Throwable) {
            Log.w(TAG, "SW intercept failed for $urlStr", e)
            null
        }
    }

    /** The [ServiceWorkerClient] handed to [android.webkit.ServiceWorkerController.setServiceWorkerClient]. */
    internal fun asClient(): ServiceWorkerClient = object : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
            this@ServiceWorkerInterceptor.intercept(request)
    }

    // ---------------- network round-trip (not unit-tested) ----------------

    internal data class RawResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val mimeType: String,
        val encoding: String?,
        val headers: Map<String, String>,
        val body: ByteArray,
    )

    @Throws(IOException::class)
    internal fun fetch(urlStr: String, method: String): RawResponse? {
        val methodHasBody = method == "GET" || method == "HEAD"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Cookie", cookieSource.getCookie(urlStr))
                if (userAgent.isNotBlank()) setRequestProperty("User-Agent", userAgent)
                // Hint that we'll handle gzip/deflate ourselves only if we don't; default lets
                // HttpURLConnection transparently decompress when we leave Accept-Encoding alone.
                connect()
            }
            val status = conn.responseCode
            // 401/etc still have a header stream; only -1 (no response) is truly unusable.
            if (status == -1) return null
            val reason = conn.responseMessage ?: ""
            val stream: InputStream = (conn.errorStream ?: conn.inputStream)
            val body = stream.readBytes()
            val contentType = conn.getContentType() ?: "application/octet-stream"
            val (mime, charset) = parseContentType(contentType)
            val headers = mutableMapOf<String, String>()
            var i = 1
            while (true) {
                val key = conn.getHeaderFieldKey(i) ?: break
                val value = conn.getHeaderField(i)
                if (value != null) {
                    // WebResourceResponse headers are case-insensitive on lookup; preserve as-is.
                    headers[key] = value
                }
                i++
            }
            RawResponse(status, reason, mime, charset, headers, body)
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    internal fun RawResponse.toWebResourceResponse(): WebResourceResponse {
        val input = ByteArrayInputStream(body)
        return WebResourceResponse(
            mimeType,
            encoding ?: "UTF-8",
            statusCode,
            reasonPhrase.ifEmpty { defaultReason(statusCode) },
            headers,
            input,
        )
    }

    /** Split `text/html; charset=utf-8` → ("text/html", "utf-8"); defaults on parse gaps. */
    internal fun parseContentType(ct: String): Pair<String, String?> {
        val parts = ct.split(";").map { it.trim() }
        val mime = parts.firstOrNull()?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        var charset: String? = null
        for (p in parts.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) {
                val k = p.substring(0, eq).trim().lowercase(Locale.ROOT)
                val v = p.substring(eq + 1).trim().trim('"')
                if (k == "charset") charset = v
            }
        }
        return mime to charset
    }

    internal fun defaultReason(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "Unknown"
    }

    private companion object {
        private const val TAG = "WebHub/SWInterceptor"
    }
}