package net.marscore.webhub.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.util.TypedValue

/**
 * Composes an adaptive-icon canvas bitmap from a source [Bitmap].
 *
 * Layout follows the adaptive-icon spec: the canvas is 108dp on a side (the platform's adaptive
 * icon canvas size) and the source is centered within the inner 2/3 of that canvas — i.e. 25%
 * padding on every side — which is the safe zone guaranteed to be visible across launcher masks.
 * The resulting bitmap is suitable for [android.graphics.drawable.Icon.createWithAdaptiveBitmap].
 *
 * Per the design, the canonical canvas size derives from [android.content.pm.ShortcutManager]'s
 * reported max icon width; the adaptive-icon canvas is 1.5× that. Because the platform reports the
 * inner (safe) width as 72dp and the full canvas as 108dp, we render at 108dp × 1.5 = 162dp worth
 * of pixels on the device so the bitmap stays crisp on high-density launchers.
 */
object AdaptiveIcons {

    /**
     * @param context any context; used only to convert dp → px for the output canvas size.
     * @param source  the favicon/uploaded image to embed; it is center-cropped to a square and
     *                drawn into the inner 2/3 of the adaptive canvas.
     */
    fun compose(context: Context, source: Bitmap): Bitmap {
        return compose(context, source, fullBleed = false)
    }

    /**
     * Variant of [compose] for sources that carry transparent/semi-transparent margins around
     * their artwork — i.e. the bundled preset PNG tiles, whose alpha bbox covers only ~98% of the
     * 192×192 canvas with soft anti-aliased edges sitting on transparent padding.
     *
     * `composeFullBleed` first crops the source to its opaque-content bbox (alpha > [BBOX_THRESHOLD]),
     * so the artwork fills the safe zone edge-to-edge just like an uploaded photo does. Without this
     * crop, the preset's transparent margin becomes a visible ~3px ring inside the safe zone after
     * scaling, and the soft-edge semi-transparent pixels (with their dark RGB) premultiply into a
     * dark/colored halo against the white canvas — the "包边" users see on launcher icons. Uploaded
     * icons never had this because [ImageIconProcessor] already center-crops and fills 192px with
     * opaque photo content.
     *
     * Use this ONLY for the shortcut-icon path; it must not change in-app preset rendering (the
     * hub grid / form dialog render the raw drawable, not this composition).
     */
    fun composeFullBleed(context: Context, source: Bitmap): Bitmap {
        return compose(context, source, fullBleed = true)
    }

    /**
     * @param context any context; used only to convert dp → px for the output canvas size.
     * @param source  the favicon/uploaded image to embed; it is center-cropped to a square and
     *                drawn into the inner 2/3 of the adaptive canvas.
     * @param fullBleed when true, the source is first cropped to its opaque-content bbox so the
     *                artwork fills the safe zone with no transparent margin (used for preset tiles
     *                in the shortcut path). When false, the source is used as-is (uploaded/favicons
     *                already fill their canvas opaquely).
     */
    private fun compose(context: Context, source: Bitmap, fullBleed: Boolean): Bitmap {
        // Adaptive-icon canvas is 108dp; safe zone (inner 2/3) is 72dp. Render at 1.5× for crispness.
        val canvasDp = 108f * 1.5f
        val canvasPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, canvasDp, context.resources.displayMetrics
        ).toInt().coerceAtLeast(1)

        val output = Bitmap.createBitmap(canvasPx, canvasPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        // Round-2 problem E: fill with an OPAQUE background before drawing the source. Without
        // this, the canvas is transparent (0,0,0,0); when the source PNG has alpha at its edges
        // (preset tiles, favicons with transparency), bilinear/premultiplied sampling blends the
        // transparent pixels' BLACK rgb (0,0,0) into adjacent opaque pixels, producing a visible
        // dark ring around the icon — "黑边". A solid white background matches the design (preset
        // / upload shortcut icons sit on white) and also stops launchers (e.g. MIUI) that render
        // adaptive-icon transparent padding as black.
        canvas.drawColor(Color.WHITE)

        val inner = (canvasPx * 2 / 3)
        val origin = (canvasPx - inner) / 2
        val dst = Rect(origin, origin, origin + inner, origin + inner)

        // For preset tiles: crop to the opaque-content bbox so artwork fills the safe zone with no
        // transparent margin, eliminating the post-compose ring. Uploaded/favicon sources are
        // already opaque-edge and pass through untouched.
        val base: Bitmap = if (fullBleed) {
            contentBBoxCrop(source) ?: source
        } else {
            source
        }
        val cropped = centerCropSquare(base)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(cropped, null, dst, paint)
        if (cropped !== base && cropped !== source) cropped.recycle()
        if (base !== source) base.recycle()
        return output
    }

    /**
     * Crop [src] to the bounding box of its opaque content (alpha > [BBOX_THRESHOLD]). Returns null
     * when the source has no opaque pixels (caller falls back to using the source as-is).
     */
    private fun contentBBoxCrop(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (Color.alpha(src.getPixel(x, y)) > BBOX_THRESHOLD) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return null
        val side = minOf(maxX - minX + 1, maxY - minY + 1)
        if (side == w && side == h) return null // already full-bleed, nothing to crop
        // Center the square crop on the bbox to preserve symmetry of the artwork.
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        val left = (cx - side / 2).coerceIn(0, w - side)
        val top = (cy - side / 2).coerceIn(0, h - side)
        return Bitmap.createBitmap(src, left, top, side, side)
    }

    /**
     * Alpha threshold for "opaque content" when cropping a preset tile to its art bbox. 128
     * matches the alpha>128 bbox convention used by the preset-asset pipeline; pixels below
     * this are treated as margin/anti-alias and excluded from the crop.
     */
    private const val BBOX_THRESHOLD = 128

    private fun centerCropSquare(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w == h) return src
        val side = minOf(w, h)
        val left = (w - side) / 2
        val top = (h - side) / 2
        return Bitmap.createBitmap(src, left, top, side, side)
    }
}