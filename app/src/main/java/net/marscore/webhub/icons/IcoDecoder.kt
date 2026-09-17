package net.marscore.webhub.icons

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal ICO container decoder (task 9.5).
 *
 * `BitmapFactory` cannot decode real `.ico` containers, and most sites' `/favicon.ico` is exactly
 * that — an ICO wrapping one or more raster images. This decoder parses the ICONDIR + ICONDIRENTRY
 * headers, picks the **largest** entry (by `width * height`, where a 0 byte means 256), and decodes
 * the payload:
 *  - if it starts with the PNG magic (`89 50 4E 47 ...`), decode as PNG via [BitmapFactory];
 *  - otherwise it's a BMP/DIB — we skip it (rare on modern sites; handled by the caller's
 *    BitmapFactory-first attempt on the raw bytes, and by the HTML `<link>` fallback).
 *
 * Also: some "favicon.ico" URLs serve a plain PNG mislabeled as .ico. The caller handles that by
 * trying `BitmapFactory` on the raw bytes **first**; this decoder is the fallback for true ICOs.
 *
 * Returns null for any malformed input (too short, bad magic, no PNG payloads, out-of-range offsets).
 */
object IcoDecoder {

    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /**
     * Decode the largest PNG-embedded image from an ICO container, or null if the bytes are not a
     * valid ICO or contain no decodable PNG entry.
     */
    fun decode(bytes: ByteArray): Bitmap? {
        // ICONDIR is 6 bytes; need at least that + one 16-byte entry to be meaningful.
        if (bytes.size < 6 + 16) return null

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // ICONDIR: reserved(2) must be 0, type(2) must be 1 (icon), count(2).
        val reserved = buf.short.toInt() and 0xFFFF
        val type = buf.short.toInt() and 0xFFFF
        if (reserved != 0 || type != 1) return null
        val count = buf.short.toInt() and 0xFFFF
        if (count == 0) return null

        // Read all ICONDIRENTRYs, tracking the largest PNG payload.
        var bestOffset = -1
        var bestSize = -1
        var bestArea = -1

        for (i in 0 until count) {
            if (buf.remaining() < 16) return null
            val wRaw = buf.get().toInt() and 0xFF
            val hRaw = buf.get().toInt() and 0xFF
            val colorCount = buf.get().toInt() and 0xFF // unused for our purposes
            val reservedByte = buf.get().toInt() and 0xFF
            val planes = buf.short.toInt() and 0xFFFF
            val bitCount = buf.short.toInt() and 0xFFFF
            val bytesInRes = buf.int
            val imageOffset = buf.int

            // 0 means 256 in ICO's single-byte width/height fields.
            val w = if (wRaw == 0) 256 else wRaw
            val h = if (hRaw == 0) 256 else hRaw
            val area = w * h

            // Sanity: offset + size must land inside the buffer.
            if (imageOffset < 0 || bytesInRes <= 0) continue
            if (imageOffset.toLong() + bytesInRes > bytes.size) continue

            // Only consider PNG-embedded entries (check the magic at the payload start).
            if (!isPngPayload(bytes, imageOffset, bytesInRes)) continue

            // Pick the largest by area; ties broken by larger declared size (bytesInRes), which
            // usually correlates with higher quality / bit depth.
            if (area > bestArea || (area == bestArea && bytesInRes > bestSize)) {
                bestArea = area
                bestSize = bytesInRes
                bestOffset = imageOffset
            }
        }

        if (bestOffset < 0) return null

        // Slice the PNG payload out and let BitmapFactory decode it.
        val pngBytes = bytes.copyOfRange(bestOffset, bestOffset + bestSize)
        return BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
    }

    /** True if the payload at [offset] of length [size] begins with the PNG magic bytes. */
    private fun isPngPayload(bytes: ByteArray, offset: Int, size: Int): Boolean {
        if (size < PNG_MAGIC.size) return false
        for (i in PNG_MAGIC.indices) {
            if (bytes[offset + i] != PNG_MAGIC[i]) return false
        }
        return true
    }
}