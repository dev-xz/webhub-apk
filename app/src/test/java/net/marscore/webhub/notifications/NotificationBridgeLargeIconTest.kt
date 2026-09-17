package net.marscore.webhub.notifications

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.app.NotificationManagerCompat
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
 * notification-child-icon tasks 1.1/1.2: NotificationBridge.show must set the child app's icon as
 * the notification large icon when one is resolvable, and must NOT set a large icon (while still
 * posting the notification) when the icon is missing or undecodable.
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md (targetSdk 36 > Robolectric ceiling).
 * @GraphicsMode(NATIVE) because the "undecodable file → null" branch needs real Skia decoding
 * (AGENTS.md: Robolectric's shadow returns a fake 100×100 bitmap instead of null for junk bytes).
 * The preset-render success branch also works under NATIVE.
 *
 * Permission note: NotificationBridge.show silently returns when
 * `NotificationManagerCompat.areNotificationsEnabled()` is false. Under Robolectric that defaults
 * to true, so the notify path runs. We verify the notification was actually posted via the shadow
 * NotificationManager for both branches.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotificationBridgeLargeIconTest {

    private lateinit var context: android.content.Context
    private lateinit var iconStore: IconStore

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        iconStore = IconStore(context)
    }

    @Test fun presetChildShowsLargeIcon() {
        // Preset source: TaskIconResolver renders the preset drawable to a bitmap (no disk decode).
        val child = ChildApp(
            id = 11, name = "Mail", url = "https://mail.example.com/",
            iconSource = "preset", iconPath = "mail"
        )
        // Diagnose: confirm the resolver itself yields a bitmap here (isolates resolver vs builder).
        val resolved = net.marscore.webhub.shell.TaskIconResolver.resolve(context, child)
        assertNotNull("resolver must return a bitmap for preset 'mail'", resolved)

        val notif = buildAndPost(child, tag = "t1")

        assertNotNull("preset child must yield a notification", notif)
        assertNotNull("notification must carry the child preset icon as largeIcon",
            notif!!.largeIconCompat())
    }

    @Test fun faviconChildShowsLargeIcon() {
        // favicon/upload source: write a real PNG to IconStore and resolve via the file path.
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawColor(Color.GREEN)
        }
        val path = iconStore.save(22L, bmp)
        bmp.recycle()
        val child = ChildApp(
            id = 22, name = "Fav", url = "https://fav.example.com/",
            iconSource = "favicon", iconPath = path
        )

        val notif = buildAndPost(child, tag = "t2")

        assertNotNull(notif)
        assertNotNull("favicon child must carry the decoded IconStore file as largeIcon",
            notif!!.largeIconCompat())
    }

    @Test fun undecodableIconFallsBackToNoLargeIcon() {
        // Point the child at a file of non-image bytes. Real Skia decode (NATIVE mode) returns null
        // instead of Robolectric's fake bitmap — so no largeIcon is set, and the notification is
        // still posted (the send must not be blocked by an icon failure).
        val junk = java.io.File(context.filesDir, "notif_junk.png").apply {
            writeBytes("definitely not a PNG".toByteArray())
        }
        val child = ChildApp(
            id = 33, name = "Bad", url = "https://bad.example.com/",
            iconSource = "favicon", iconPath = junk.absolutePath
        )

        val notif = buildAndPost(child, tag = "t3")

        assertNotNull("notification must still be posted even when the icon fails to decode",
            notif)
        assertNull("no largeIcon when the icon file is undecodable", notif!!.largeIconCompat())
    }

    @Test fun missingIconPathFallsBackToNoLargeIcon() {
        // No icon path at all → TaskIconResolver returns null → no largeIcon, notification posts.
        val child = ChildApp(
            id = 44, name = "NoIcon", url = "https://noicon.example.com/",
            iconSource = "favicon", iconPath = null
        )

        val notif = buildAndPost(child, tag = "t4")

        assertNotNull(notif)
        assertNull("no largeIcon when the child has no icon path", notif!!.largeIconCompat())
    }

    @Test fun unknownPresetKeyFallsBackToNoLargeIcon() {
        val child = ChildApp(
            id = 55, name = "X", url = "https://x.example.com/",
            iconSource = "preset", iconPath = "no_such_preset_key"
        )

        val notif = buildAndPost(child, tag = "t5")

        assertNotNull(notif)
        assertNull("no largeIcon when the preset key is unknown", notif!!.largeIconCompat())
    }

    // ---------------- helpers ----------------

    /**
     * Build + post a notification via [NotificationBridge.show] for [child], then return the
     * Notification the shadow NotificationManager received (or null if none was posted).
     *
     * show() is a @JavascriptInterface taking (title, body, tag, iconUrl); the iconUrl param is
     * unused by the bridge's icon resolution (which uses the child's stored icon, not the page's
     * icon URL) so we pass an empty string.
     */
    private fun buildAndPost(child: ChildApp, tag: String): Notification? {
        val bridge = NotificationBridge(context, child)
        // Guard: if Robolectric reports notifications disabled for the app, show() no-ops. Under
        // default Robolectric this is enabled, but assert so a future env change fails loudly
        // rather than silently passing the "no largeIcon" assertions for the wrong reason.
        assertTrue("test precondition: notifications must be enabled for the app",
            NotificationManagerCompat.from(context).areNotificationsEnabled())

        bridge.show("Hello", "World", tag, "")

        // Robolectric's ShadowNotificationManager backs NotificationManager.getActiveNotifications
        // with the notifications posted via notify(id, Notification).
        val mgr = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        val expectedId = NotificationIds.forTag(child.id, tag)
        return mgr.activeNotifications.firstOrNull { it.id == expectedId }?.notification
    }

    /**
     * Canonical large-icon accessor for the test. On API 23+ [androidx.core.app.NotificationCompat]
     * stores the large icon via [android.graphics.drawable.Icon] in the notification extras, and
     * the platform field [Notification.largeIcon] is lazily/unpopulated under Robolectric.
     * [Notification.getLargeIcon] reconstructs the icon from extras, so it's the reliable accessor
     * here; on the real device both the field and the getter are populated, so this only affects
     * the test assertion path, not the shipped behavior.
     */
    private fun Notification.largeIconCompat(): android.graphics.drawable.Icon? =
        try { getLargeIcon() } catch (_: Throwable) { null }
}
