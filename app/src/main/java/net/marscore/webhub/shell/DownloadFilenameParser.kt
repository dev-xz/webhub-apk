package net.marscore.webhub.shell

import android.net.Uri
import java.net.URLDecoder

/**
 * RFC 6266 Content-Disposition filename parsing for the `DownloadManager` route
 * (design D7): turns the `contentDisposition`/`url` pair handed to
 `WebView.DownloadListener.onDownloadStart` into a single safe filename string
 * suitable for `DownloadManager.Request.setDestinationInExternalPublicDir`.
 *
 * OpenSpec improve-webapp-shell-ux task 4.2 / design D7.
 *
 * Pure helper — no Android widgets touched (only `android.net.Uri` for the URL
 * fallback), so the logic is Robolectric-unit-testable (mirrors the
 * `TaskIconResolver` / `ThemeColorReader` pattern).
 */
internal object DownloadFilenameParser {

    /** Final fallback when neither disposition nor URL yields a usable name. */
    private const val FALLBACK = "download.bin"

    /**
     * Parse a Content-Disposition header value into a safe filename.
     *
     * Supports RFC 6266:
     *  - `attachment; filename="report.pdf"`                 → `report.pdf`
     *  - `attachment; filename*=UTF-8''report%20.pdf`        → `report .pdf` (URL-decoded)
     *  - `attachment; filename="a.pdf"; filename*=UTF-8''b.pdf` → `b.pdf` (ext form preferred)
     *
     * Falls back to the last path segment of [url] when no filename is present
     * in [contentDisposition]. Final fallback [FALLBACK] (`download.bin`) when
     * both are empty/invalid.
     *
     * The result is sanitized: surrounding quotes are stripped, every `/` and
     * `\` character is removed (no path components, no path-traversal segments),
     * and leading dots are stripped (no hidden-file traversal on filesystems
     * that treat a leading dot specially). Internal dots (extension separators)
     * are preserved.
     *
     * @param contentDisposition raw `Content-Disposition` header, may be null/empty.
     * @param url                 raw download URL, may be null/empty; used for the
     *                            last-path-segment fallback.
     * @return a non-empty, path-safe filename; never null.
     */
    fun parse(contentDisposition: String?, url: String?): String {
        val fromDisposition = parseDisposition(contentDisposition)
        if (fromDisposition.isNotEmpty()) return fromDisposition

        val fromUrl = parseUrl(url)
        if (fromUrl.isNotEmpty()) return fromUrl

        return FALLBACK
    }

    /** Extract and sanitize a filename from a Content-Disposition header. */
    private fun parseDisposition(contentDisposition: String?): String {
        if (contentDisposition.isNullOrEmpty()) return ""
        val ext = parseExtFilename(contentDisposition)
        if (ext != null) return sanitize(ext)
        val plain = parsePlainFilename(contentDisposition)
        if (plain != null) return sanitize(plain)
        return ""
    }

    /**
     * RFC 5987 ext form: `filename*=UTF-8''<percent-encoded>`.
     * Returns the decoded value (pre-sanitize) or null if absent.
     */
    private fun parseExtFilename(header: String): String? {
        val tokenized = header.split(';')
        for (raw in tokenized) {
            val part = raw.trim()
            val key = "filename*="
            val idx = part.indexOf(key, ignoreCase = true)
            if (idx < 0) continue
            val rest = part.substring(idx + key.length).trim()
            val quoteIdx = rest.indexOf("''")
            if (quoteIdx < 0) continue
            val value = rest.substring(quoteIdx + 2)
            return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull()
        }
        return null
    }

    /**
     * Plain form: `filename="quoted"` or `filename=unquoted`. Returns the raw
     * value (pre-sanitize) or null if the parameter is absent. An explicitly
     * empty quoted value (`filename=""`) yields the empty string and is treated
     * as "absent" by the caller via [sanitize] returning "".
     */
    private fun parsePlainFilename(header: String): String? {
        val tokenized = header.split(';')
        for (raw in tokenized) {
            val part = raw.trim()
            val key = "filename="
            val idx = part.indexOf(key, ignoreCase = true)
            if (idx < 0) continue
            // Skip the ext-form key (`filename*=`) — it is handled elsewhere.
            if (idx > 0 && part[idx - 1] == '*') continue
            val rest = part.substring(idx + key.length).trim()
            if (rest.startsWith("\"")) {
                val end = rest.indexOf('"', 1)
                return if (end > 0) rest.substring(1, end) else rest.substring(1)
            }
            // Unquoted: take up to the next `;` or end.
            val semi = rest.indexOf(';')
            return if (semi >= 0) rest.substring(0, semi).trim() else rest
        }
        return null
    }

    /** Fall back to the last path segment of the URL; sanitize the result. */
    private fun parseUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return ""
        // A URL whose path ends with "/" (e.g. "https://host/path/") has no
        // terminal filename segment — treat it as unusable and let the caller
        // fall through to the final fallback, rather than surfacing the
        // directory name ("path") as the filename.
        val path = uri.path
        if (path != null && path.endsWith("/")) return ""
        val last = uri.lastPathSegment
        if (last.isNullOrEmpty()) return ""
        return sanitize(last)
    }

    /**
     * Strip surrounding quotes (defensive — quote removal already happens for
     * the plain form), remove every `/` and `\` character, then strip leading
     * dots. Internal dots (extension separators) are preserved. Returns "" if
     * nothing usable remains.
     */
    private fun sanitize(raw: String): String {
        var s = raw
        if (s.isEmpty()) return ""
        // Remove all path separators (collapses path components together).
        s = s.replace("/", "").replace("\\", "")
        // Strip leading dots (hidden-file / traversal protection).
        var start = 0
        while (start < s.length && s[start] == '.') start++
        s = s.substring(start)
        return s
    }
}