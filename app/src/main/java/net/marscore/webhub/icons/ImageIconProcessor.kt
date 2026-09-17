package net.marscore.webhub.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import net.marscore.webhub.data.IconStore
import java.io.ByteArrayOutputStream

/**
 * Processes user-supplied images (from the system Photo Picker or any bitmap source) into a
 * canonical form before persistence: center-crop to a square, scale to 192px, transcode to PNG.
 *
 * The picker UI itself arrives in a later wave; this class is the pure processor.
 */
class ImageIconProcessor(
    private val context: Context,
    private val iconStore: IconStore = IconStore(context)
) {

    /**
     * Read the image at [uri] (a Photo Picker `ACTION_PICK_IMAGES` result or any decodable Uri),
     * center-crop it to a square, scale to [targetSize] px, and persist it as PNG for [childId]
     * via [IconStore]. Returns the absolute path of the saved file.
     *
     * @return the saved icon path, or null when the Uri cannot be decoded.
     */
    fun processAndSave(childId: Long, uri: Uri, targetSize: Int = TARGET_SIZE): String? {
        val raw = decodeUri(uri) ?: return null
        return try {
            processAndSave(childId, raw, targetSize)
        } finally {
            raw.recycle()
        }
    }

    /**
     * Center-crop [bitmap] to a square, scale to [targetSize] px, and persist as PNG for [childId].
     * Returns the saved path. The input bitmap is not recycled by this method.
     */
    fun processAndSave(childId: Long, bitmap: Bitmap, targetSize: Int = TARGET_SIZE): String? {
        val square = centerCropSquare(bitmap)
        val scaled = scaleTo(square, targetSize)
        val path = iconStore.save(childId, scaled)
        if (scaled !== square) square.recycle()
        scaled.recycle()
        return path
    }

    /**
     * Center-crop [bitmap] to a square and transcode to PNG bytes at [targetSize]. Useful when the
     * caller wants bytes rather than a persisted file (e.g. shortcut icon composition).
     */
    fun processToPng(bitmap: Bitmap, targetSize: Int = TARGET_SIZE): ByteArray {
        val square = centerCropSquare(bitmap)
        val scaled = scaleTo(square, targetSize)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        if (scaled !== square) square.recycle()
        scaled.recycle()
        return out.toByteArray()
    }

    private fun decodeUri(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun centerCropSquare(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        if (w == h) return src
        val side = minOf(w, h)
        val left = (w - side) / 2
        val top = (h - side) / 2
        return Bitmap.createBitmap(src, left, top, side, side)
    }

    private fun scaleTo(src: Bitmap, target: Int): Bitmap {
        if (src.width == target && src.height == target) return src
        val matrix = Matrix()
        val scale = target.toFloat() / src.width
        matrix.postScale(scale, scale)
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    companion object {
        /** Canonical uploaded-icon edge length, in pixels. */
        const val TARGET_SIZE = 192
    }
}