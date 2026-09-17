package net.marscore.webhub.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Reproduces the real-device break point: a redirect chain whose TOTAL duration exceeds the
 * call-level timeout, while each individual hop stays within the per-hop readTimeout.
 *
 * OkHttp's `callTimeout` bounds the ENTIRE call including auto-followed redirects. The default
 * FaviconFetcher client uses `callTimeout(5s)`. A chain like `/` → 302 (2s) → `/login` (2s) → 200
 * totals 4s — fine under 5s. But on a real device with a cold TLS handshake + a multi-hop
 * redirect (e.g. `/` → 302 → `/login` → 302 → `/dashboard`), each hop under 5s readTimeout but
 * the chain total over 5s would be killed by `callTimeout(5s)`. This test isolates callTimeout
 * (not readTimeout) as the limiter by using multi-hop redirects each under 5s but totaling > 5s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FaviconFetcherCallTimeoutTest {

    private class MultiHopServer(private val perHopDelayMs: Long, private val hops: Int) {
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
                client.soTimeout = 30000
                val reader = client.getInputStream().bufferedReader()
                val requestLine = reader.readLine() ?: return client.closeQuietly()
                val path = requestLine.split(' ').getOrNull(1) ?: return client.closeQuietly()
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val out = client.getOutputStream()
                // Each redirect hop delays perHopDelayMs (under 5s readTimeout); the chain totals
                // perHopDelayMs * hops, designed to exceed a 5s callTimeout while staying under
                // per-hop readTimeout. The final hop serves the icon-declaring HTML + a PNG.
                val hopIndex = path.removePrefix("/hop").substringBefore('/').toIntOrNull() ?: -1
                when {
                    path == "/" -> {
                        Thread.sleep(perHopDelayMs)
                        val head = "HTTP/1.1 302 Found\r\nLocation: /hop1\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII)); out.flush()
                    }
                    hopIndex in 1 until hops -> {
                        Thread.sleep(perHopDelayMs)
                        val head = "HTTP/1.1 302 Found\r\nLocation: /hop${hopIndex + 1}\r\n" +
                            "Content-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII)); out.flush()
                    }
                    path == "/hop$hops" || (hops == 1 && path == "/") -> {
                        Thread.sleep(perHopDelayMs)
                        val html = """<html><head>
                            <link rel="apple-touch-icon" sizes="192x192" href="/icon.png" />
                            </head></html>""".trimIndent()
                        val body = html.toByteArray()
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
                    }
                    path == "/icon.png" -> {
                        val body = pngBytes(48, 48, Color.BLUE)
                        val head = "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
                    }
                    else -> {
                        val head = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        out.write(head.toByteArray(Charsets.US_ASCII)); out.flush()
                    }
                }
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

    @Test fun multiHopChainUnderPerHopReadTimeoutButOverOldCallTimeoutSucceeds() = runTest {
        // 4 hops × 1.5s each = 6s total (exceeds the old 5s callTimeout; each hop 1.5s is under the
        // 5s per-hop readTimeout). With callTimeout(5s) this fails; with the raised callTimeout
        // it succeeds — proving the callTimeout bound is the real-device break point for redirect
        // chains whose total exceeds 5s while each hop is fast.
        val server = MultiHopServer(perHopDelayMs = 1500L, hops = 4).also { it.start() }
        try {
            val result = FaviconFetcher().fetch(server.baseUrl)
            assertNotNull("multi-hop redirect chain totaling >5s must succeed (per-hop readTimeout honored)", result.bitmap)
            assertNull(result.error)
        } finally {
            server.stop()
        }
    }
}