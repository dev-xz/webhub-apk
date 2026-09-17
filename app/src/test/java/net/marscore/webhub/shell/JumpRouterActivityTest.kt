package net.marscore.webhub.shell

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import net.marscore.webhub.data.AppDatabase
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.data.ChildAppRepository
import net.marscore.webhub.icons.UrlValidator
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.net.URLEncoder

/**
 * Routing logic for the external jump scheme (tasks 2.1 + 2.2).
 *
 * Drives [JumpRouterActivity.onCreate] via Robolectric and asserts:
 *  - invalid/missing `url` -> `shell_jump_invalid` Toast + finishing, no shell launch.
 *  - `javascript:` / `file:` schemes rejected by [UrlValidator.normalize].
 *  - no match -> `shell_jump_no_match` Toast + finishing, no shell launch.
 *  - match -> forwards to [WebAppActivity] with `EXTRA_CHILD_ID` + `EXTRA_TARGET_URL`.
 *
 * Started activities are read via `ShadowContextWrapper.getNextStartedActivity()` (Robolectric 4.13
 * routes `Activity.startActivity` through the ContextWrapper shadow; the legacy
 * `ShadowActivityManager.getNextStartedActivity` was removed).
 *
 * The DB is set up the same way as [net.marscore.webhub.data.ChildAppRepositoryTest]:
 * `AppDatabase.resetForTest()` + a real [ChildAppRepository] on the application context, so
 * `runBlocking { repository.getAll() }` inside the activity reads actual rows.
 *
 * Robolectric @Config(sdk=[33]) per AGENTS.md (targetSdk 36 > Robolectric ceiling).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class JumpRouterActivityTest {

    private lateinit var context: android.content.Context
    private lateinit var repo: ChildAppRepository

    @Before fun setup() {
        AppDatabase.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        repo = ChildAppRepository(context)
    }

    @After fun teardown() {
        AppDatabase.resetForTest()
    }

    private fun jumpIntent(encodedUrl: String? = null): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setClass(context, JumpRouterActivity::class.java)
            val uri = if (encodedUrl != null) "webhub://jump/auto?url=$encodedUrl"
                      else "webhub://jump/auto"
            data = Uri.parse(uri)
        }

    private fun launch(intent: Intent) =
        Robolectric.buildActivity(JumpRouterActivity::class.java, intent).create()

    /** The next intent the activity forwarded via startActivity, or null if none was started. */
    private fun nextStartedIntent(activity: JumpRouterActivity): Intent? =
        shadowOf(activity).nextStartedActivity

    @Test fun invalidUrlParameterShowsInvalidToastAndFinishes() {
        val controller = launch(jumpIntent(encodedUrl = null))
        val activity = controller.get()
        assertEquals("链接无效", ShadowToast.getTextOfLatestToast())
        assertTrue("activity should be finishing on invalid url", activity.isFinishing)
        assertNull("no shell should be started on invalid url", nextStartedIntent(activity))
    }

    @Test fun javascriptSchemeRejected() {
        val encoded = URLEncoder.encode("javascript:alert(1)", "UTF-8")
        val controller = launch(jumpIntent(encoded))
        val activity = controller.get()
        assertEquals("链接无效", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
        assertNull(nextStartedIntent(activity))
    }

    @Test fun fileSchemeRejected() {
        val encoded = URLEncoder.encode("file:///etc/passwd", "UTF-8")
        val controller = launch(jumpIntent(encoded))
        val activity = controller.get()
        assertEquals("链接无效", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
        assertNull(nextStartedIntent(activity))
    }

    @Test fun noMatchShowsNoMatchToastAndFinishes() {
        // Insert a child whose origin does NOT match the target.
        kotlinx.coroutines.runBlocking {
            repo.insert(ChildApp(name = "Other", url = "https://other.example.com/"))
        }
        val encoded = URLEncoder.encode("https://code.marscore.net/", "UTF-8")
        val controller = launch(jumpIntent(encoded))
        val activity = controller.get()
        assertEquals("无匹配子应用", ShadowToast.getTextOfLatestToast())
        assertTrue(activity.isFinishing)
        assertNull("no shell should be started on no-match", nextStartedIntent(activity))
    }

    @Test fun matchForwardsToWebAppActivityWithCorrectExtras() {
        val childId = kotlinx.coroutines.runBlocking {
            repo.insert(ChildApp(name = "Code", url = "https://code.marscore.net:31410/"))
        }
        val rawTarget = "https://code.marscore.net:31410/?folder=/x"
        val encoded = URLEncoder.encode(rawTarget, "UTF-8")
        val expectedTarget = UrlValidator.normalize(rawTarget)!!

        val controller = launch(jumpIntent(encoded))
        val activity = controller.get()
        // On a match the activity finishes after forwarding.
        assertTrue("activity should finish after forwarding", activity.isFinishing)

        val next = nextStartedIntent(activity)
        assertNotNull("WebAppActivity should be started on match", next)
        assertEquals(WebAppActivity::class.java.name, next!!.component?.className)
        assertEquals(childId, next.getLongExtra(WebAppActivity.EXTRA_CHILD_ID, -1L))
        assertEquals(expectedTarget, next.getStringExtra(WebAppActivity.EXTRA_TARGET_URL))
    }
}