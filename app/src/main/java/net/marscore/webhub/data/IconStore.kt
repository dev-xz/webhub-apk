package net.marscore.webhub.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * Persists child-app icons as PNG files at `<filesDir>/icons/<id>.png`.
 *
 * Save always overwrites any existing file for the same id. The directory is created lazily.
 */
class IconStore(context: Context) {

    private val iconsDir: File = File(context.applicationContext.filesDir, "icons").apply {
        if (!exists()) mkdirs()
    }

    /** Save [bitmap] as PNG for [childId], overwriting any existing file. Returns the absolute path. */
    fun save(childId: Long, bitmap: Bitmap): String {
        val file = File(iconsDir, "$childId.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }

    /** Load a bitmap from an absolute [path]; null when missing or undecodable. */
    fun load(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    /** Delete the icon file for [childId] if it exists. No-op when absent. */
    fun deleteFor(childId: Long) {
        File(iconsDir, "$childId.png").let { if (it.exists()) it.delete() }
    }

    /** Resolve the absolute path that *would* be used for [childId] (whether or not it exists). */
    fun pathFor(childId: Long): String = File(iconsDir, "$childId.png").absolutePath
}