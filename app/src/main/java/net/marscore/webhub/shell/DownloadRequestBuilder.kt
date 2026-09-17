package net.marscore.webhub.shell

import android.app.DownloadManager
import android.net.Uri
import android.os.Environment

/**
 * Builds the `DownloadManager.Request` for the WebView `DownloadListener`
 * route (design D7).
 *
 * OpenSpec improve-webapp-shell-ux task 4.1 / 4.3.
 *
 * The Request-construction is pulled out of `WebAppActivity` so the pieces
 * that can be exercised under Robolectric (filename via
 * [DownloadFilenameParser], referer + UA + mime wiring) are testable without
 * standing up the full Activity. `DownloadManager.Request` itself is an
 * Android class — Robolectric's shadow supports the setter accessors used
 * here.
 *
 * Behavior:
 *  - `addRequestHeader("Referer", referer)` and
 *    `addRequestHeader("User-Agent", userAgent)` (per design: there is no
 *    `setUserAgent` on `Request`, so a header is the only route).
 *  - `addRequestHeader("Cookie", cookie)` when a non-empty cookie header value
 *    is supplied — forwards the WebView's session cookies so auth-gated
 *    downloads (attachments/exports on logged-in sites) don't 401/403.
 *  - `setMimeType(mimetype)` only when non-null/non-empty.
 *  - `setDestinationInExternalPublicDir(DIRECTORY_DOWNLOADS, filename)` —
 *    public Downloads dir; API 29+ needs no write permission.
 *  - `setNotificationVisibility(VISIBILITY_VISIBLE)` — system's own
 *    in-progress notification.
 */
internal object DownloadRequestBuilder {

    /**
     * Build a `DownloadManager.Request` for a WebView download.
     *
     * @param url                the download URL.
     * @param userAgent          the WebView's current User-Agent (already the
     *                           child's configured UA).
     * @param referer            the page that triggered the download (usually
     *                           `webView.url`); may be null/empty.
     * @param mimetype           the response's MIME type; null/empty → no
     *                           `setMimeType` call (let the system infer).
     * @param contentDisposition raw `Content-Disposition` header, may be null.
     * @param cookie             raw `Cookie` header value for this URL
     *                           (WebView session cookies); null/empty → no
     *                           `Cookie` header (download proceeds without it).
     * @return a configured `DownloadManager.Request` (not yet enqueued).
     */
    fun buildRequest(
        url: String,
        userAgent: String?,
        referer: String?,
        mimetype: String?,
        contentDisposition: String?,
        cookie: String?,
    ): DownloadManager.Request {
        val request = DownloadManager.Request(Uri.parse(url))
        if (!referer.isNullOrEmpty()) {
            request.addRequestHeader("Referer", referer)
        }
        if (!userAgent.isNullOrEmpty()) {
            request.addRequestHeader("User-Agent", userAgent)
        }
        if (!cookie.isNullOrEmpty()) {
            request.addRequestHeader("Cookie", cookie)
        }
        if (!mimetype.isNullOrEmpty()) {
            request.setMimeType(mimetype)
        }
        val filename = DownloadFilenameParser.parse(contentDisposition, url)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
        return request
    }
}