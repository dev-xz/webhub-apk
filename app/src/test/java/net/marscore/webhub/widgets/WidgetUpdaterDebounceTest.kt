package net.marscore.webhub.widgets

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Verifies the 300ms debounce/coalescing contract of [WidgetUpdater]: rapid repeated calls
 * within the debounce window collapse into a single dispatch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WidgetUpdaterDebounceTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        WidgetUpdater.resetForTest()
    }

    @Test
    fun notifyAllWidgetsChanged_rapidCalls_coalesceToOneDispatch() {
        // Three synchronous calls within the 300ms debounce window — all keyed identically.
        repeat(3) {
            WidgetUpdater.notifyAllWidgetsChanged(context)
        }

        // Nothing should have dispatched yet (still inside the debounce window).
        assertEquals(0, WidgetUpdater.getDispatchCountForTest())

        // Advance the main looper past the 300ms debounce delay and flush the queued runnable.
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)

        // All three calls share one dedup key → exactly one dispatch.
        assertEquals(1, WidgetUpdater.getDispatchCountForTest())
    }

    @Test
    fun updateAllWidgets_rapidCalls_coalesceToOneDispatch() {
        repeat(5) {
            WidgetUpdater.updateAllWidgets(context)
        }
        assertEquals(0, WidgetUpdater.getDispatchCountForTest())

        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)

        assertEquals(1, WidgetUpdater.getDispatchCountForTest())
    }

    @Test
    fun distinctMethods_dispatchIndependently() {
        // Different keys → both should dispatch after the looper flushes.
        WidgetUpdater.notifyAllWidgetsChanged(context)
        WidgetUpdater.updateAllWidgets(context)

        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)

        assertEquals(2, WidgetUpdater.getDispatchCountForTest())
    }
}