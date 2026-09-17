package net.marscore.webhub.widgets

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetIconsCoverageTest {

    @Test
    fun lowCoverageGlyph_getsOpaqueWhiteBase() {
        val source = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(source).drawRect(36f, 36f, 60f, 60f, Paint().apply { color = Color.RED })

        val tile = WidgetIcons.composeTileFromBitmap(source, 96)!!

        assertEquals(Color.WHITE, tile.getPixel(8, 8))
        assertEquals(Color.RED, tile.getPixel(48, 48))
    }

    @Test
    fun fullyOpaqueArtwork_hasTransparentClippedCorner() {
        val source = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }

        val tile = WidgetIcons.composeTileFromBitmap(source, 96)!!

        assertEquals(0, Color.alpha(tile.getPixel(2, 2)))
    }

    @Test
    fun roundedOpaqueArtwork_doesNotGetWhiteCornerWedges() {
        val source = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(source)
        canvas.drawColor(Color.BLUE)
        val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        canvas.drawRect(0f, 0f, 12f, 12f, clear)
        canvas.drawRect(84f, 0f, 96f, 12f, clear)
        canvas.drawRect(0f, 84f, 12f, 96f, clear)
        canvas.drawRect(84f, 84f, 96f, 96f, clear)

        val tile = WidgetIcons.composeTileFromBitmap(source, 96)!!

        assertEquals(0, Color.alpha(tile.getPixel(8, 8)))
        assertEquals(Color.BLUE, tile.getPixel(48, 48))
    }
}
