package net.marscore.webhub.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OpenSpec improve-webapp-shell-ux task 4.2 / design D7: RFC 6266 Content-
 * Disposition parsing for the `DownloadManager` route. Covers the ext form
 * (`filename*=UTF-8''...`), the plain quoted/unquoted form, ext-form
 * preference, URL last-segment fallback, and the `download.bin` final
 * fallback, plus path-traversal stripping.
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md (targetSdk 36 > Robolectric
 * supported ceiling). `Uri` is an Android class, hence Robolectric rather
 * than a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DownloadFilenameParserTest {

    @Test
    fun quotedPlainForm() {
        assertEquals(
            "report.pdf",
            DownloadFilenameParser.parse("attachment; filename=\"report.pdf\"", "https://example.com/")
        )
    }

    @Test
    fun unquotedPlainForm() {
        assertEquals(
            "report.pdf",
            DownloadFilenameParser.parse("attachment; filename=report.pdf", null)
        )
    }

    @Test
    fun extFormUrlDecoded() {
        assertEquals(
            "report .pdf",
            DownloadFilenameParser.parse("attachment; filename*=UTF-8''report%20.pdf", null)
        )
    }

    @Test
    fun extFormPreferredOverPlain() {
        assertEquals(
            "résumé.pdf",
            DownloadFilenameParser.parse(
                "attachment; filename=\"ascii.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf",
                null
            )
        )
    }

    @Test
    fun urlFallbackWhenDispositionNull() {
        assertEquals(
            "file.zip",
            DownloadFilenameParser.parse(null, "https://example.com/path/file.zip")
        )
    }

    @Test
    fun urlFallbackEmptyLastSegment() {
        assertEquals(
            "download.bin",
            DownloadFilenameParser.parse(null, "https://example.com/path/")
        )
    }

    @Test
    fun bothNull() {
        assertEquals(
            "download.bin",
            DownloadFilenameParser.parse(null, null)
        )
    }

    @Test
    fun bothEmpty() {
        assertEquals(
            "download.bin",
            DownloadFilenameParser.parse("", "")
        )
    }

    @Test
    fun emptyQuotedFilenameFallsBackToUrl() {
        assertEquals(
            "x.bin",
            DownloadFilenameParser.parse("attachment; filename=\"\"", "https://example.com/x.bin")
        )
    }

    @Test
    fun pathTraversalStripped() {
        assertEquals(
            "etcpasswd",
            DownloadFilenameParser.parse("attachment; filename=\"../etc/passwd\"", null)
        )
    }

    /**
     * D7 (revised) robustness: a `blob:` URL passed to the parser must not
     * crash. In production the scheme gate in `WebAppActivity.handleDownload`
     * prevents blob URLs from ever reaching the builder — this test just
     * documents that the parser degrades gracefully. `Uri.parse("blob:…")`
     * yields a non-empty lastPathSegment (the UUID), so we assert non-empty
     * rather than forcing a specific value.
     */
    @Test
    fun blobUrl_doesNotCrash_returnsNonEmpty() {
        val result = DownloadFilenameParser.parse(null, "blob:https://example.com/uuid-1234")
        assertTrue("blob URL should yield a non-empty filename, got: $result", result.isNotEmpty())
    }
}