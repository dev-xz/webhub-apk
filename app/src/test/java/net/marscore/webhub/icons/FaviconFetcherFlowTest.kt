package net.marscore.webhub.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Flow tests for [FaviconFetcher] against a real local HTTP server.
 *
 * Uses a tiny hand-rolled [ServerSocket] HTTP responder instead of MockWebServer: okhttp's
 * deprecated `mockwebserver` 4.12.0 wrapper misbehaves in this Robolectric env (enqueued
 * responses are never served — dispatch always falls through to 404), `mockwebserver3` is not
 * resolvable at 4.12.0, and `com.sun.net.httpserver.HttpServer` is not on the Android unit-test
 * compile classpath. `java.net.ServerSocket` is in Android's API surface and runs as the real
 * JDK implementation in local unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaviconFetcherFlowTest {

    // ---------------- Mini HTTP server (path → (code, body), 404 for unknown paths) ----------------

    private class MiniHttpServer {
        private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        private val routes = mutableMapOf<String, Pair<Int, ByteArray>>()
        @Volatile private var stopped = false

        val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

        fun serve(path: String, code: Int, body: ByteArray) {
            routes[path] = code to body
        }

        fun start() {
            thread(isDaemon = true) {
                while (!stopped) {
                    val client = try { socket.accept() } catch (e: Exception) { break }
                    handle(client)
                }
            }
        }

        fun stop() {
            stopped = true
            try { socket.close() } catch (_: Exception) {}
        }

        private fun handle(client: Socket) {
            try {
                client.soTimeout = 3000
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return client.closeQuietly()
                val path = requestLine.split(' ').getOrNull(1) ?: return client.closeQuietly()
                val (code, body) = routes[path] ?: (404 to ByteArray(0))
                val out = client.getOutputStream()
                val head = "HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\n" +
                    "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray(Charsets.US_ASCII))
                out.write(body)
                out.flush()
            } catch (_: Exception) {
            } finally {
                client.closeQuietly()
            }
        }

        private fun Socket.closeQuietly() = try { close() } catch (_: Exception) {}
    }

    private lateinit var server: MiniHttpServer

    @Before fun setup() {
        server = MiniHttpServer()
        server.start()
    }

    @After fun teardown() {
        server.stop()
    }

    private fun pngBytes(w: Int, h: Int, color: Int = Color.GREEN): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** Build a minimal ICO wrapping one PNG payload (header + entry + payload). */
    private fun icoBytes(png: ByteArray, w: Int, h: Int): ByteArray {
        val headerSize = 6 + 16
        val out = ByteArrayOutputStream()
        // ICONDIR
        out.write(0); out.write(0); out.write(1); out.write(0); out.write(1); out.write(0)
        // ICONDIRENTRY
        out.write(if (w >= 256) 0 else w); out.write(if (h >= 256) 0 else h)
        out.write(0); out.write(0); out.write(1); out.write(0); out.write(8); out.write(0)
        val size = png.size
        out.write(size and 0xFF); out.write((size ushr 8) and 0xFF)
        out.write((size ushr 16) and 0xFF); out.write((size ushr 24) and 0xFF)
        out.write(headerSize and 0xFF); out.write((headerSize ushr 8) and 0xFF)
        out.write((headerSize ushr 16) and 0xFF); out.write((headerSize ushr 24) and 0xFF)
        out.write(png)
        return out.toByteArray()
    }

    private fun fetcher(): FaviconFetcher {
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .callTimeout(2, TimeUnit.SECONDS)
            .build()
        return FaviconFetcher(http)
    }

    @Test fun fetchesRawPngFromFaviconIco() = runTest {
        // Site serves a PNG (mislabeled as .ico) at /favicon.ico.
        server.serve("/favicon.ico", 200, pngBytes(48, 48))
        val result = fetcher().fetch("${server.baseUrl}/favicon.ico")
        assertNotNull(result.bitmap)
        assertEquals(48, result.bitmap!!.width)
        assertNull(result.error)
    }

    @Test fun fetchesTrueIcoContainer() = runTest {
        server.serve("/favicon.ico", 200, icoBytes(pngBytes(32, 32, Color.RED), 32, 32))
        val result = fetcher().fetch("${server.baseUrl}/favicon.ico")
        assertNotNull("true ICO container should decode via IcoDecoder", result.bitmap)
        assertEquals(32, result.bitmap!!.width)
        assertNull(result.error)
    }

    @Test fun fallsBackToHtmlLinkWhenIcoFails() = runTest {
        // /favicon.ico has no route → 404; the page HTML declares an apple-touch-icon PNG.
        val html = """<html><head>
            <link rel="apple-touch-icon" href="/apple-touch-icon.png" sizes="180x180">
            </head></html>""".trimIndent()
        server.serve("/", 200, html.toByteArray())
        server.serve("/apple-touch-icon.png", 200, pngBytes(64, 64, Color.BLUE))
        val result = fetcher().fetch(server.baseUrl)
        assertNotNull(result.bitmap)
        assertEquals(64, result.bitmap!!.width)
        assertNull(result.error)
    }

    @Test fun returnsErrorWhenNothingDecodes() = runTest {
        // /favicon.ico serves garbage; the page 404s → nothing left to try.
        server.serve("/favicon.ico", 200, "not an image at all".toByteArray())
        val result = fetcher().fetch(server.baseUrl)
        assertNull(result.bitmap)
        assertNotNull(result.error)
        // Error is a short lowercase machine reason.
        assertTrue("error reason must be short lowercase token", result.error!!.matches(Regex("[a-z0-9_]+")))
    }

    // ---- revamp-hub-ux round-2: HTML <link> fallback edge cases ----

    @Test fun fallsBackToHtmlLinkWithRelativePath() = runTest {
        // /favicon.ico 404; the page HTML declares a relative ./_static/... PNG icon.
        val html = """<html><head>
            <link rel="icon" href="./_static/src/favicon.png">
            </head></html>""".trimIndent()
        server.serve("/favicon.ico", 404, ByteArray(0))
        server.serve("/", 200, html.toByteArray())
        server.serve("/_static/src/favicon.png", 200, pngBytes(64, 64, Color.MAGENTA))
        val result = fetcher().fetch(server.baseUrl)
        assertNotNull("relative ./_static/ PNG should be fetched after /favicon.ico 404", result.bitmap)
        assertEquals(64, result.bitmap!!.width)
        assertNull(result.error)
    }

    @Test fun htmlWithOnlySvgIconYieldsError() = runTest {
        // /favicon.ico 404; the page HTML only declares an SVG icon (BitmapFactory can't decode).
        val html = """<html><head>
            <link rel="icon" href="./_static/src/browser/media/favicon-dark-support.svg">
            <link rel="alternate icon" href="./_static/src/browser/media/favicon.ico">
            </head></html>""".trimIndent()
        server.serve("/favicon.ico", 404, ByteArray(0))
        server.serve("/", 200, html.toByteArray())
        // Serve a non-image body at the .ico href so the candidate also fails to decode.
        server.serve("/_static/src/browser/media/favicon.ico", 200, "garbage".toByteArray())
        val result = fetcher().fetch(server.baseUrl)
        assertNull("SVG-only candidates must not yield a bitmap", result.bitmap)
        assertNotNull(result.error)
    }

    @Test fun faviconIco401JsonBodyFallsBackToAppleTouchIcon() = runTest {
        // Reproduces the vscode.marscore.net:31410 case: /favicon.ico returns 401 application/json
        // (a 24-byte auth-error body), but the public login-page HTML declares an apple-touch-icon
        // PNG that decodes.
        val authErrorBody = """{"error":"unauthorized"}""".toByteArray()
        val html = """<html><head>
            <link rel="icon" href="./_static/src/browser/media/favicon-dark-support.svg" />
            <link rel="alternate icon" href="./_static/src/browser/media/favicon.ico" />
            <link rel="apple-touch-icon" sizes="192x192" href="./_static/src/browser/media/pwa-icon-192.png" />
            </head></html>""".trimIndent()
        server.serve("/favicon.ico", 401, authErrorBody)
        server.serve("/", 200, html.toByteArray())
        // The .ico href at the HTML link also serves garbage; the SVG is skipped; the PNG wins.
        server.serve("/_static/src/browser/media/favicon.ico", 200, "garbage".toByteArray())
        server.serve("/_static/src/browser/media/pwa-icon-192.png", 200, pngBytes(48, 48, Color.CYAN))
        val result = fetcher().fetch(server.baseUrl)
        assertNotNull("401 on /favicon.ico must fall back to the apple-touch-icon PNG", result.bitmap)
        assertEquals(48, result.bitmap!!.width)
        assertNull(result.error)
    }
}
