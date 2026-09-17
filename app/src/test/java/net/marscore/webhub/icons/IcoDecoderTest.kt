package net.marscore.webhub.icons

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class IcoDecoderTest {

    // ---------------- helpers: build PNG bytes + ICO containers in memory ----------------

    /** A small known-size RGBA PNG (w×h), filled with a solid color so it compresses/decodes. */
    private fun pngBytes(w: Int, h: Int, color: Int = Color.RED): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /**
     * Assemble an ICO container wrapping [entries] (each = PNG payload + declared w/h).
     * Writes a valid ICONDIR + one ICONDIRENTRY per entry, then the payloads concatenated.
     */
    private fun icoBytes(entries: List<IcoEntry>): ByteArray {
        val count = entries.size
        val headerSize = 6 + 16 * count
        // Compute offsets: payloads laid out back-to-back after the header.
        var offset = headerSize
        val dir = entries.map { e ->
            IcoDirEntry(
                w = if (e.width >= 256) 0 else e.width,
                h = if (e.height >= 256) 0 else e.height,
                size = e.payload.size,
                offset = offset
            ).also { offset += e.payload.size }
        }
        val out = ByteArrayOutputStream()
        // ICONDIR
        out.write(0); out.write(0)              // reserved = 0
        out.write(1); out.write(0)              // type = 1 (LE)
        out.write(count); out.write((count ushr 8)) // count (LE)
        // ICONDIRENTRYs
        for (d in dir) {
            out.write(d.w); out.write(d.h)      // width, height (0 = 256)
            out.write(0)                        // colorCount
            out.write(0)                        // reserved
            out.write(1); out.write(0)          // planes = 1 (LE)
            out.write(8); out.write(0)          // bitCount = 8 (LE) — not load-bearing for PNG
            writeLeInt(out, d.size)
            writeLeInt(out, d.offset)
        }
        // Payloads
        for (e in entries) out.write(e.payload)
        return out.toByteArray()
    }

    private fun writeLeInt(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private data class IcoEntry(val width: Int, val height: Int, val payload: ByteArray)
    private data class IcoDirEntry(val w: Int, val h: Int, val size: Int, val offset: Int)

    // ---------------- tests ----------------

    @Test fun decodesSinglePngIco() {
        val png = pngBytes(32, 32)
        val ico = icoBytes(listOf(IcoEntry(32, 32, png)))
        val bmp = IcoDecoder.decode(ico)
        assertNotNull("single-entry PNG ICO should decode", bmp)
        assertEquals(32, bmp!!.width)
        assertEquals(32, bmp.height)
    }

    @Test fun picksLargestEntry() {
        val small = pngBytes(16, 16)
        val large = pngBytes(64, 64, Color.BLUE)
        // Put the small one FIRST to ensure the decoder actually selects by area, not by order.
        val ico = icoBytes(listOf(IcoEntry(16, 16, small), IcoEntry(64, 64, large)))
        val bmp = IcoDecoder.decode(ico)
        assertNotNull(bmp)
        assertEquals(64, bmp!!.width)
        assertEquals(64, bmp.height)
    }

    @Test fun widthZeroMeans256() {
        val png = pngBytes(256, 256)
        // Encode with w/h = 0 (the ICO convention for 256).
        val ico = icoBytes(listOf(IcoEntry(256, 256, png)))
        val bmp = IcoDecoder.decode(ico)
        assertNotNull(bmp)
        assertEquals(256, bmp!!.width)
    }

    @Test fun garbageInputReturnsNull() {
        assertNull(IcoDecoder.decode(byteArrayOf(0, 1, 2, 3)))
        assertNull(IcoDecoder.decode(byteArrayOf()))
        // Wrong type field (type=2 = cursor, not icon).
        assertNull(IcoDecoder.decode(byteArrayOf(0, 0, 2, 0, 1, 0) + ByteArray(16)))
        // Non-zero reserved.
        assertNull(IcoDecoder.decode(byteArrayOf(1, 0, 1, 0, 1, 0) + ByteArray(16)))
    }

    @Test fun bmpOnlyIcoReturnsNull() {
        // An ICO whose single payload is NOT a PNG (fake BMP payload) → no decodable entry.
        val fakeBmp = byteArrayOf(0x42, 0x4D, 0x00, 0x00) // "BM..." but truncated junk
        val ico = icoBytes(listOf(IcoEntry(16, 16, fakeBmp)))
        assertNull(IcoDecoder.decode(ico))
    }

    @Test fun outOfRangeOffsetIgnored() {
        // An entry whose declared offset points past the buffer end should be skipped, not crash.
        val png = pngBytes(16, 16)
        // Hand-build an ICO with a bogus offset entry + a valid entry; the valid one must still win.
        val valid = IcoEntry(16, 16, png)
        val ico = icoBytes(listOf(valid))
        // Corrupt the offset field of the single entry to point way past the end.
        val corrupted = ico.copyOf()
        // offset is at bytes 6+12 .. 6+15 (entry 0, offset field at byte 12 within the entry).
        val offsetPos = 6 + 12
        corrupted[offsetPos] = 0x7F.toByte()
        corrupted[offsetPos + 1] = 0x00
        corrupted[offsetPos + 2] = 0x00
        corrupted[offsetPos + 3] = 0x00
        // With the only entry's offset pointing into the void, decode yields null (no valid entry).
        assertNull(IcoDecoder.decode(corrupted))
    }
}