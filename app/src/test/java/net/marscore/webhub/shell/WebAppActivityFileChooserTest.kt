package net.marscore.webhub.shell

import android.content.Intent
import android.provider.MediaStore
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
 * OpenSpec improve-webapp-shell-ux tasks 3.2–3.6 / design D5, D6.
 *
 * Tests the pure [FileChooserDecision] layer extracted out of
 * `WebAppActivity.ChromeClient.onShowFileChooser`. Standing up the full
 * Activity under Robolectric would require a ChildApp in the Room DB and a
 * real WebView — too fragile for the unit-test suite. Per the task spec:
 * "Prefer extraction over fragile Activity tests."
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md (targetSdk 36 > Robolectric
 * cap); `Intent`/`MediaStore` are Android classes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WebAppActivityFileChooserTest {

    // ---- chooseIntent: non-capture path ----

    @Test
    fun choose_nonCapture_imageAccept_buildsContentIntentForImages() {
        val decision = FileChooserDecision.chooseIntent(
            isCapture = false,
            isCameraGranted = true,
            acceptTypes = arrayOf("image/*"),
            isMultiple = false,
        )
        assertEquals(Intent.ACTION_GET_CONTENT, decision.intent.action)
        // EXTRA_MIME_TYPES carries the image filter.
        val mimes = decision.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        assertNotNull(mimes)
        assertEquals("image/*", mimes!!.first())
        assertFalse(decision.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun choose_nonCapture_multiple_setsAllowMultiple() {
        val decision = FileChooserDecision.chooseIntent(
            isCapture = false,
            isCameraGranted = true,
            acceptTypes = arrayOf("image/*", "video/*"),
            isMultiple = true,
        )
        assertTrue(decision.intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
    }

    @Test
    fun choose_noAcceptTypes_fallsBackToWildcard() {
        val decision = FileChooserDecision.chooseIntent(
            isCapture = false,
            isCameraGranted = true,
            acceptTypes = arrayOf(""),
            isMultiple = false,
        )
        // Wildcard single — EXTRA_MIME_TYPES omitted as redundant.
        assertNull(decision.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        assertEquals("*/*", decision.intent.type)
    }

    // ---- chooseIntent: capture path ----

    @Test
    fun choose_captureImageAndCameraGranted_buildsImageCaptureIntent() {
        val decision = FileChooserDecision.chooseIntent(
            isCapture = true,
            isCameraGranted = true,
            acceptTypes = arrayOf("image/*"),
            isMultiple = false,
        )
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, decision.intent.action)
    }

    @Test
    fun choose_captureVideoAndCameraGranted_buildsVideoCaptureIntent() {
        val decision = FileChooserDecision.chooseIntent(
            isCapture = true,
            isCameraGranted = true,
            acceptTypes = arrayOf("video/*"),
            isMultiple = false,
        )
        assertEquals(MediaStore.ACTION_VIDEO_CAPTURE, decision.intent.action)
    }

    @Test
    fun choose_captureButCameraNotGranted_fallsBackToContentPicker() {
        // Permission not granted → must NOT launch the camera Intent directly;
        // the caller is responsible for the requestPermissions flow. The
        // decision returns the safe content-picker.
        val decision = FileChooserDecision.chooseIntent(
            isCapture = true,
            isCameraGranted = false,
            acceptTypes = arrayOf("image/*"),
            isMultiple = false,
        )
        assertEquals(Intent.ACTION_GET_CONTENT, decision.intent.action)
    }

    @Test
    fun choose_captureButAcceptIsPdf_fallsBackToContentPicker() {
        // capture requested but accept types don't name image/video → no
        // capture Intent possible, content picker is the only path.
        val decision = FileChooserDecision.chooseIntent(
            isCapture = true,
            isCameraGranted = true,
            acceptTypes = arrayOf("application/pdf"),
            isMultiple = false,
        )
        assertEquals(Intent.ACTION_GET_CONTENT, decision.intent.action)
    }

    // ---- isCaptureReachable ----

    @Test
    fun isCaptureReachable_imageAccept_true() {
        assertTrue(FileChooserDecision.isCaptureReachable(arrayOf("image/*")))
    }

    @Test
    fun isCaptureReachable_videoAccept_true() {
        assertTrue(FileChooserDecision.isCaptureReachable(arrayOf("video/*")))
    }

    @Test
    fun isCaptureReachable_wildcard_false() {
        assertFalse(FileChooserDecision.isCaptureReachable(arrayOf("*/*")))
    }

    @Test
    fun isCaptureReachable_pdf_false() {
        assertFalse(FileChooserDecision.isCaptureReachable(arrayOf("application/pdf")))
    }

    @Test
    fun isCaptureReachable_empty_false() {
        assertFalse(FileChooserDecision.isCaptureReachable(arrayOf("")))
    }
}