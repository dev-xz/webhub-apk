package net.marscore.webhub.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Locks down the preset-shortcut "包边" (ring/halo) fix: a source bitmap that carries a
 * transparent margin around its artwork — like the bundled preset PNG tiles, whose alpha bbox
 * covers ~98% of the 192×192 canvas with soft dark-RGB anti-aliased edges — must, after
 * [AdaptiveIcons.composeFullBleed], produce an adaptive canvas whose safe-zone interior has NO
 * transparent-margin ring and NO dark-RGB halo bleed against the white background.
 *
 * Contrast with [compose] (the plain path used for uploaded/favicon icons, which already fill
 * their canvas opaquely and need no bbox crop).
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md. @GraphicsMode(NATIVE) so Canvas/Bitmap pixel ops
 * run on real Skia — required to observe premultiplied edge bleed and the white-fill behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveIconsFullBleedTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * A source mimicking a preset tile: transparent 6px margin + a 2px soft dark-RGB anti-aliased
     * edge (alpha ~40, rgb 0,0,0) + a solid-red interior. After composeFullBleed the artwork fills
     * the safe zone edge-to-edge, so the safe-zone boundary must show red (the art), not a ring of
     * dark/transparent margin, and NOT black-bleed from the soft edge's premultiplied rgb.
     */
    @Test fun fullBleedFillsSafeZoneWithoutMarginRing() {
        val src = presetLikeSource()

        val composed = AdaptiveIcons.composeFullBleed(context, src)

        // The safe zone is the inner 2/3 of the canvas. A point just inside the safe-zone boundary
        // (1px in from the edge, at the midpoint of each side) must be the art color (red), proving
        // the art fills the safe zone edge-to-edge rather than sitting inside a transparent margin.
        val cs = composed.width
        val safe = cs * 2 / 3
        val origin = (cs - safe) / 2
        // 2px inside each safe-zone edge, at the side midpoints.
        val topInside = composed.getPixel(cs / 2, origin + 2)
        val botInside = composed.getPixel(cs / 2, origin + safe - 3)
        val leftInside = composed.getPixel(origin + 2, cs / 2)
        val rightInside = composed.getPixel(origin + safe - 3, cs / 2)
        for ((label, c) in listOf("top" to topInside, "bot" to botInside, "left" to leftInside, "right" to rightInside)) {
            assertTrue(
                "$label safe-zone-edge pixel must be opaque (got alpha=${Color.alpha(c)}); full-bleed must not leave transparent margin",
                Color.alpha(c) >= 250
            )
            // The art is red; the safe-zone edge must be red-ish (high red, low green/blue), not
            // black/dark-bleed from the soft edge's premultiplied rgb.
            assertTrue(
                "$label safe-zone-edge pixel must be art-colored (red), not dark-bleed (got ${Color.red(c)},${Color.green(c)},${Color.blue(c)})",
                Color.red(c) > 150 && Color.green(c) < 100 && Color.blue(c) < 100
            )
        }

        // Padding (outside the safe zone) is still opaque white — unchanged from the base compose.
        val corner = composed.getPixel(0, 0)
        assertEquals(255, Color.alpha(corner))
        assertEquals(Color.WHITE, corner)

        src.recycle()
        composed.recycle()
    }

    /**
     * The plain [compose] path (used for uploaded/favicon icons) does NOT crop to the content bbox,
     * so the same preset-like source leaves a visible transparent/dark margin inside the safe zone.
     * This test documents the contrast: plain compose leaves the margin, fullBleed does not. It
     * also guards against accidentally making plain compose full-bleed (which would change the
     * upload-icon look users are already happy with).
     */
    @Test fun plainComposeLeavesMarginForContrast() {
        val src = presetLikeSource()
        val composed = AdaptiveIcons.compose(context, src)

        val cs = composed.width
        val safe = cs * 2 / 3
        val origin = (cs - safe) / 2
        // With plain compose the 6px transparent margin on a 192px source scales into the safe
        // zone: 6/192 of the safe width (~inner) of transparent → the safe-zone top edge midpoint
        // should NOT be the red art color; it should be white (the canvas background showing through
        // the transparent margin) or at least not solid red.
        val topEdge = composed.getPixel(cs / 2, origin + 1)
        assertTrue(
            "plain compose must leave the margin (top edge should not be solid red art); " +
                "if this fails, plain compose was made full-bleed by mistake (got rgb=${Color.red(topEdge)},${Color.green(topEdge)},${Color.blue(topEdge)})",
            !(Color.red(topEdge) > 150 && Color.green(topEdge) < 100 && Color.blue(topEdge) < 100)
        )

        src.recycle()
        composed.recycle()
    }

    /**
     * Real preset tiles: render each bundled preset PNG via the same path ShortcutHelper uses
     * (renderDrawableToBitmap → composeFullBleed) and assert the safe-zone edge has no transparent
     * ring and no black/dark halo. This is the end-to-end regression for the reported bug.
     */
    @Test fun composedPresetTilesHaveNoDarkRingAtSafeZoneEdge() {
        val presetKeys = listOf("mail", "play", "note", "check", "code", "doc", "calendar", "clock", "image")
        for (key in presetKeys) {
            val resId = context.resources.getIdentifier("preset_$key", "drawable", context.packageName)
            assertTrue("preset_$key PNG must be present", resId != 0)
            val rendered = renderPresetBitmap(resId)
            val composed = AdaptiveIcons.composeFullBleed(context, rendered)

            val cs = composed.width
            val safe = cs * 2 / 3
            val origin = (cs - safe) / 2
            // Sample several points just inside the safe-zone boundary on each side.
            val samples = listOf(
                composed.getPixel(cs / 2, origin + 1),
                composed.getPixel(cs / 2, origin + safe - 2),
                composed.getPixel(origin + 1, cs / 2),
                composed.getPixel(origin + safe - 2, cs / 2),
            )
            for (c in samples) {
                assertTrue(
                    "preset_$key: safe-zone edge must be opaque (alpha=${Color.alpha(c)}) — no transparent margin ring",
                    Color.alpha(c) >= 250
                )
                // No black halo: the art edges are colored, not (0,0,0) bleeding from semi-transparent
                // pixels. Tolerate near-black only if it's a genuinely dark art color by requiring the
                // pixel NOT be both very dark AND low-contrast (a halo is dark against a colored art).
                val isBlackHalo = Color.red(c) < 30 && Color.green(c) < 30 && Color.blue(c) < 30
                assertTrue(
                    "preset_$key: safe-zone edge must not be black-bleed halo (got ${Color.red(c)},${Color.green(c)},${Color.blue(c)})",
                    !isBlackHalo
                )
            }

            rendered.recycle()
            composed.recycle()
        }
    }

    /** Render a preset drawable resource to a bitmap the way ShortcutHelper.renderDrawableToBitmap does. */
    private fun renderPresetBitmap(resId: Int): Bitmap {
        val drawable = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, resId)
            ?: error("drawable $resId not found")
        val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: 192
        val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: 192
        val scale = 192f / maxOf(iw, ih)
        val w = (iw * scale).toInt().coerceAtLeast(1)
        val h = (ih * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bmp
    }

    /** A 192×192 source that mimics a preset tile: transparent margin + soft dark edge + red art. */
    private fun presetLikeSource(): Bitmap {
        val src = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(src)
        canvas.drawColor(Color.TRANSPARENT)
        // Soft dark-RGB semi-transparent edge (mimics preset anti-aliased art edge on transparent bg).
        val edgePaint = Paint().apply { color = Color.argb(40, 0, 0, 0) }
        canvas.drawRect(4f, 4f, 188f, 188f, edgePaint)
        // Solid red interior (the art).
        val artPaint = Paint().apply { color = Color.RED }
        canvas.drawRect(6f, 6f, 186f, 186f, artPaint)
        return src
    }
}