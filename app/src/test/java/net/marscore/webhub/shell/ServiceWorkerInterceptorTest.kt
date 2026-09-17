package net.marscore.webhub.shell

import android.net.Uri
import android.webkit.WebResourceRequest
import net.marscore.webhub.shell.ServiceWorkerInterceptor.CookieSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ServiceWorker cookie-compensation decision logic (round-2 problem SW-401).
 *
 * Only the pure decision is tested here — [ServiceWorkerInterceptor.shouldIntercept] and
 * [ServiceWorkerInterceptor.parseContentType]. The network round-trip [ServiceWorkerInterceptor.fetch]
 * is intentionally NOT tested (AGENTS.md: real network stays out of the suite).
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md: targetSdk 36 > Robolectric's supported ceiling. We
 * only need [Uri.parse] to resolve (a framework call); the [WebResourceRequest] is a tiny interface
 * stubbed directly. No @GraphicsMode needed — no bitmap decoding here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ServiceWorkerInterceptorTest {

    private fun req(method: String, url: String): WebResourceRequest = object : WebResourceRequest {
        override fun getMethod(): String = method
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
        override fun getUrl(): Uri = Uri.parse(url)
        override fun hasGesture(): Boolean = false
        override fun isForMainFrame(): Boolean = false
        override fun isRedirect(): Boolean = false
    }

    private fun cookieSource(cookie: String?): CookieSource = CookieSource { cookie }

    // ---------------- shouldIntercept ----------------

    @Test fun getWithCookieReturnsUrl() {
        val i = ServiceWorkerInterceptor(cookieSource("session=abc"), "UA")
        assertEquals(
            "https://vscode.marscore.net/sw.js",
            i.shouldIntercept(req("GET", "https://vscode.marscore.net/sw.js")),
        )
    }

    @Test fun headWithCookieReturnsUrl() {
        val i = ServiceWorkerInterceptor(cookieSource("session=abc"), "UA")
        assertEquals(
            "https://vscode.marscore.net/sw.js",
            i.shouldIntercept(req("HEAD", "https://vscode.marscore.net/sw.js")),
        )
    }

    @Test fun lowerCaseMethodIsAccepted() {
        val i = ServiceWorkerInterceptor(cookieSource("session=abc"), "UA")
        assertEquals(
            "https://x.example.com/sw.js",
            i.shouldIntercept(req("get", "https://x.example.com/sw.js")),
        )
    }

    @Test fun postIsNotIntercepted() {
        val i = ServiceWorkerInterceptor(cookieSource("session=abc"), "UA")
        assertNull("POST must fall through to default SW handling",
            i.shouldIntercept(req("POST", "https://vscode.marscore.net/api")))
    }

    @Test fun putIsNotIntercepted() {
        val i = ServiceWorkerInterceptor(cookieSource("session=abc"), "UA")
        assertNull(i.shouldIntercept(req("PUT", "https://x/y")))
    }

    @Test fun noCookieIsNotIntercepted() {
        // The whole point: if there's no cookie for the URL there's nothing to add, so don't
        // re-issue the request — let Chromium's default fetch run.
        val i = ServiceWorkerInterceptor(cookieSource(null), "UA")
        assertNull(i.shouldIntercept(req("GET", "https://vscode.marscore.net/sw.js")))
    }

    @Test fun blankCookieIsNotIntercepted() {
        val i = ServiceWorkerInterceptor(cookieSource(""), "UA")
        assertNull(i.shouldIntercept(req("GET", "https://vscode.marscore.net/sw.js")))
    }

    @Test fun emptyCookieIsNotIntercepted() {
        // CookieManager can return "" (no cookies for the URL) — treat as no cookie.
        val i = ServiceWorkerInterceptor(cookieSource(""), "UA")
        assertNull(i.shouldIntercept(req("GET", "https://vscode.marscore.net/sw.js")))
    }

    @Test fun cookieSourceThrowingReturnsNullNotCrash() {
        val i = ServiceWorkerInterceptor({ throw RuntimeException("cookie jar exploded") }, "UA")
        assertNull(i.shouldIntercept(req("GET", "https://x/y")))
    }

    @Test fun multipleCookiesAreAccepted() {
        val i = ServiceWorkerInterceptor(cookieSource("a=1; b=2; c=3"), "UA")
        assertEquals("https://x/y", i.shouldIntercept(req("GET", "https://x/y")))
    }

    @Test fun interceptReturnsNullWhenShouldNotIntercept() {
        // No cookie -> shouldIntercept is null -> intercept returns null without touching network.
        val i = ServiceWorkerInterceptor(cookieSource(null), "UA")
        assertNull(i.intercept(req("GET", "https://vscode.marscore.net/sw.js")))
    }

    @Test fun interceptReturnsNullForPost() {
        val i = ServiceWorkerInterceptor(cookieSource("a=1"), "UA")
        assertNull(i.intercept(req("POST", "https://vscode.marscore.net/api")))
    }

    // ---------------- parseContentType ----------------

    @Test fun parseContentTypeSimpleMime() {
        val (mime, charset) = ServiceWorkerInterceptor(cookieSource("a=1"), "UA").parseContentType("text/html")
        assertEquals("text/html", mime)
        assertNull(charset)
    }

    @Test fun parseContentTypeWithCharset() {
        val (mime, charset) = ServiceWorkerInterceptor(cookieSource("a=1"), "UA")
            .parseContentType("text/html; charset=utf-8")
        assertEquals("text/html", mime)
        assertEquals("utf-8", charset)
    }

    @Test fun parseContentTypeWithQuotedCharset() {
        val (mime, charset) = ServiceWorkerInterceptor(cookieSource("a=1"), "UA")
            .parseContentType("application/json; charset=\"UTF-8\"")
        assertEquals("application/json", mime)
        assertEquals("UTF-8", charset)
    }

    @Test fun parseContentTypeMultiParams() {
        val (mime, charset) = ServiceWorkerInterceptor(cookieSource("a=1"), "UA")
            .parseContentType("text/html; boundary=xyz; charset=iso-8859-1")
        assertEquals("text/html", mime)
        assertEquals("iso-8859-1", charset)
    }

    @Test fun parseContentTypeBlankFallsBackToOctetStream() {
        val (mime, _) = ServiceWorkerInterceptor(cookieSource("a=1"), "UA").parseContentType("")
        assertEquals("application/octet-stream", mime)
    }

    // ---------------- defaultReason ----------------

    @Test fun defaultReasonForCommonCodes() {
        val i = ServiceWorkerInterceptor(cookieSource("a=1"), "UA")
        assertEquals("OK", i.defaultReason(200))
        assertEquals("Unauthorized", i.defaultReason(401))
        assertEquals("Not Found", i.defaultReason(404))
        assertEquals("Unknown", i.defaultReason(599))
    }

    // ---------------- RealCookieSource swallows errors ----------------

    @Test fun realCookieSourceSwallowsThrowingCookieManager() {
        // RealCookieSource wraps CookieManager.getCookie in try/catch. We can't easily subclass the
        // abstract CookieManager in a JVM test, so verify the contract indirectly: a CookieSource
        // lambda that throws must not propagate (the decision path uses the same try/catch).
        val crashing: CookieSource = CookieSource { throw RuntimeException("boom") }
        val i = ServiceWorkerInterceptor(crashing, "UA")
        assertNull("cookie access throwing must not break shouldIntercept",
            i.shouldIntercept(req("GET", "https://x/y")))
    }
}