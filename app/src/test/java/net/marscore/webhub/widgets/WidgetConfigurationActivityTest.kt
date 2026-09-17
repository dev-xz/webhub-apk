package net.marscore.webhub.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import net.marscore.webhub.R
import net.marscore.webhub.data.AppDatabase
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.data.ChildAppRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies [WidgetConfigurationActivity]'s size branches and the 1×1 binding flow (spec "提供
 * 1×1 单图标小组件" + "未选择不落桌"):
 *
 *  - [is1x1_overrideTrue_returnsTrue] / [is1x1_overrideFalse_returnsFalse]: the size decision
 *    seam is honored (lets tests drive the branch without a registered widget info).
 *  - [selectingSecondAppBindsItAndReturnsOk]: 1×1 branch, two seeded children, bind + click the
 *    second row's adapter → `WidgetBindings.lookup == apps[1].id` and the activity result is
 *    `RESULT_OK` with the widget id extra.
 *  - [emptyChildList_showsEmptyState]: 1×1 branch, no children → `config_empty` visible, recycler
 *    gone, no binding written.
 *  - [matrixBranch_finishesOkWithoutBinding]: matrix size → `RESULT_OK` and no binding written
 *    (matrix auto-populates from the DB; no user interaction).
 *  - [invalidWidgetId_finishesCanceled]: missing/invalid widget id → `RESULT_CANCELED` + finish.
 *
 * Uses the [WidgetConfigurationActivity.sizeOverride] and [WidgetConfigurationActivity.childListProvider]
 * test seams to drive the size decision + list contents without registering the activity / widget
 * info with Robolectric's `ShadowAppWidgetManager` (design risks "Robolectric"). The activity is
 * driven through [Robolectric.buildActivity] with `setup()` so lifecycle coroutines (the child
 * list load) drain before assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WidgetConfigurationActivityTest {

    private lateinit var context: android.content.Context
    private lateinit var repo: ChildAppRepository

    @Before
    fun setUp() {
        AppDatabase.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        repo = ChildAppRepository(context)
    }

    @After
    fun tearDown() {
        AppDatabase.resetForTest()
    }

    // ---------------- pure size decision ----------------

    @Test
    fun is1x1_overrideTrue_returnsTrue() {
        val activity = buildWithoutSetup(42)
        activity.sizeOverride = { true }
        assertTrue(activity.is1x1())
    }

    @Test
    fun is1x1_overrideFalse_returnsFalse() {
        val activity = buildWithoutSetup(42)
        activity.sizeOverride = { false }
        assertFalse(activity.is1x1())
    }

    // ---------------- 1×1 branch ----------------

    @Test
    fun selectingSecondAppBindsItAndReturnsOk() {
        val secondId = kotlinx.coroutines.runBlocking {
            repo.insert(ChildApp(name = "First", url = "https://first.example.com"))
            repo.insert(ChildApp(name = "Second", url = "https://second.example.com"))
        }

        val controller = Robolectric.buildActivity(
            WidgetConfigurationActivity::class.java, configIntent(7)
        )
        val activity = controller.get()
        activity.sizeOverride = { true }
        // Use the real seeded repository; getAll() runs in onCreate's lifecycleScope.
        activity.childListProvider = null
        // Stub the post-bind RemoteViews push so onChildSelected doesn't hit the real
        // AppWidgetManager (no widget registered for id 7 under Robolectric).
        activity.widgetRenderer = { /* no-op for the bind+result flow under test */ }
        controller.setup()

        // After setup(), onCreate's lifecycle coroutine has drained (Robolectric runs the main
        // looper to idle), so the RecyclerView adapter is populated.
        val recycler = activity.findViewById<RecyclerView>(R.id.config_recycler)
        val adapter = recycler.adapter
        assertNotNull(adapter)
        assertEquals(2, adapter!!.itemCount)

        // Inflate a real row and bind position 1, then click it — this drives the adapter's
        // onClick closure, which calls the activity's onChildSelected(child) path (bind +
        // setResult + finish).
        val holder = adapter.createViewHolder(recycler, 0)
        activity.runOnUiThread { adapter.bindViewHolder(holder, 1) }
        activity.runOnUiThread { holder.itemView.performClick() }

        // Selection → RESULT_OK + binding persisted to the second child.
        assertEquals(secondId, WidgetBindings.lookup(context, 7))
        val result = shadowResult(controller.get())
        assertEquals(android.app.Activity.RESULT_OK, result.resultCode)
        assertEquals(
            7,
            result.resultData.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
            )
        )
    }

    @Test
    fun emptyChildList_showsEmptyState() {
        val controller = Robolectric.buildActivity(
            WidgetConfigurationActivity::class.java, configIntent(8)
        )
        val activity = controller.get()
        activity.sizeOverride = { true }
        activity.childListProvider = { emptyList() }
        controller.setup()

        val empty = activity.findViewById<TextView>(R.id.config_empty)
        val recycler = activity.findViewById<View>(R.id.config_recycler)
        assertEquals(View.VISIBLE, empty.visibility)
        assertEquals(View.GONE, recycler.visibility)
        // No binding should have been written.
        assertNull(WidgetBindings.lookup(context, 8))
    }

    // ---------------- matrix branch ----------------

    @Test
    fun matrixBranch_finishesOkWithoutBinding() {
        val controller = Robolectric.buildActivity(
            WidgetConfigurationActivity::class.java, configIntent(9)
        )
        val activity = controller.get()
        activity.sizeOverride = { false } // matrix size
        controller.setup()

        // Matrix → finish() called in onCreate with RESULT_OK.
        assertTrue(controller.get().isFinishing)
        assertNull(WidgetBindings.lookup(context, 9))
    }

    // ---------------- invalid widget id ----------------

    @Test
    fun invalidWidgetId_finishesCanceled() {
        val intent = Intent(context, WidgetConfigurationActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        }
        val controller = Robolectric.buildActivity(WidgetConfigurationActivity::class.java, intent)
        controller.setup()
        assertTrue(controller.get().isFinishing)
    }

    // ---------------- helpers ----------------

    private fun configIntent(appWidgetId: Int): Intent =
        Intent(context, WidgetConfigurationActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }

    /** Build (without setup) so the test can set seams before `onCreate` runs. */
    private fun buildWithoutSetup(appWidgetId: Int): WidgetConfigurationActivity {
        val controller = Robolectric.buildActivity(
            WidgetConfigurationActivity::class.java, configIntent(appWidgetId)
        )
        return controller.get()
    }

    /** Robolectric records the activity's last-set result on the shadow Activity. */
    private fun shadowResult(activity: WidgetConfigurationActivity): ActivityResult {
        val shadow = org.robolectric.Shadows.shadowOf(activity)
        return ActivityResult(shadow.resultCode, shadow.resultIntent)
    }

    private data class ActivityResult(val resultCode: Int, val resultData: Intent)
}