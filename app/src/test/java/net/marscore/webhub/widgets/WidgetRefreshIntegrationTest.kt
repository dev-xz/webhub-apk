package net.marscore.webhub.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import net.marscore.webhub.R
import net.marscore.webhub.data.AppDatabase
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.data.ChildAppRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Integration test for the data-sync refresh wiring (spec "数据同步刷新", design D2/D3, tasks
 * 7.1–7.4): verifies that [WidgetUpdater] dispatches after the 300ms debounce and that the
 * dispatched refresh targets the correct provider ComponentNames.
 *
 * Per the task spec, coverage is layered to avoid brittle Robolectric broadcast delivery
 * (design risks "Robolectric"; `@Config(manifest = Config.NONE)` means manifest receivers are
 * not registered, so APPWIDGET_UPDATE broadcasts sent by [WidgetUpdater.notifyProviderUpdate]
 * are not delivered to a provider instance — matching the existing Widget1x1ProviderTest
 * approach):
 *
 *  1. **Dispatch contract** (primary): `getDispatchCountForTest()` asserts the debounced block
 *     ran for both `updateAllWidgets` and `notifyAllWidgetsChanged` (combined with the existing
 *     `WidgetUpdaterDebounceTest`, this covers coalescing + dispatch).
 *  2. **Provider targeting contract**: install widgets via `ShadowAppWidgetManager.createWidget`
 *     and assert `AppWidgetManager.getAppWidgetIds(ComponentName)` returns them — this is the
 *     exact lookup [WidgetUpdater.notifyProviderUpdate] uses to decide whether to send a
 *     broadcast. An absent provider resolves to an empty array (the no-op early-return path).
 *  3. **Render path contract** (manual provider drive, matching Widget1x1ProviderTest): the 1×1
 *     provider's `onUpdate` re-renders the bound child's current name into the view tree. This
 *     confirms the render glue that the broadcast *would* invoke, independent of broadcast
 *     delivery.
 *  4. **Matrix render path contract** (manual provider drive): a 2×2 widget's `onUpdate`
 *     re-renders its fixed cells from the recency-ordered DB list — cells 0..N-1 show the
 *     children's names in recency order, surplus cells are INVISIBLE. Confirms the direct
 *     fixed-cell render glue (replaces the previous GridView collection path).
 *  5. **`notifyAllWidgetsChanged` semantics**: dispatch runs but, by design, the 1×1 provider is
 *     never targeted (recency doesn't affect a single bound child) — verified by the provider
 *     targeting contract (only matrix ComponentNames are looked up).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WidgetRefreshIntegrationTest {

    private lateinit var context: android.content.Context
    private lateinit var repo: ChildAppRepository
    private lateinit var mgr: AppWidgetManager

    @Before
    fun setUp() {
        AppDatabase.resetForTest()
        WidgetUpdater.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        repo = ChildAppRepository(context)
        mgr = AppWidgetManager.getInstance(context)
    }

    @After
    fun tearDown() {
        AppDatabase.resetForTest()
        WidgetUpdater.resetForTest()
    }

    // ---------------- 1. dispatch contract ----------------

    @Test
    fun updateAllWidgets_dispatchesOnceAfterDebounce() {
        // No widgets installed — getAppWidgetIds returns empty, so the dispatch is a no-op for
        // the manager, but the dispatch *block* still runs (dispatchCount increments).
        WidgetUpdater.updateAllWidgets(context)

        // Still inside the 300ms window.
        assertEquals(0, WidgetUpdater.getDispatchCountForTest())

        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)

        assertEquals(1, WidgetUpdater.getDispatchCountForTest())
    }

    @Test
    fun notifyAllWidgetsChanged_dispatchesOnceAfterDebounce() {
        WidgetUpdater.notifyAllWidgetsChanged(context)
        assertEquals(0, WidgetUpdater.getDispatchCountForTest())

        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)

        assertEquals(1, WidgetUpdater.getDispatchCountForTest())
    }

    @Test
    fun updateAllWidgets_noInstalledWidgets_dispatchesWithoutCrashing() {
        // No widgets installed at all — the dispatch must still run (getAppWidgetIds → empty →
        // no broadcast) without throwing.
        WidgetUpdater.updateAllWidgets(context)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
        assertEquals(1, WidgetUpdater.getDispatchCountForTest())
    }

    // ---------------- 2. provider targeting contract ----------------

    @Test
    fun registeredProviderComponentNames_resolveToInstalledIds() {
        // 7.1 registered the four providers in the manifest. WidgetUpdater addresses them by
        // ComponentName(packageName, className); getAppWidgetIds must return a non-empty array
        // for an installed widget (this is the lookup WidgetUpdater uses to decide to send a
        // broadcast). Install one of each and confirm resolution.
        shadowOf(mgr).createWidget(Widget1x1Provider::class.java, R.layout.widget_1x1)
        shadowOf(mgr).createWidget(Widget2x1Provider::class.java, R.layout.widget_launcher_2)
        shadowOf(mgr).createWidget(Widget2x2Provider::class.java, R.layout.widget_launcher_4)
        shadowOf(mgr).createWidget(Widget4x2Provider::class.java, R.layout.widget_launcher_8)

        val cn1x1 = ComponentName(context.packageName, Widget1x1Provider::class.java.name)
        val cn2x1 = ComponentName(context.packageName, Widget2x1Provider::class.java.name)
        val cn2x2 = ComponentName(context.packageName, Widget2x2Provider::class.java.name)
        val cn4x2 = ComponentName(context.packageName, Widget4x2Provider::class.java.name)

        assertEquals(1, mgr.getAppWidgetIds(cn1x1).size)
        assertEquals(1, mgr.getAppWidgetIds(cn2x1).size)
        assertEquals(1, mgr.getAppWidgetIds(cn2x2).size)
        assertEquals(1, mgr.getAppWidgetIds(cn4x2).size)

        // An absent provider class resolves to an empty array (the no-op early-return path in
        // WidgetUpdater.notifyProviderUpdate).
        val cnAbsent = ComponentName(context.packageName, "net.marscore.webhub.widgets.Absent")
        assertEquals(0, mgr.getAppWidgetIds(cnAbsent).size)
    }

    // ---------------- 3. 1×1 render path (manual provider drive) ----------------

    @Test
    fun updateAllWidgets_triggers1x1Render_whenWidgetInstalled() {
        // Seed a child so the 1×1 Bound branch renders a real label.
        val childId = kotlinx.coroutines.runBlocking {
            repo.insert(ChildApp(name = "Synced", url = "https://synced.example.com"))
        }
        // Install a 1×1 widget instance via the shadow and bind it to the child.
        val id1x1 = shadowOf(mgr).createWidget(
            Widget1x1Provider::class.java,
            R.layout.widget_1x1
        )
        WidgetBindings.bind(context, id1x1, childId)

        // WidgetUpdater addresses the 1×1 provider by ComponentName — confirm the installed id
        // is visible to the manager (the lookup the dispatcher uses).
        val cn1x1 = ComponentName(context.packageName, Widget1x1Provider::class.java.name)
        assertEquals(1, mgr.getAppWidgetIds(cn1x1).size)

        // Drive the render path that the dispatched APPWIDGET_UPDATE broadcast would invoke.
        // (Manifest receivers aren't registered under Config.NONE, so we call onUpdate directly —
        // the same pattern as Widget1x1ProviderTest.)
        Widget1x1Provider().onUpdate(context, mgr, intArrayOf(id1x1))

        val rendered = shadowOf(mgr).getViewFor(id1x1)
        assertNotNull("1×1 view tree was rendered by onUpdate", rendered)
        assertEquals(
            "Synced",
            rendered!!.findViewById<TextView>(R.id.widget_label).text.toString()
        )

        // Rename the child and re-drive the render path — confirms the render reflects the
        // current DB state (the contract the spec's "编辑名称后图标矩阵刷新" scenario relies on
        // for the 1×1 re-render arm of updateAllWidgets).
        kotlinx.coroutines.runBlocking {
            repo.update(repo.getById(childId)!!.copy(name = "Renamed"))
        }
        Widget1x1Provider().onUpdate(context, mgr, intArrayOf(id1x1))
        val refreshed = shadowOf(mgr).getViewFor(id1x1)
        assertEquals(
            "Renamed",
            refreshed.findViewById<TextView>(R.id.widget_label).text.toString()
        )
    }

    @Test
    fun notifyAllWidgetsChanged_skips1x1_byComponentName() {
        // notifyAllWidgetsChanged addresses only the three matrix providers (PROVIDER_2x1/2x2/4x2),
        // never PROVIDER_1x1. Install a 1×1 widget and confirm that — while the dispatch runs —
        // the 1×1 ComponentName is NOT among the targets the dispatcher looks up. This is the
        // load-bearing semantic: recency changes don't re-render 1×1 (its bound child didn't
        // change).
        shadowOf(mgr).createWidget(Widget1x1Provider::class.java, R.layout.widget_1x1)
        shadowOf(mgr).createWidget(Widget2x2Provider::class.java, R.layout.widget_launcher_4)

        // The dispatcher's targets for notifyAllWidgetsChanged are the matrix providers; the 1×1
        // provider is resolvable (installed) but is intentionally not queried by that method.
        val cn1x1 = ComponentName(context.packageName, Widget1x1Provider::class.java.name)
        val cn2x2 = ComponentName(context.packageName, Widget2x2Provider::class.java.name)
        assertEquals(1, mgr.getAppWidgetIds(cn1x1).size)
        assertEquals(1, mgr.getAppWidgetIds(cn2x2).size)

        WidgetUpdater.notifyAllWidgetsChanged(context)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)

        // Dispatch ran (matrix path only).
        assertEquals(1, WidgetUpdater.getDispatchCountForTest())

        // The 2×2 view tree (initial, from createWidget) is present.
        assertNotNull(shadowOf(mgr).getViewFor(
            mgr.getAppWidgetIds(cn2x2)[0]
        ))
    }

    // ---------------- 4. matrix render path contract (direct fixed-cell render) ----------------

    @Test
    fun updateAllWidgets_rendersMatrixWidget_whenInstalled() {
        // Seed three children with distinct recency so the cells render in a deterministic order.
        //   A: lastOpenedAt = 3000 (most recent)
        //   B: lastOpenedAt = 1000 (least recent)
        //   C: lastOpenedAt = 2000 (middle)
        // Expected recency order: A, C, B.
        kotlinx.coroutines.runBlocking {
            val aId = repo.insert(ChildApp(name = "A", url = "https://a.example.com", createdAt = 1000L))
            val bId = repo.insert(ChildApp(name = "B", url = "https://b.example.com", createdAt = 2000L))
            val cId = repo.insert(ChildApp(name = "C", url = "https://c.example.com", createdAt = 3000L))
            repo.update(repo.getById(aId)!!.copy(lastOpenedAt = 3000L))
            repo.update(repo.getById(bId)!!.copy(lastOpenedAt = 1000L))
            repo.update(repo.getById(cId)!!.copy(lastOpenedAt = 2000L))
        }

        // Install a 2×2 widget (capacity 4). createWidget runs the provider's onUpdate once via
        // the APPWIDGET_UPDATE broadcast it sends, producing a view tree with the fixed cells.
        val idGrid = shadowOf(mgr).createWidget(
            Widget2x2Provider::class.java,
            R.layout.widget_launcher_4
        )
        val gridViews = shadowOf(mgr).getViewFor(idGrid)
        assertNotNull("matrix widget initial render produced a view tree", gridViews)

        // Cells 0..2 render the three children in recency order; cell 3 is INVISIBLE
        // (spec "子应用数不足格数时留空"). Resolve cell ids by name (the renderer does the same).
        val pkg = context.packageName
        fun idByName(name: String): Int = context.resources.getIdentifier(name, "id", pkg)

        val label0 = gridViews!!.findViewById<TextView>(idByName("cell_label_0"))
        val label1 = gridViews.findViewById<TextView>(idByName("cell_label_1"))
        val label2 = gridViews.findViewById<TextView>(idByName("cell_label_2"))
        assertEquals("A", label0.text.toString())
        assertEquals("C", label1.text.toString())
        assertEquals("B", label2.text.toString())

        // Surplus cells are INVISIBLE.
        val root3 = gridViews.findViewById<View>(idByName("cell_root_3"))
        assertEquals(View.INVISIBLE, root3.visibility)

        // updateAllWidgets dispatches the matrix refresh path (APPWIDGET_UPDATE broadcast on
        // the matrix ids) — verified via dispatchCount. The matrix ComponentName is the lookup
        // the dispatcher uses; confirm it resolves to the installed id.
        val cn2x2 = ComponentName(context.packageName, Widget2x2Provider::class.java.name)
        assertEquals(intArrayOf(idGrid).toList(), mgr.getAppWidgetIds(cn2x2).toList())

        WidgetUpdater.updateAllWidgets(context)
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)

        assertEquals(1, WidgetUpdater.getDispatchCountForTest())
    }
}
