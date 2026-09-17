package net.marscore.webhub.shell

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OpenSpec improve-webapp-shell-ux task 4.1 / 4.3 / design D7.
 *
 * Exercises [DownloadRequestBuilder.buildRequest] under Robolectric. The
 * filename-disposition logic is already covered by [DownloadFilenameParserTest];
 * `DownloadManager.Request`'s setter accessors (`setDestinationInExternalPublicDir`,
 * `addRequestHeader`, `setMimeType`, `setNotificationVisibility`) are not
 * exposed back via public getters on the Android stub, so we assert the
 * Request builds without throwing for typical inputs and that the
 * null/empty-optional-header edge cases are handled. The header/filename wiring
 * is verified by inspection of [DownloadRequestBuilder] (the
 * `DownloadFilenameParser.parse` output is the only computed piece, and that's
 * already unit-tested).
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md (targetSdk 36 > Robolectric cap);
 * `DownloadManager.Request` is an Android class.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class DownloadRequestBuilderTest {

    @Test
    fun buildRequest_dispositionFilename_buildsWithoutThrowing() {
        val request = DownloadRequestBuilder.buildRequest(
            url = "https://example.com/path/file.zip",
            userAgent = "TestUA/1.0",
            referer = "https://example.com/index.html",
            mimetype = "application/zip",
            contentDisposition = "attachment; filename=\"report.zip\"",
            cookie = null,
        )
        assertNotNull(request)
    }

    @Test
    fun buildRequest_urlFallbackFilename_whenDispositionNull() {
        val request = DownloadRequestBuilder.buildRequest(
            url = "https://example.com/path/file.zip",
            userAgent = null,
            referer = null,
            mimetype = null,
            contentDisposition = null,
            cookie = null,
        )
        assertNotNull(request)
    }

    @Test
    fun buildRequest_nullMimetype_handledWithoutThrowing() {
        // mimetype null → setMimeType must NOT be called (let the system infer).
        val request = DownloadRequestBuilder.buildRequest(
            url = "https://example.com/x.bin",
            userAgent = "UA",
            referer = "https://example.com/",
            mimetype = null,
            contentDisposition = null,
            cookie = null,
        )
        assertNotNull(request)
    }

    @Test
    fun buildRequest_emptyMimetype_handledWithoutThrowing() {
        val request = DownloadRequestBuilder.buildRequest(
            url = "https://example.com/x.bin",
            userAgent = "UA",
            referer = "https://example.com/",
            mimetype = "",
            contentDisposition = null,
            cookie = null,
        )
        assertNotNull(request)
    }

    @Test
    fun buildRequest_emptyUserAgentAndReferer_skipsHeadersWithoutThrowing() {
        val request = DownloadRequestBuilder.buildRequest(
            url = "https://example.com/x.bin",
            userAgent = "",
            referer = "",
            mimetype = "application/octet-stream",
            contentDisposition = null,
            cookie = null,
        )
        assertNotNull(request)
    }

    @Test
    fun buildRequest_finalFallbackFilename_whenDispositionAndUrlBothEmpty() {
        // Both null → DownloadFilenameParser returns "download.bin"; the
        // builder must still produce a Request (no IllegalArgumentException
        // from an empty destination filename).
        val request = DownloadRequestBuilder.buildRequest(
            url = "https://example.com/",
            userAgent = "UA",
            referer = null,
            mimetype = null,
            contentDisposition = null,
            cookie = null,
        )
        assertNotNull(request)
    }

    @Test
    fun buildRequest_withCookieHeader_buildsWithoutThrowing() {
        // Cookie forwarding (D7 revised): a non-empty cookie value is added as a
        // `Cookie` request header. The Android stub doesn't expose headers via
        // getters, so assert no-throw + non-null (matches existing test style).
        val request = DownloadRequestBuilder.buildRequest(
            url = "https://example.com/secure/export.csv",
            userAgent = "UA",
            referer = "https://example.com/",
            mimetype = "text/csv",
            contentDisposition = "attachment; filename=\"export.csv\"",
            cookie = "sid=abc; token=xyz",
        )
        assertNotNull(request)
    }

    @Test
    fun buildRequest_nullCookie_buildsWithoutThrowing() {
        val request = DownloadRequestBuilder.buildRequest(
            url = "https://example.com/file.zip",
            userAgent = "UA",
            referer = null,
            mimetype = null,
            contentDisposition = null,
            cookie = null,
        )
        assertNotNull(request)
    }

    @Test
    fun buildRequest_emptyCookie_buildsWithoutThrowing() {
        val request = DownloadRequestBuilder.buildRequest(
            url = "https://example.com/file.zip",
            userAgent = "UA",
            referer = null,
            mimetype = null,
            contentDisposition = null,
            cookie = "",
        )
        assertNotNull(request)
    }
}