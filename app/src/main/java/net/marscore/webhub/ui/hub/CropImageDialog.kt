package net.marscore.webhub.ui.hub

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.marscore.webhub.R
import java.io.File
import java.io.FileOutputStream

object CropImageDialog {
    fun show(host: AppCompatActivity, source: Uri, onCropped: (Uri) -> Unit) {
        val bitmap = decode(host, source) ?: return
        val content = LayoutInflater.from(host).inflate(R.layout.dialog_crop_image, null)
        val cropView = content.findViewById<CropImageView>(R.id.crop_image)
        cropView.setBitmap(bitmap)
        val dialog = MaterialAlertDialogBuilder(host)
            .setTitle(R.string.crop_title)
            .setView(content)
            .setPositiveButton(R.string.crop_confirm, null)
            .setNegativeButton(R.string.wizard_cancel, null)
            .create()
        dialog.setOnDismissListener { bitmap.recycle() }
        dialog.show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val cropped = cropView.crop()
            val file = File(host.cacheDir, "crop_${System.currentTimeMillis()}.png")
            val saved = runCatching {
                FileOutputStream(file).use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.getOrDefault(false)
            cropped.recycle()
            if (saved) {
                onCropped(Uri.fromFile(file))
                dialog.dismiss()
            } else {
                Toast.makeText(host, R.string.crop_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun decode(host: AppCompatActivity, source: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        host.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        var sample = 1
        while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        host.contentResolver.openInputStream(source)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }.getOrNull()
}
