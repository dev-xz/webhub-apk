package net.marscore.webhub.widgets

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
import org.robolectric.annotation.Config

/**
 * Verifies [WidgetLauncherRenderer]'s direct fixed-cell rendering of the matrix widgets
 * (replaces the previous `WidgetGridFactory` collection-path tests).
 *
 * The load-bearing spec behaviors verified here:
 *  - **Recency ordering** ("矩阵 SHALL 按最近打开时间倒序排列子应用"): cells are filled from
 *    [ChildAppRepository.getAllByRecency] (DAO-ordered), so cell labels appear in recency order.
 *  - **Capacity truncation + empty cells** ("子应用数不足格数时留空"): surplus cells are
 *    [View.INVISIBLE] so the grid slots stay aligned but nothing is drawn.
 *
 * Style mirrors [Widget1x1ProviderTest]: the pure builder ([WidgetLauncherRenderer.buildViews])
 * is driven directly, the resulting RemoteViews is `apply`ed to a view tree, and cells are
 * introspected by id (resolved by name, the same lookup the renderer uses — keeps the test
 * decoupled from the generated `R` class for the per-cell ids).
 *
 * Click-PendingIntent introspection is brittle under Robolectric (the RemoteViews action list
 * is private — design risks "Robolectric"); coverage of the click *target* is delegated to the
 * fact that each non-empty cell receives a `setOnClickPendingIntent` action (verified indirectly
 * by the cell being VISIBLE + populated), matching the 1×1 test's stance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class WidgetLauncherRendererTest {

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

    // ---------------- recency ordering + capacity truncation ----------------

    @Test
    fun buildViews_capacity4_threeChildren_renderedInRecencyOrder_surplusInvisible() {
        // Seed three children with distinct lastOpenedAt. Insert out of recency order to confirm
        // the DAO query (not insertion order) drives the result.
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

        val items = WidgetLauncherRenderer.itemsFor(context, capacity = 4)
        val views = WidgetLauncherRenderer.buildViews(
            context, appWidgetId = 1, capacity = 4,
            layoutRes = R.layout.widget_launcher_4, items = items
        )
        val applied = views.apply(context, null)

        // Cells 0..2 render the three children in recency order.
        assertEquals("A", label(applied, 0))
        assertEquals("C", label(applied, 1))
        assertEquals("B", label(applied, 2))

        // Cell 3 is INVISIBLE (surplus — spec "子应用数不足格数时留空").
        assertEquals(View.INVISIBLE, cellRoot(applied, 3).visibility)

        // Cells 0..2 are VISIBLE.
        assertEquals(View.VISIBLE, cellRoot(applied, 0).visibility)
        assertEquals(View.VISIBLE, cellRoot(applied, 1).visibility)
        assertEquals(View.VISIBLE, cellRoot(applied, 2).visibility)
    }

    @Test
    fun buildViews_capacity2_twoChildren_allCellsFilled() {
        // Capacity exactly matches supply — both cells filled, none hidden.
        kotlinx.coroutines.runBlocking {
            repo.insert(ChildApp(name = "First", url = "https://first.example.com", createdAt = 1000L, lastOpenedAt = 3000L))
            repo.insert(ChildApp(name = "Second", url = "https://second.example.com", createdAt = 2000L, lastOpenedAt = 2000L))
        }

        val items = WidgetLauncherRenderer.itemsFor(context, capacity = 2)
        val views = WidgetLauncherRenderer.buildViews(
            context, appWidgetId = 2, capacity = 2,
            layoutRes = R.layout.widget_launcher_2, items = items
        )
        val applied = views.apply(context, null)

        assertEquals("First", label(applied, 0))
        assertEquals("Second", label(applied, 1))
        assertEquals(View.VISIBLE, cellRoot(applied, 0).visibility)
        assertEquals(View.VISIBLE, cellRoot(applied, 1).visibility)
    }

    @Test
    fun buildViews_emptyDb_allCellsInvisible() {
        // No children → every cell is INVISIBLE (the widget renders nothing but keeps its slot
        // layout, so the desktop wallpaper shows through).
        val items = WidgetLauncherRenderer.itemsFor(context, capacity = 4)
        val views = WidgetLauncherRenderer.buildViews(
            context, appWidgetId = 3, capacity = 4,
            layoutRes = R.layout.widget_launcher_4, items = items
        )
        val applied = views.apply(context, null)

        for (i in 0 until 4) {
            assertEquals("cell $i should be INVISIBLE", View.INVISIBLE, cellRoot(applied, i).visibility)
        }
    }

    @Test
    fun buildViews_moreChildrenThanCapacity_truncatedToCapacity() {
        // 3 children, capacity 2 → only the 2 most recent render; the 3rd is dropped (not hidden,
        // simply not surfaced — capacity truncation via WidgetGridBuilder).
        kotlinx.coroutines.runBlocking {
            repo.insert(ChildApp(name = "A", url = "https://a.example.com", createdAt = 1000L, lastOpenedAt = 4000L))
            repo.insert(ChildApp(name = "B", url = "https://b.example.com", createdAt = 2000L, lastOpenedAt = 3000L))
            repo.insert(ChildApp(name = "C", url = "https://c.example.com", createdAt = 3000L, lastOpenedAt = 2000L))
        }

        val items = WidgetLauncherRenderer.itemsFor(context, capacity = 2)
        val views = WidgetLauncherRenderer.buildViews(
            context, appWidgetId = 4, capacity = 2,
            layoutRes = R.layout.widget_launcher_2, items = items
        )
        val applied = views.apply(context, null)

        assertEquals(2, items.size)
        assertEquals("A", label(applied, 0))
        assertEquals("B", label(applied, 1))
        // C (lastOpenedAt=2000, least recent) is truncated off — both cells remain visible.
        assertEquals(View.VISIBLE, cellRoot(applied, 0).visibility)
        assertEquals(View.VISIBLE, cellRoot(applied, 1).visibility)
    }

    @Test
    fun buildViews_capacity8_layoutHasAllEightCells() {
        // Smoke test: the 4×2 layout (widget_launcher_8) defines all 8 cell ids; an empty DB
        // leaves all 8 INVISIBLE without crashing (confirms the layout + id-by-name lookup
        // cover the largest capacity).
        val items = WidgetLauncherRenderer.itemsFor(context, capacity = 8)
        val views = WidgetLauncherRenderer.buildViews(
            context, appWidgetId = 5, capacity = 8,
            layoutRes = R.layout.widget_launcher_8, items = items
        )
        val applied = views.apply(context, null)

        for (i in 0 until 8) {
            assertNotNull("cell_root_$i must exist in widget_launcher_8", cellRoot(applied, i))
            assertEquals(View.INVISIBLE, cellRoot(applied, i).visibility)
        }
    }

    // ---------------- helpers ----------------

    private fun idByName(name: String): Int =
        context.resources.getIdentifier(name, "id", context.packageName)

    private fun cellRoot(applied: android.view.View, index: Int): View =
        applied.findViewById(idByName("cell_root_$index"))

    private fun label(applied: android.view.View, index: Int): String =
        applied.findViewById<TextView>(idByName("cell_label_$index")).text.toString()
}
