package net.marscore.webhub.shell

import android.content.Intent
import android.provider.MediaStore

/**
 * File-chooser Intent construction for the HTML input-file flow in
 * `WebAppActivity.ChromeClient.onShowFileChooser`.
 *
 * OpenSpec improve-webapp-shell-ux task 3.1 / design D5.
 *
 * `onShowFileChooser` receives the accept-types array and an allow-multiple
 * flag from the WebView and is responsible for building the picker Intent that
 * the system will use. The construction is pure (no Activity/widget touched),
 * so it is pulled out into this internal helper for Robolectric unit testing —
 * mirrors the `TaskIconResolver` / `ThemeColorReader` / `ZoomScale` pattern.
 *
 * Behavior contract:
 *  - `normalizeAcceptTypes` strips empty strings; if nothing remains (or the
 *    array was empty) it falls back to the single wildcard MIME so the picker
 *    always opens.
 *  - `buildContentIntent` always sets the Intent type to the wildcard MIME and
 *    uses `EXTRA_MIME_TYPES` for fine filtering (the documented way to pass
 *    multiple MIME types to `ACTION_GET_CONTENT`). `EXTRA_MIME_TYPES` is
 *    omitted when the normalized array is exactly the single wildcard (redundant).
 *    `EXTRA_ALLOW_MULTIPLE` is set whenever `allowMultiple` is true.
 *  - `buildCaptureIntent` returns an `IMAGE_CAPTURE` or `VIDEO_CAPTURE` Intent
 *    only when the normalized accept types contain an image or video wildcard.
 *    When both are present, image wins (camera-photo is the common case). No
 *    `EXTRA_OUTPUT` is added — the system camera app handles its own output
 *    (per design D8 risk note: a `FileProvider` output path is a later
 *    increment, not this change).
 */
internal object FileChooserHandler {

    /**
     * Filter empty strings from `raw`; if nothing remains (or `raw` is empty),
     * return the single wildcard MIME as the safe fallback so the picker always
     * opens.
     */
    fun normalizeAcceptTypes(raw: Array<String>): Array<String> {
        val filtered = raw.filter { it.isNotEmpty() }
        return if (filtered.isEmpty()) arrayOf("*/*") else filtered.toTypedArray()
    }

    /**
     * Build an `ACTION_GET_CONTENT` Intent:
     *  - `Category OPENABLE`.
     *  - Intent type set to the wildcard MIME (always; fine filtering is via
     *    `EXTRA_MIME_TYPES`).
     *  - `EXTRA_MIME_TYPES` set to `acceptTypes` unless it is exactly the single
     *    wildcard (redundant in that case, so we skip it to keep the Intent clean).
     *  - `EXTRA_ALLOW_MULTIPLE` set when `allowMultiple` is true.
     *
     * The caller is expected to pass already-normalized accept types
     * (see [normalizeAcceptTypes]).
     */
    fun buildContentIntent(acceptTypes: Array<String>, allowMultiple: Boolean): Intent {
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            if (acceptTypes.size != 1 || acceptTypes[0] != "*/*") {
                putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
            }
            if (allowMultiple) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
    }

    /**
     * Build a camera-capture Intent when the normalized accept types name a
     * concrete image or video MIME group:
     *  - contains an image wildcard → `MediaStore.ACTION_IMAGE_CAPTURE`.
     *  - contains a video wildcard → `MediaStore.ACTION_VIDEO_CAPTURE`.
     *  - both present → `IMAGE_CAPTURE` preferred (camera-photo is the common
     *    input-accept-image-video-capture case).
     *  - neither → `null`; caller falls back to [buildContentIntent].
     *
     * No `EXTRA_OUTPUT` is added — the system camera app handles its own output
     * (design D8 risk note; a `FileProvider` output path is a later increment).
     */
    fun buildCaptureIntent(acceptTypes: Array<String>): Intent? {
        val hasImage = acceptTypes.any { it.equals("image/*", ignoreCase = true) }
        val hasVideo = acceptTypes.any { it.equals("video/*", ignoreCase = true) }
        return when {
            hasImage -> Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            hasVideo -> Intent(MediaStore.ACTION_VIDEO_CAPTURE)
            else -> null
        }
    }
}