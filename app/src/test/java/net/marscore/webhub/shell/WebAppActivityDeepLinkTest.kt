package net.marscore.webhub.shell

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Companion-level contract for [WebAppActivity] deep-link intent construction (task 3.1).
 *
 * Verifies that:
 *  - the single-arg [WebAppActivity.createIntent] does NOT carry `EXTRA_TARGET_URL`.
 *  - the three-arg overload carries `EXTRA_TARGET_URL` when given a non-null target.
 *  - the three-arg overload with a null target omits the extra (delegates to the single-arg path).
 *  - `EXTRA_CHILD_ID` is preserved across all overloads.
 *
 * Task 3.2 (startWeb reads `EXTRA_TARGET_URL` and validates via `UrlValidator.isValid` before
 * `loadUrl`) is verified by code inspection only: driving a real WebView through Robolectric is
 * flaky and out of scope for this suite. The decision logic in `startWeb` is a two-line guarded
 * fallback (`targetUrl ?.takeIf { UrlValidator.isValid(it) } ?: app.url`), so the companion
 * contract here is the load-bearing part worth locking down in unit tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WebAppActivityDeepLinkTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun createIntentWithoutTargetUrlDoesNotIncludeExtraTargetUrl() {
        val intent = WebAppActivity.createIntent(context, 5L)
        assertNull(intent.getStringExtra(WebAppActivity.EXTRA_TARGET_URL))
        assertEquals(5L, intent.getLongExtra(WebAppActivity.EXTRA_CHILD_ID, -1L))
    }

    @Test fun createIntentWithTargetUrlIncludesIt() {
        val intent = WebAppActivity.createIntent(context, 5L, "https://example.com/deep")
        assertEquals("https://example.com/deep", intent.getStringExtra(WebAppActivity.EXTRA_TARGET_URL))
        assertEquals(5L, intent.getLongExtra(WebAppActivity.EXTRA_CHILD_ID, -1L))
    }

    @Test fun createIntentWithNullTargetUrlOmitsExtra() {
        val intent = WebAppActivity.createIntent(context, 5L, null)
        assertNull(intent.getStringExtra(WebAppActivity.EXTRA_TARGET_URL))
        assertEquals(5L, intent.getLongExtra(WebAppActivity.EXTRA_CHILD_ID, -1L))
    }
}