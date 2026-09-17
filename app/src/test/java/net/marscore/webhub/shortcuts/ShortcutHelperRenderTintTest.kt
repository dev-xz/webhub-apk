package net.marscore.webhub.shortcuts

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down the revamp-hub-ux D2 tint policy in [ShortcutHelper.renderDrawableToBitmap]:
 *  - VectorDrawable → the fixed dark-gray tint IS applied (vector branch taken; bitmap forced to
 *    192×192 square per the vector sizing rule).
 *  - BitmapDrawable → rendered UNTINTED (full-color source pixels preserved, not drowned in
 *    0xFF424242). This is the load-bearing assertion: the new preset PNG tiles are colored and
 *    must not be tinted.
 *
 * The vector-tint assertion is structural (branch taken → 192×192 square output) because
 * Robolectric's VectorDrawable shadow does not reliably rasterize path data into pixels; the
 * bitmap-untinted assertion is pixel-level and reliable.
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ShortcutHelperRenderTintTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * Vector branch: a bundled vector drawable is rendered, and the vector sizing rule (forced
     * 192×192 square) is observed — proving the [VectorDrawable]-specific branch was taken (that
     * branch is also the one that applies the tint).
     */
    @Test fun vectorDrawableRenderedViaVectorBranch() {
        val resId = context.resources.getIdentifier("ic_ind_mobile", "drawable", context.packageName)
        assertTrue("test needs a vector drawable to exercise the vector branch", resId != 0)
        val bmp = ShortcutHelper.renderDrawableToBitmap(context, resId)
        assertNotNull(bmp)
        // The vector branch forces a 192×192 square; the bitmap branch preserves aspect ratio.
        assertEquals(192, bmp!!.width)
        assertEquals(192, bmp.height)
    }

    /**
     * Bitmap branch (the load-bearing D2 assertion): a colored BitmapDrawable is rendered, and
     * the source color is preserved in the output. If the dark-gray tint were applied to bitmaps
     * (the pre-D2 bug), every pixel would be 0xFF424242.
     *
     * We synthesize a solid-red BitmapDrawable via a temporary drawable resource is not feasible
     * without shipping one, so we instead render one of the new preset PNG tiles (preset_mail) —
     * a designed colored tile — and assert the output is NOT dominated by the tint color.
     */
    @Test fun bitmapDrawableRenderedUntinted() {
        val resId = context.resources.getIdentifier("preset_mail", "drawable", context.packageName)
        assertTrue("preset_mail PNG must be present", resId != 0)
        val bmp = ShortcutHelper.renderDrawableToBitmap(context, resId)
        assertNotNull(bmp)
        val rendered = bmp!!
        val tint = 0xFF424242.toInt()
        val tintShare = dominantColorShare(rendered, tint)
        assertTrue(
            "bitmap preset must render untinted (tint color 0xFF424242 covers only ${tintShare * 100}% of pixels; must be < 95%)",
            tintShare < 0.95f
        )
    }

    /**
     * Synthetic colored BitmapDrawable rendered untinted: a solid-red 64×64 bitmap is wrapped as
     * a BitmapDrawable resource is not possible, so we instead directly verify the color-preserve
     * property by rendering the preset tile and confirming at least some non-tint, non-transparent
     * pixels exist (the tile has real color content).
     */
    @Test fun bitmapDrawablePreservesNonTintColor() {
        val resId = context.resources.getIdentifier("preset_mail", "drawable", context.packageName)
        assertTrue("preset_mail PNG must be present", resId != 0)
        val bmp = ShortcutHelper.renderDrawableToBitmap(context, resId)
        assertNotNull(bmp)
        val rendered = bmp!!
        val tint = 0xFF424242.toInt()
        assertTrue(
            "untinted bitmap must contain at least one pixel that is neither transparent nor the tint color",
            hasPixelNotMatching(rendered, tint)
        )
    }

    /** True if at least one opaque pixel's RGB differs from [tint] by more than the tolerance. */
    private fun hasPixelNotMatching(bmp: Bitmap, tint: Int): Boolean {
        val tr = Color.red(tint); val tg = Color.green(tint); val tb = Color.blue(tint)
        val tol = 20
        val stride = 2
        for (y in 0 until bmp.height step stride) {
            for (x in 0 until bmp.width step stride) {
                val c = bmp.getPixel(x, y)
                if (Color.alpha(c) == 0) continue
                if (kotlin.math.abs(Color.red(c) - tr) > tol ||
                    kotlin.math.abs(Color.green(c) - tg) > tol ||
                    kotlin.math.abs(Color.blue(c) - tb) > tol
                ) return true
            }
        }
        return false
    }

    /** Fraction of opaque pixels that are (close to) [targetColor], sampling with a stride. */
    private fun dominantColorShare(bmp: Bitmap, targetColor: Int): Float {
        val tr = Color.red(targetColor)
        val tg = Color.green(targetColor)
        val tb = Color.blue(targetColor)
        val tol = 12
        var sampled = 0
        var matched = 0
        val stride = 4
        for (y in 0 until bmp.height step stride) {
            for (x in 0 until bmp.width step stride) {
                val c = bmp.getPixel(x, y)
                if (Color.alpha(c) == 0) continue
                sampled++
                if (kotlin.math.abs(Color.red(c) - tr) <= tol &&
                    kotlin.math.abs(Color.green(c) - tg) <= tol &&
                    kotlin.math.abs(Color.blue(c) - tb) <= tol
                ) matched++
            }
        }
        return if (sampled == 0) 1f else matched.toFloat() / sampled
    }
}