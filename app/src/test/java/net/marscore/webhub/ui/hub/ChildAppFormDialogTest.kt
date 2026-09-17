package net.marscore.webhub.ui.hub

import android.net.Uri
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityOptionsCompat
import net.marscore.webhub.R
import net.marscore.webhub.data.ChildApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ChildAppFormDialogTest {

    @Test fun invalidUrlStaysOnStepOne() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_WebHub) }
        controller.setup()
        var saved = false
        val launcher = noOpLauncher()
        ChildAppFormDialog.show(
            activity,
            existing = null,
            pickImageLauncher = launcher,
            pickImageBridge = ChildAppFormDialog.PickImageBridge()
        ) { saved = true }

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val content = dialog.window!!.decorView
        val url = content.findViewById<android.widget.EditText>(R.id.step1_url_input)
        url.setText("example.com")
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()

        assertFalse(saved)
        assertEquals(View.VISIBLE, content.findViewById<View>(R.id.step1_root).visibility)
        assertEquals(View.GONE, content.findViewById<View>(R.id.step2_root).visibility)
        assertEquals(View.GONE, content.findViewById<View>(R.id.edit_ignore_ssl).visibility)
        assertTrue(content.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.step1_url_layout).error != null)
    }

    @Test fun formResultCarriesThreeIconSourcesAndFaviconBitmapSeparately() {
        val favicon = ChildAppFormDialog.FormResult("A", "https://a.example/", "favicon", null, null, null, "default", 100, false)
        val upload = ChildAppFormDialog.FormResult("A", "https://a.example/", "upload", Uri.parse("content://image"), null, null, "default", 100, false)
        val preset = ChildAppFormDialog.FormResult("A", "https://a.example/", "preset", null, "mail", null, "default", 100, false)

        assertEquals("favicon", favicon.iconSource)
        assertNull(favicon.uploadUri)
        assertEquals("upload", upload.iconSource)
        assertEquals(Uri.parse("content://image"), upload.uploadUri)
        assertEquals("mail", preset.presetKey)
        assertNull(preset.faviconBitmap)
        assertEquals(100, preset.zoomPercent)
    }

    @Test fun editModeUsesNeutralZoomForLegacyZeroAndShowsSslControl() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        val activity = controller.get().apply { setTheme(R.style.Theme_WebHub) }
        controller.setup()
        var saved: ChildAppFormDialog.FormResult? = null
        ChildAppFormDialog.show(
            activity,
            existing = ChildApp(name = "A", url = "https://a.example/", iconSource = "preset", iconPath = "mail", zoomPercent = 0),
            pickImageLauncher = noOpLauncher(),
            pickImageBridge = ChildAppFormDialog.PickImageBridge()
        ) { saved = it }

        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val content = dialog.window!!.decorView
        assertEquals(100f, content.findViewById<com.google.android.material.slider.Slider>(R.id.zoom_slider).value)
        assertEquals(View.VISIBLE, content.findViewById<View>(R.id.edit_ignore_ssl).visibility)
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        assertEquals(100, saved?.zoomPercent)
    }

    private fun noOpLauncher(): ActivityResultLauncher<PickVisualMediaRequest> =
        object : ActivityResultLauncher<PickVisualMediaRequest>() {
            override val contract = ActivityResultContracts.PickVisualMedia()
            override fun launch(input: PickVisualMediaRequest) = Unit
            override fun launch(input: PickVisualMediaRequest, options: ActivityOptionsCompat?) = Unit
            override fun unregister() = Unit
        }
}
