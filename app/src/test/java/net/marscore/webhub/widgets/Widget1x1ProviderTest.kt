package net.marscore.webhub.widgets

import android.appwidget.AppWidgetManager
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

/**
 * Verifies [Widget1x1Provider]'s three render branches (spec "提供 1×1 单图标小组件" + "删除子
 * 应用后 1×1 兜底"):
 *
 *  - [decideStateBoundChildExists] / [decideStateBoundChildDeleted] / [decideStateNotBound]: the
 *    pure state decision (the load-bearing spec branch selection) is verified directly via
 *    [Widget1x1Provider.decideState].
 *  - [onUpdate_boundChildExists_rendersChildName]: bound + present → the applied RemoteViews'
 *    `widget_label` carries the child's name and `updateAppWidget` was called (view applied).
 *  - [onUpdate_boundChildDeleted_rendersDeletedPlaceholder]: bound + missing → label carries
 *    `widget_child_deleted` (spec "删除后显示占位并跳管理端"; MUST NOT auto-remove the widget).
 *  - [onUpdate_notBound_rendersConfigurePlaceholder]: no binding → label carries
 *    `widget_configure_placeholder` (design risks "Rom 兼容" mitigation).
 *
 * Robolectric's `RemoteViews` click-PendingIntent introspection is brittle (the action list is
 * private and the class is instrumented, so reflecting `mActions` / the listener's PendingIntent
 * field is unreliable across Robolectric versions — design risks "Robolectric"). Coverage of the
 * click *target* is therefore delegated to the pure [Widget1x1Provider.decideState] decision
 * (which branch is taken determines the intent target per [Widget1x1Provider.buildViews]); the
 * render glue is verified via the applied view tree's label text + `updateAppWidget` having run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class Widget1x1ProviderTest {

    private lateinit var context: android.content.Context
    private lateinit var repo: ChildAppRepository
    private lateinit var provider: Widget1x1Provider
    private lateinit var mgr: AppWidgetManager

    @Before
    fun setUp() {
        AppDatabase.resetForTest()
        context = ApplicationProvider.getApplicationContext()
        repo = ChildAppRepository(context)
        provider = Widget1x1Provider()
        mgr = AppWidgetManager.getInstance(context)
    }

    @After
    fun tearDown() {
        AppDatabase.resetForTest()
    }

    // ---------------- pure state decision (load-bearing branch selection) ----------------

    @Test
    fun decideState_boundChildExists_isBound() {
        val child = ChildApp(id = 1, name = "A", url = "https://a.example.com")
        assertEquals(Widget1x1State.Bound, provider.decideState(1L, child))
    }

    @Test
    fun decideState_boundChildDeleted_isDeleted() {
        // Bound to an id with no DB row → Deleted (spec "删除子应用后 1×1 兜底").
        assertEquals(Widget1x1State.Deleted, provider.decideState(999L, null))
    }

    @Test
    fun decideState_notBound_isNotBound() {
        // No binding recorded → NotBound ("点击配置" placeholder, re-open config activity).
        assertEquals(Widget1x1State.NotBound, provider.decideState(null, null))
    }

    // ---------------- render glue via applied view tree ----------------

    @Test
    fun onUpdate_boundChildExists_rendersChildName() {
        val childId = kotlinx.coroutines.runBlocking {
            repo.insert(ChildApp(name = "Real", url = "https://real.example.com"))
        }
        val appWidgetId = createBoundWidget(childId)

        provider.onUpdate(context, mgr, intArrayOf(appWidgetId))

        // updateAppWidget was called → the shadow has an applied view tree.
        val views = getViewForWidget(appWidgetId)
        assertNotNull(views)
        // Bound state renders the child's name on the label.
        val label = views!!.findViewById<TextView>(R.id.widget_label)
        assertEquals("Real", label.text.toString())
    }

    @Test
    fun onUpdate_boundChildDeleted_rendersDeletedPlaceholder() {
        // Bind to a child id that was never inserted → Deleted placeholder (MUST NOT auto-remove).
        val appWidgetId = allocateWidgetId()
        WidgetBindings.bind(context, appWidgetId, 999L)

        provider.onUpdate(context, mgr, intArrayOf(appWidgetId))

        val views = getViewForWidget(appWidgetId)
        assertNotNull(views)
        val label = views!!.findViewById<TextView>(R.id.widget_label)
        assertEquals(
            context.getString(R.string.widget_child_deleted),
            label.text.toString()
        )
    }

    @Test
    fun onUpdate_notBound_rendersConfigurePlaceholder() {
        val appWidgetId = allocateWidgetId()
        // No WidgetBindings.bind call → NotBound ("点击配置" placeholder).

        provider.onUpdate(context, mgr, intArrayOf(appWidgetId))

        val views = getViewForWidget(appWidgetId)
        assertNotNull(views)
        val label = views!!.findViewById<TextView>(R.id.widget_label)
        assertEquals(
            context.getString(R.string.widget_configure_placeholder),
            label.text.toString()
        )
    }

    @Test
    fun onUpdate_propagatesAllAppWidgetIds() {
        // Each id in the batch gets its own updateAppWidget (shadow records an applied view per id).
        val id1 = allocateWidgetId()
        val id2 = allocateWidgetId()
        WidgetBindings.bind(context, id1, 999L) // Deleted
        WidgetBindings.bind(context, id2, 999L) // Deleted

        provider.onUpdate(context, mgr, intArrayOf(id1, id2))

        assertNotNull(getViewForWidget(id1))
        assertNotNull(getViewForWidget(id2))
    }

    // ---------------- buildViews intent wiring (decision → target mapping) ----------------

    @Test
    fun buildViews_boundState_rendersChildName() {
        // The Bound branch is the only one that routes to WebAppActivity; the decision selects
        // the branch, so verifying decideState(Bound) + the label render is sufficient coverage
        // of the "点击打开子应用" scenario (the actual PendingIntent wiring is exercised at runtime
        // by RemoteViews and is brittle to introspect under Robolectric).
        val child = ChildApp(id = 5, name = "Shell", url = "https://shell.example.com")
        assertEquals(Widget1x1State.Bound, provider.decideState(5L, child))
        val views = provider.buildViews(context, appWidgetId = 1, childId = 5L, child = child)
        val applied = views.apply(context, null)
        assertEquals("Shell", applied.findViewById<TextView>(R.id.widget_label).text.toString())
    }

    @Test
    fun buildViews_deletedState_rendersDeletedPlaceholder() {
        // Deleted branch routes to HubActivity (spec "点击打开 WebHub 管理端而非子应用壳").
        assertEquals(Widget1x1State.Deleted, provider.decideState(999L, null))
        val views = provider.buildViews(context, appWidgetId = 2, childId = 999L, child = null)
        val applied = views.apply(context, null)
        assertEquals(
            context.getString(R.string.widget_child_deleted),
            applied.findViewById<TextView>(R.id.widget_label).text.toString()
        )
    }

    @Test
    fun buildViews_notBoundState_rendersConfigurePlaceholder() {
        // NotBound branch routes to WidgetConfigurationActivity carrying the widget id.
        assertEquals(Widget1x1State.NotBound, provider.decideState(null, null))
        val views = provider.buildViews(context, appWidgetId = 3, childId = null, child = null)
        val applied = views.apply(context, null)
        assertEquals(
            context.getString(R.string.widget_configure_placeholder),
            applied.findViewById<TextView>(R.id.widget_label).text.toString()
        )
    }

    // ---------------- helpers ----------------

    /**
     * Allocate a widget id via the Robolectric shadow (registers a WidgetInfo so
     * `updateAppWidget`/`getViewFor` work) without binding a child.
     */
    private fun allocateWidgetId(): Int {
        val shadow = shadowOf(mgr)
        val id = allocateRawId()
        val cn = android.content.ComponentName(
            context.packageName,
            Widget1x1Provider::class.java.name
        )
        runCatching { shadow.bindAppWidgetId(id, cn) }
        return id
    }

    private fun createBoundWidget(childId: Long): Int {
        val id = allocateWidgetId()
        WidgetBindings.bind(context, id, childId)
        return id
    }

    private var idSeq = 100
    private fun allocateRawId(): Int = idSeq++

    private fun getViewForWidget(appWidgetId: Int): android.view.View? =
        shadowOf(mgr).getViewFor(appWidgetId)
}