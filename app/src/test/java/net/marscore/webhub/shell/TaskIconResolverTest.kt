package net.marscore.webhub.shell

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.data.IconStore
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Problem D (revamp-hub-ux round 2): the recents-task icon resolution must yield a plain bitmap
 * for preset and on-disk-file sources, and null on decode failure (so TaskDescription omits the
 * icon rather than crashing).
 *
 * Robolectric @Config(sdk=[33]); @GraphicsMode(NATIVE) so the file-decode failure case returns
 * null instead of Robolectric's fake 100×100 bitmap (AGENTS.md pitfall).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskIconResolverTest {

    private lateinit var context: android.content.Context
    private lateinit var iconStore: IconStore

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        iconStore = IconStore(context)
    }

    @Test fun presetSourceReturnsBitmap() {
        val app = ChildApp(
            id = 1, name = "Mail", url = "https://mail.example.com/",
            iconSource = "preset", iconPath = "mail"
        )
        val bmp = TaskIconResolver.resolve(context, app)
        assertNotNull("preset source must render to a bitmap", bmp)
        // It's a plain bitmap, not an adaptive-canvas composition — non-null square is fine.
        assertTrue(bmp!!.width > 0 && bmp.height > 0)
    }

    @Test fun unknownPresetKeyReturnsNull() {
        val app = ChildApp(
            id = 1, name = "X", url = "https://x.example.com/",
            iconSource = "preset", iconPath = "nonexistent_key"
        )
        assertNull(TaskIconResolver.resolve(context, app))
    }

    @Test fun fileSourceDecodesIconStoreFile() {
        // Save a real PNG to the icon store slot and resolve via the file path.
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.GREEN)
        }
        val path = iconStore.save(7L, bmp)
        bmp.recycle()
        val app = ChildApp(
            id = 7, name = "Fav", url = "https://fav.example.com/",
            iconSource = "favicon", iconPath = path
        )
        val resolved = TaskIconResolver.resolve(context, app)
        assertNotNull("on-disk icon file must decode to a bitmap", resolved)
        assertEquals(48, resolved!!.width)
    }

    @Test fun undecodableFileReturnsNullNotCrash() {
        // Write non-image bytes to a file and point the child at it.
        val junk = java.io.File(context.filesDir, "junk_icon.png").apply {
            writeBytes("not an image at all".toByteArray())
        }
        val app = ChildApp(
            id = 9, name = "Bad", url = "https://bad.example.com/",
            iconSource = "favicon", iconPath = junk.absolutePath
        )
        // Decode failure → null (TaskDescription omits icon). Must NOT throw.
        assertNull(TaskIconResolver.resolve(context, app))
    }

    @Test fun blankIconPathReturnsNull() {
        val app = ChildApp(
            id = 1, name = "No", url = "https://no.example.com/",
            iconSource = "favicon", iconPath = null
        )
        assertNull(TaskIconResolver.resolve(context, app))
    }

    private fun assertEquals(expected: Int, actual: Int) {
        org.junit.Assert.assertEquals(expected, actual)
    }
}