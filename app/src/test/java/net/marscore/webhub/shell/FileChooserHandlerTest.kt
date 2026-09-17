package net.marscore.webhub.shell

import android.content.Intent
import android.provider.MediaStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OpenSpec improve-webapp-shell-ux task 3.1 / design D5.
 *
 * Covers `normalizeAcceptTypes` (empty filtering + wildcard fallback),
 * `buildContentIntent` (action / category / type / EXTRA_MIME_TYPES /
 * EXTRA_ALLOW_MULTIPLE), and `buildCaptureIntent` (image/video preference and
 * null fallback).
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md (targetSdk 36 > Robolectric cap);
 * `Intent` / `MediaStore` are Android classes so the test needs the Android
 * runtime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class FileChooserHandlerTest {

    // ---- normalizeAcceptTypes ----

    @Test
    fun normalize_singleImageType_returnedAsIs() {
        assertArrayEquals(arrayOf("image/*"), FileChooserHandler.normalizeAcceptTypes(arrayOf("image/*")))
    }

    @Test
    fun normalize_emptyStringFilteredOut() {
        assertArrayEquals(arrayOf("image/*"), FileChooserHandler.normalizeAcceptTypes(arrayOf("image/*", "")))
    }

    @Test
    fun normalize_allEmpty_fallsBackToWildcard() {
        assertArrayEquals(arrayOf("*/*"), FileChooserHandler.normalizeAcceptTypes(arrayOf("", "")))
    }

    @Test
    fun normalize_singleEmpty_fallsBackToWildcard() {
        assertArrayEquals(arrayOf("*/*"), FileChooserHandler.normalizeAcceptTypes(arrayOf("")))
    }

    @Test
    fun normalize_emptyArray_fallsBackToWildcard() {
        assertArrayEquals(arrayOf("*/*"), FileChooserHandler.normalizeAcceptTypes(arrayOf()))
    }

    @Test
    fun normalize_multipleTypes_preservedInOrder() {
        assertArrayEquals(
            arrayOf("image/*", "video/*"),
            FileChooserHandler.normalizeAcceptTypes(arrayOf("image/*", "video/*"))
        )
    }

    // ---- buildContentIntent ----

    @Test
    fun buildContent_singleImageType_setsMimeTypesAndDefaults() {
        val intent = FileChooserHandler.buildContentIntent(arrayOf("image/*"), allowMultiple = false)

        assertEquals(Intent.ACTION_GET_CONTENT, intent.action)
        assertNotNull(intent.categories)
        assertTrue(intent.categories!!.contains(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", intent.type)
        assertArrayEquals(arrayOf("image/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun buildContent_wildcardType_omitsExtraMimeTypes_andSetsAllowMultiple() {
        val intent = FileChooserHandler.buildContentIntent(arrayOf("*/*"), allowMultiple = true)

        assertEquals(Intent.ACTION_GET_CONTENT, intent.action)
        assertEquals("*/*", intent.type)
        assertNull(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    // ---- buildCaptureIntent ----

    @Test
    fun buildCapture_imageType_returnsImageCapture() {
        val intent = FileChooserHandler.buildCaptureIntent(arrayOf("image/*"))
        assertNotNull(intent)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent!!.action)
    }

    @Test
    fun buildCapture_videoType_returnsVideoCapture() {
        val intent = FileChooserHandler.buildCaptureIntent(arrayOf("video/*"))
        assertNotNull(intent)
        assertEquals(MediaStore.ACTION_VIDEO_CAPTURE, intent!!.action)
    }

    @Test
    fun buildCapture_imageAndVideo_prefersImageCapture() {
        val intent = FileChooserHandler.buildCaptureIntent(arrayOf("image/*", "video/*"))
        assertNotNull(intent)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, intent!!.action)
    }

    @Test
    fun buildCapture_wildcard_returnsNull() {
        assertNull(FileChooserHandler.buildCaptureIntent(arrayOf("*/*")))
    }

    @Test
    fun buildCapture_pdfType_returnsNull() {
        assertNull(FileChooserHandler.buildCaptureIntent(arrayOf("application/pdf")))
    }
}