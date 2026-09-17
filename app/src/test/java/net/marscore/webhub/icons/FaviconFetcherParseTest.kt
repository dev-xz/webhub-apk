package net.marscore.webhub.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaviconFetcherParseTest {

    private val fetcher = FaviconFetcher()

    @Test fun parsesAppleTouchIconAndRegularIcons() {
        val html = """
            <html><head>
            <link rel="icon" href="/favicon.ico" sizes="32x32">
            <link rel="shortcut icon" href="/favicon16.ico" sizes="16x16">
            <link rel="apple-touch-icon" href="/apple-touch-icon.png" sizes="180x180">
            </head></html>
        """.trimIndent()
        val candidates = fetcher.parseIconLinks(html, "https://example.com/")
        // Apple-touch should rank first (priority 0).
        assertEquals("apple-touch-icon.png", candidates.first().url.substringAfterLast('/'))
        assertTrue(candidates.any { it.url == "https://example.com/favicon.ico" })
        assertTrue(candidates.any { it.url == "https://example.com/apple-touch-icon.png" })
    }

    @Test fun prefersLargerSizesAmongSameRelation() {
        val html = """
            <html><head>
            <link rel="icon" href="/small.ico" sizes="16x16">
            <link rel="icon" href="/big.png" sizes="192x192">
            </head></html>
        """.trimIndent()
        val candidates = fetcher.parseIconLinks(html, "https://example.com/")
        assertEquals("big.png", candidates.first().url.substringAfterLast('/'))
    }

    @Test fun ignoresNonIconLinks() {
        val html = """<html><head><link rel="stylesheet" href="/style.css"></head></html>"""
        val candidates = fetcher.parseIconLinks(html, "https://example.com/")
        assertTrue(candidates.isEmpty())
    }

    @Test fun resolvesRelativeAndAbsoluteUrls() {
        val html = """
            <html><head>
            <link rel="icon" href="https://cdn.example.com/icon.png">
            <link rel="icon" href="/local.png">
            <link rel="icon" href="../up.png">
            </head></html>
        """.trimIndent()
        val candidates = fetcher.parseIconLinks(html, "https://example.com/sub/page")
        assertTrue(candidates.any { it.url == "https://cdn.example.com/icon.png" })
        assertTrue(candidates.any { it.url == "https://example.com/local.png" })
        assertTrue(candidates.any { it.url == "https://example.com/up.png" })
    }
}