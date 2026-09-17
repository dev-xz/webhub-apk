package net.marscore.webhub.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.icons.IconResolver

object WidgetIcons {

    /** Composes a launcher-style tile, adding a white base behind low-coverage artwork. */
    fun composeTile(context: Context, child: ChildApp, sizePx: Int): Bitmap? =
        composeTileFromBitmap(IconResolver.resolveIconBitmap(context, child), sizePx)

    fun composeTileFromBitmap(src: Bitmap?, sizePx: Int): Bitmap? {
        if (src == null || src.width <= 0 || src.height <= 0 || sizePx <= 0) return null

        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bounds = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val radius = sizePx * 0.22f
        val path = Path().apply {
            addRoundRect(bounds, radius, radius, Path.Direction.CCW)
        }

        canvas.clipPath(path)
        if (opaqueCoverage(src) < 0.6f) canvas.drawColor(Color.WHITE)

        val scale = maxOf(sizePx.toFloat() / src.width, sizePx.toFloat() / src.height)
        val width = src.width * scale
        val height = src.height * scale
        val left = (sizePx - width) / 2f
        val top = (sizePx - height) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(src, null, RectF(left, top, left + width, top + height), paint)
        return out
    }

    private fun opaqueCoverage(src: Bitmap): Float {
        val readable = if (src.config == Bitmap.Config.HARDWARE) {
            src.copy(Bitmap.Config.ARGB_8888, false) ?: return 0f
        } else {
            src
        }
        var opaque = 0
        var sampled = 0
        for (y in 0 until readable.height step 4) {
            for (x in 0 until readable.width step 4) {
                if (Color.alpha(readable.getPixel(x, y)) > 128) opaque++
                sampled++
            }
        }
        if (readable !== src) readable.recycle()
        return if (sampled == 0) 0f else opaque.toFloat() / sampled
    }
}
