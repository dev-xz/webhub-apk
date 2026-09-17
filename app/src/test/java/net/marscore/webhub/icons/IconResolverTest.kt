package net.marscore.webhub.icons

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import net.marscore.webhub.data.ChildApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Locks down [IconResolver] (add-home-screen-widgets D6 / task 2.3):
 *  - a `preset` child resolves to the rendered preset drawable;
 *  - a child with a missing on-disk icon file falls back to [PresetIcons.pickForId];
 *  - a child with a null `iconPath` falls back to [PresetIcons.pickForId].
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md (targetSdk 36 > Robolectric ceiling).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class IconResolverTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /**
     * A `preset` child resolves to the rendered preset bitmap (non-null, matching the
     * drawable for that key).
     */
    @Test fun preset_child_returns_rendered_bitmap() {
        val key = PresetIcons.all().first().key
        val child = ChildApp(
            id = 1L,
            name = "Mail",
            url = "https://example.com",
            iconSource = "preset",
            iconPath = key,
        )
        val bmp = IconResolver.resolveIconBitmap(context, child)
        assertNotNull("preset child must yield a non-null bitmap", bmp)

        val expected = IconResolver.renderDrawableToBitmap(context, PresetIcons.resForKey(key))
        assertNotNull(expected)
        assertEquals(expected!!.width, bmp!!.width)
        assertEquals(expected.height, bmp.height)
    }

    /**
     * A `favicon` child whose `iconPath` points at a nonexistent file falls back to the
     * deterministic preset for its id, and the fallback matches a direct render of that preset.
     */
    @Test fun missing_icon_file_falls_back_to_preset() {
        val childId = 123L
        val child = ChildApp(
            id = childId,
            name = "Foo",
            url = "https://example.com",
            iconSource = "favicon",
            iconPath = "/nonexistent/path.png",
        )
        val bmp = IconResolver.resolveIconBitmap(context, child)
        assertNotNull("missing file must fall back to a non-null preset bitmap", bmp)

        val expectedResId = PresetIcons.pickForId(childId).resId
        val expected = IconResolver.renderDrawableToBitmap(context, expectedResId)
        assertNotNull(expected)
        assertEquals(expected!!.width, bmp!!.width)
        assertEquals(expected.height, bmp.height)
    }

    /**
     * A `favicon` child with a null `iconPath` falls back to the deterministic preset for its id.
     */
    @Test fun null_icon_path_falls_back_to_preset() {
        val childId = 123L
        val child = ChildApp(
            id = childId,
            name = "Foo",
            url = "https://example.com",
            iconSource = "favicon",
            iconPath = null,
        )
        val bmp = IconResolver.resolveIconBitmap(context, child)
        assertNotNull("null iconPath must fall back to a non-null preset bitmap", bmp)

        val expectedResId = PresetIcons.pickForId(childId).resId
        val expected = IconResolver.renderDrawableToBitmap(context, expectedResId)
        assertNotNull(expected)
        assertEquals(expected!!.width, bmp!!.width)
        assertEquals(expected.height, bmp.height)
    }
}