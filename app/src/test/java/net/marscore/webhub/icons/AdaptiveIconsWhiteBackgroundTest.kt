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
 * Locks down problem E (revamp-hub-ux round 2): adaptive-icon composition must render the source
 * onto an OPAQUE (white) background, eliminating the dark ring ("黑边") that appears around preset
 * /favicon shortcut icons when their alpha edges bleed black rgb into the bilinear-sampled border
 * and/or launchers render the transparent adaptive padding as black.
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md. @GraphicsMode(NATIVE) so Canvas/Bitmap pixel ops
 * run on real Skia rather than Robolectric's stub graphics (the default shadow returns zeroed
 * pixels, which would mask the white-fill behavior we're asserting).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveIconsWhiteBackgroundTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * A source with a transparent border and a colored interior — after composition, the canvas
     * padding area (outside the inner 2/3 safe zone) must be OPAQUE WHITE, not transparent/black.
     * This is the area launchers mask, and where the black ring was visible.
     */
    @Test fun composeFillsPaddingWithOpaqueWhite() {
        // 48×48 source: transparent border (8px) + solid-red interior (32×32 centered).
        val src = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        Canvas(src).drawColor(Color.TRANSPARENT)
        val paint = Paint().apply { color = Color.RED }
        Canvas(src).drawRect(8f, 8f, 40f, 40f, paint)

        val composed = AdaptiveIcons.compose(context, src)

        // Corner pixel is in the 25% padding zone — must be opaque white, not transparent/black.
        val corner = composed.getPixel(0, 0)
        assertEquals(Color.alpha(corner), 255)
        assertEquals("corner must be white, not black-bleed", Color.WHITE, corner)

        // A pixel deep in the padding zone (mid-padding) also opaque white.
        val pad = composed.getPixel(composed.width / 2, 2)
        assertEquals(255, Color.alpha(pad))
        assertEquals(Color.WHITE, pad)

        // The interior should still contain the red source color (untinted).
        val center = composed.getPixel(composed.width / 2, composed.height / 2)
        assertEquals(Color.RED, center)

        src.recycle()
        composed.recycle()
    }

    /**
     * Even a fully-transparent source must compose to an opaque-white canvas (no transparent
     * adaptive padding that launchers could render as black).
     */
    @Test fun transparentSourceYieldsOpaqueWhiteCanvas() {
        val src = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        Canvas(src).drawColor(Color.TRANSPARENT)
        val composed = AdaptiveIcons.compose(context, src)
        // Sample several pixels; all must be opaque white.
        for ((x, y) in listOf(0 to 0, composed.width - 1 to 0, 0 to composed.height - 1, composed.width / 2 to composed.height / 2)) {
            val c = composed.getPixel(x, y)
            assertTrue("pixel ($x,$y) must be opaque", Color.alpha(c) == 255)
        }
        src.recycle()
        composed.recycle()
    }
}