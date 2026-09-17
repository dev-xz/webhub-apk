package net.marscore.webhub.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Reproduces the vscode.marscore.net:31410 redirect scenario against a local server that emits a
 * RELATIVE Location header (`./login`, as Caddy does) so we can verify OkHttp (via the real
 * FaviconFetcher client) follows it and the HTML-link fallback still resolves candidates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaviconFetcherRelativeRedirectTest {

    private class RedirectHttpServer {
        private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        @Volatile private var stopped = false
        val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

        fun start() {
            thread(isDaemon = true) {
                while (!stopped) {
                    val client = try { socket.accept() } catch (e: Exception) { break }
                    handle(client)
                }
            }
        }
        fun stop() { stopped = true; try { socket.close() } catch (_: Exception) {} }

        private fun handle(client: Socket) {
            try {
                client.soTimeout = 3000
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return client.closeQuietly()
                val path = requestLine.split(' ').getOrNull(1) ?: return client.closeQuietly()
                // Drain headers.
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val out = client.getOutputStream()
                when {
                    path == "/" || path == "" -> {
                        // Root → 302 to RELATIVE ./login (Caddy-style).
                        val head = "HTTP/1.1 302 Found\r\n" +
                            "Location: ./login\r\n" +
                            "Content-Type: text/plain; charset=utf-8\r\n" +
                            "Content-Length: 29\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII))
                        out.write("Redirecting to ./login...".toByteArray())
                    }
                    path == "/login" -> {
                        // Login page with icon links (SVG skipped, PNG + ICO candidates).
                        val html = """<!doctype html><html><head>
                            <link rel="icon" href="./_static/favicon.svg" />
                            <link rel="alternate icon" href="./_static/favicon.ico" />
                            <link rel="apple-touch-icon" sizes="192x192" href="./_static/pwa-192.png" />
                            </head></html>""".trimIndent()
                        val body = html.toByteArray()
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII))
                        out.write(body)
                    }
                    path == "/_static/favicon.svg" -> {
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: image/svg+xml\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII))
                    }
                    path == "/_static/favicon.ico" -> {
                        val body = pngBytes(32, 32, Color.RED)
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: image/vnd.microsoft.icon\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII))
                        out.write(body)
                    }
                    path == "/_static/pwa-192.png" -> {
                        val body = pngBytes(48, 48, Color.BLUE)
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII))
                        out.write(body)
                    }
                    else -> {
                        val head = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII))
                    }
                }
                out.flush()
            } catch (_: Exception) {
            } finally {
                client.closeQuietly()
            }
        }

        private fun pngBytes(w: Int, h: Int, color: Int): ByteArray {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(color)
            val o = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, o); bmp.recycle()
            return o.toByteArray()
        }
        private fun Socket.closeQuietly() = try { close() } catch (_: Exception) {}
    }

    private lateinit var server: RedirectHttpServer

    @Before fun setup() { server = RedirectHttpServer(); server.start() }
    @After fun teardown() { server.stop() }

    @Test fun relativeLocationLoginRedirectIsFollowedAndIconResolved() = runTest {
        // /favicon.ico has no route → 404; root "/" → 302 ./login → 200 with PNG/ICO candidates.
        val result = FaviconFetcher().fetch(server.baseUrl)
        assertNotNull("relative ./login redirect must be followed and a candidate PNG decoded", result.bitmap)
        // apple-touch-icon (priority 0) wins; the 48×48 PNG.
        assertEquals(48, result.bitmap!!.width)
        assertNull(result.error)
    }

    @Test fun relativeLocationLoginRedirectWithTrailingSlashSiteUrl() = runTest {
        // Same as above but siteUrl carries a trailing slash (what UrlValidator.normalize produces).
        val result = FaviconFetcher().fetch("${server.baseUrl}/")
        assertNotNull(result.bitmap)
        assertNull(result.error)
    }

    // ---- deep-path redirect: relative href must resolve against the FINAL document URL ----

    private class DeepRedirectServer {
        private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        @Volatile private var stopped = false
        val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

        fun start() {
            thread(isDaemon = true) {
                while (!stopped) {
                    val client = try { socket.accept() } catch (e: Exception) { break }
                    handle(client)
                }
            }
        }
        fun stop() { stopped = true; try { socket.close() } catch (_: Exception) {} }

        private fun handle(client: Socket) {
            try {
                client.soTimeout = 3000
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return client.closeQuietly()
                val path = requestLine.split(' ').getOrNull(1) ?: return client.closeQuietly()
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val out = client.getOutputStream()
                when {
                    // Root → 302 to a DEEP path /deep/page (not a shallow /login).
                    path == "/" || path == "" -> {
                        val head = "HTTP/1.1 302 Found\r\nLocation: /deep/page\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII))
                    }
                    // Deep page carries a relative ./icon.png that must resolve to /deep/icon.png,
                    // NOT /icon.png (which is the wrong, unhosted path the old baseUrl would produce).
                    path == "/deep/page" -> {
                        val html = """<html><head>
                            <link rel="apple-touch-icon" sizes="192x192" href="./icon.png" />
                            </head></html>""".trimIndent()
                        val body = html.toByteArray()
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII)); out.write(body)
                    }
                    // The CORRECT resolved candidate: /deep/icon.png → 200 PNG.
                    path == "/deep/icon.png" -> {
                        val body = pngBytes(48, 48, Color.RED)
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII)); out.write(body)
                    }
                    // The WRONG path the old code would hit: /icon.png → 404 (proves the fix).
                    path == "/icon.png" -> {
                        val head = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII))
                    }
                    else -> {
                        val head = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII))
                    }
                }
                out.flush()
            } catch (_: Exception) {
            } finally {
                client.closeQuietly()
            }
        }
        private fun pngBytes(w: Int, h: Int, color: Int): ByteArray {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(color)
            val o = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, o); bmp.recycle()
            return o.toByteArray()
        }
        private fun Socket.closeQuietly() = try { close() } catch (_: Exception) {}
    }

    @Test fun relativeHrefResolvesAgainstFinalRedirectUrlNotRequestBaseUrl() = runTest {
        // Root redirects to a DEEP path /deep/page; the page's ./icon.png must resolve to
        // /deep/icon.png (hosted) — NOT /icon.png (404, which the pre-fix baseUrl would produce).
        val deep = DeepRedirectServer().also { it.start() }
        try {
            val result = FaviconFetcher().fetch(deep.baseUrl)
            assertNotNull("relative href must resolve against the post-redirect document URL", result.bitmap)
            assertEquals(48, result.bitmap!!.width)
            assertNull(result.error)
        } finally {
            deep.stop()
        }
    }
}