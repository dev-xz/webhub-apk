package net.marscore.webhub.icons

import kotlinx.coroutines.test.runTest
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
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Flow tests for [TitleFetcher] against a hand-rolled local HTTP server.
 *
 * Copies the MiniHttpServer pattern from [FaviconFetcherFlowTest] — MockWebServer is broken in
 * this Robolectric env (AGENTS.md). Robolectric @Config(sdk=[33]) per AGENTS.md.
 *
 * Covers: normal title, missing title, redirect-then-title, HTML entity unescape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class TitleFetcherTest {

    // ---------------- Mini HTTP server ----------------

    /**
     * A minimal HTTP/1.1 responder. Routes map path → (code, body, headers). Supports a redirect
     * route (302 + Location). 404 for unknown paths.
     */
    private class MiniHttpServer {
        private val socket = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        private val routes = mutableMapOf<String, Route>()
        @Volatile private var stopped = false

        val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

        data class Route(val code: Int, val body: ByteArray, val headers: Map<String, String>)

        fun serve(path: String, code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) {
            routes[path] = Route(code, body, headers)
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
                // Drain headers so keep-alive doesn't confuse the reader.
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val route = routes[path] ?: Route(404, ByteArray(0), emptyMap())
                val reason = if (route.code == 200) "OK"
                    else if (route.code == 302) "Found"
                    else "Not Found"
                val out = client.getOutputStream()
                val sb = StringBuilder()
                sb.append("HTTP/1.1 ${route.code} $reason\r\n")
                sb.append("Content-Length: ${route.body.size}\r\n")
                for ((k, v) in route.headers) sb.append("$k: $v\r\n")
                sb.append("Connection: close\r\n\r\n")
                out.write(sb.toString().toByteArray(Charsets.US_ASCII))
                out.write(route.body)
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

    @Test fun extractsNormalTitle() = runTest {
        server.serve("/", 200, "<html><head><title>My Site</title></head></html>".toByteArray())
        val title = TitleFetcher.fetch(server.baseUrl)
        assertEquals("My Site", title)
    }

    @Test fun returnsNullWhenTitleMissing() = runTest {
        server.serve("/", 200, "<html><head></head><body>no title here</body></html>".toByteArray())
        val title = TitleFetcher.fetch(server.baseUrl)
        assertNull(title)
    }

    @Test fun followsRedirectThenExtractsTitle() = runTest {
        // "/" redirects to "/landing"; "/landing" has the title.
        server.serve(
            "/", 302, ByteArray(0),
            mapOf("Location" to "/landing")
        )
        server.serve("/landing", 200, "<html><head><title>Welcome</title></head></html>".toByteArray())
        val title = TitleFetcher.fetch(server.baseUrl)
        assertEquals("Welcome", title)
    }

    @Test fun unescapesHtmlEntities() = runTest {
        val html = "<html><head><title>A &amp; B &lt;C&gt; &quot;q&quot; &#39;ap&#x27;apo&#39;s</title></head></html>"
        server.serve("/", 200, html.toByteArray())
        val title = TitleFetcher.fetch(server.baseUrl)
        assertNotNull(title)
        assertEquals("""A & B <C> "q" 'ap'apo's""", title)
    }

    @Test fun collapsesWhitespaceAndTrims() = runTest {
        val html = "<html><head><title>   Spaced\n  Out\t Title   </title></head></html>"
        server.serve("/", 200, html.toByteArray())
        val title = TitleFetcher.fetch(server.baseUrl)
        assertEquals("Spaced Out Title", title)
    }

    @Test fun returnsNullOn404() = runTest {
        // No route registered → 404.
        val title = TitleFetcher.fetch(server.baseUrl)
        assertNull(title)
    }

    // ---- pure helper unit tests (no network) ----

    @Test fun extractTitleCaseInsensitive() {
        val html = "<HTML><HEAD><TITLE>Upper</TITLE></HEAD></HTML>"
        assertEquals("Upper", TitleFetcher.extractTitle(html))
    }

    @Test fun extractTitleMultiline() {
        val html = "<title>\n  Multi\n  Line\n</title>"
        val raw = TitleFetcher.extractTitle(html)
        assertNotNull(raw)
        assertTrue(raw!!.contains("Multi"))
    }

    @Test fun unescapeNumericDecimalAndHex() {
        assertEquals("A£€", TitleFetcher.unescapeEntities("A&#163;&#8364;"))
        assertEquals("A£€", TitleFetcher.unescapeEntities("A&#xA3;&#x20AC;"))
    }

    @Test fun collapseWhitespaceReplacesRuns() {
        assertEquals("a b c", TitleFetcher.collapseWhitespace("a\n\t  b\r\n c"))
    }
}