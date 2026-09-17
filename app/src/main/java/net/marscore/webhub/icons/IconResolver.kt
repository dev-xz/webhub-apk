package net.marscore.webhub.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import androidx.appcompat.content.res.AppCompatResources
import net.marscore.webhub.data.ChildApp
import java.io.File

/**
 * Shared icon resolution for child apps.
 *
 * Extracted (see add-home-screen-widgets D6) so that both the launcher-shortcut path
 * ([net.marscore.webhub.shortcuts.ShortcutHelper]) and the home-screen widget path can resolve a
 * representative **raw source** bitmap for a [ChildApp] without the widget code depending on the
 * `shortcuts` package. Behavior is a pure move of the original `ShortcutHelper` icon resolution;
 * the shortcut module now delegates here.
 *
 * Resolution order:
 *  - `iconSource == "preset"` → render [PresetIcons.resForKey] (vector drawable) to bitmap.
 *  - else → load the on-disk file at [ChildApp.iconPath].
 *  - fallback: render [PresetIcons.pickForId] when the above yields no bitmap.
 */
object IconResolver {

    /**
     * Resolve a representative **raw source** bitmap for [child], suitable for passing to the
     * shortcut / widget composition paths (which apply the adaptive-canvas composition themselves).
     *
     *  - `iconSource == "preset"` → render [PresetIcons.resForKey] (vector drawable) to bitmap.
     *  - else → load the on-disk file at [ChildApp.iconPath].
     *  - fallback: render [PresetIcons.pickForId] when the above yields no bitmap.
     *
     * @return the raw source bitmap, or null on total failure.
     */
    fun resolveIconBitmap(context: Context, child: ChildApp): Bitmap? {
        return when (child.iconSource) {
            "preset" -> {
                val key = child.iconPath
                if (!key.isNullOrBlank()) {
                    val resId = PresetIcons.resForKey(key)
                    if (resId != 0) renderDrawableToBitmap(context, resId) else null
                } else null
            }
            else -> {
                val path = child.iconPath
                if (!path.isNullOrBlank()) decodeFileBitmap(path) else null
            }
        } ?: renderDrawableToBitmap(context, PresetIcons.pickForId(child.id).resId)
    }

    private fun decodeFileBitmap(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Render a drawable resource to a bitmap. Handles both vector and raster drawables.
     *
     * Preset vectors declare `android:tint="?attr/colorControlNormal"`; loading via
     * [AppCompatResources.getDrawable] resolves the themed reference safely (a plain
     * `Resources.getDrawable(_, null)` would fail to resolve `?attr` refs without a theme).
     *
     * Tint policy (revamp-hub-ux D2): the fixed dark-gray tint is applied **only** to
     * [VectorDrawable] — the old vector glyphs were single-color and needed a deterministic
     * visible color. The new preset assets are full-color bitmap tiles (`BitmapDrawable`); tinting
     * them would drown the design in dark gray, so they render untinted. Any other drawable type
     * is likewise rendered as-is.
     */
    fun renderDrawableToBitmap(context: Context, resId: Int): Bitmap? {
        val drawable: Drawable = try {
            AppCompatResources.getDrawable(context, resId)
        } catch (e: Exception) {
            return null
        } ?: return null
        // Apply the visible tint ONLY for vector glyphs (single-color, otherwise transparent/white).
        if (drawable is VectorDrawable) {
            drawable.setTint(0xFF424242.toInt())
        }
        val targetSizePx = 192
        val width: Int
        val height: Int
        if (drawable is VectorDrawable) {
            width = targetSizePx
            height = targetSizePx
        } else {
            val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetSizePx
            val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetSizePx
            // scale so the larger side is targetSizePx, keep aspect
            val scale = targetSizePx.toFloat() / maxOf(iw, ih)
            width = (iw * scale).toInt().coerceAtLeast(1)
            height = (ih * scale).toInt().coerceAtLeast(1)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }
}