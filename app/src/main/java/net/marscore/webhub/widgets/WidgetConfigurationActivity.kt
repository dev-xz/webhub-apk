package net.marscore.webhub.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.marscore.webhub.R
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.data.ChildAppRepository
import net.marscore.webhub.icons.IconResolver

/**
 * Single configuration activity for all four widget sizes (design D4): 1×1 branches into a child
 * app picker; the matrix sizes (2×1 / 2×2 / 4×2) immediately `setResult(RESULT_OK)` and finish so
 * the host drops the widget and fires `onUpdate` (matrix auto-populates from the DB by recency —
 * no user interaction needed).
 *
 * 1×1 flow:
 *  1. Load all child apps via [ChildAppRepository.getAll] (sorted by createdAt, matching the hub).
 *  2. Empty list → show [R.string.widget_config_empty]; the user can only cancel (back) →
 *     `RESULT_CANCELED` (spec "未选择不落桌": nothing is dropped).
 *  3. Non-empty → [RecyclerView] picker. Selecting an item: persist
 *     [WidgetBindings.bind], `setResult(RESULT_OK)` with the widget id extra, directly push
 *     the 1×1 RemoteViews via [AppWidgetManager.updateAppWidget] (the framework's post-config
 *     onUpdate isn't reliable across launchers), finish.
 *  4. Back press → `RESULT_CANCELED` + finish (no widget dropped).
 *
 * Size detection: `AppWidgetProviderInfo.targetCellWidth/Height` on API 31+ (the manifest declares
 * the 1×1 cells); below 31 the heuristic is `minWidth < 60dp && minHeight < 60dp` (1×1 ≈ 40dp).
 * [sizeOverride] is a test seam so the size decision can be driven without Robolectric's
 * `ShadowAppWidgetManager` widget-info registration (design risks "Robolectric").
 */
class WidgetConfigurationActivity : AppCompatActivity() {

    private lateinit var titleView: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: TextView

    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    /** When non-null, bypasses [is1x1] — used by tests to force a branch without a widget info. */
    @VisibleForTesting
    internal var sizeOverride: (() -> Boolean)? = null

    /** When non-null, replaces the real [ChildAppRepository.getAll] call — used by tests. */
    @VisibleForTesting
    internal var childListProvider: (() -> List<ChildApp>)? = null

    /**
     * When non-null, replaces the production direct-push of the 1×1 RemoteViews in
     * [onChildSelected] — used by tests to avoid hitting the real [AppWidgetManager] (which
     * under Robolectric has no widget registered for the test id). When null (production), the
     * activity builds the views via [Widget1x1Provider.buildViews] and pushes them through
     * [AppWidgetManager.updateAppWidget] so the widget re-renders immediately without relying
     * on the framework's post-config `onUpdate` callback (many launchers skip it).
     */
    @VisibleForTesting
    internal var widgetRenderer: ((child: ChildApp) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        setContentView(R.layout.widget_config_list)
        titleView = findViewById(R.id.config_title)
        recycler = findViewById(R.id.config_recycler)
        emptyView = findViewById(R.id.config_empty)
        recycler.layoutManager = LinearLayoutManager(this)

        if (!is1x1()) {
            // Matrix sizes need no configuration — drop immediately with RESULT_OK so the host
            // places the widget and fires onUpdate (matrix auto-populates from the DB).
            setResult(RESULT_OK, resultIntent())
            finish()
            return
        }

        titleView.text = getString(R.string.widget_config_title_single)

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                childListProvider?.invoke() ?: ChildAppRepository(this@WidgetConfigurationActivity).getAll()
            }
            showList(apps)
        }
    }

    /**
     * Pure-ish size decision (depends only on [AppWidgetManager.getAppWidgetInfo]). Exposed for
     * unit coverage; [sizeOverride] short-circuits it in tests.
     *
     * On API 31+ a 1×1 widget declares `targetCellWidth == 1 && targetCellHeight == 1`. Below 31
     * those fields don't exist, so fall back to a `minWidth/minHeight < 60dp` heuristic (the 1×1
     * appwidget_1x1.xml declares 40dp; matrix sizes declare ≥ 100dp on at least one axis).
     */
    @VisibleForTesting
    internal fun is1x1(): Boolean {
        sizeOverride?.let { return it() }
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)
        if (info == null) {
            // No info available (e.g. not registered with the host) — default to the 1×1 picker so
            // the user can still bind something rather than silently dropping.
            return true
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.targetCellWidth == 1 && info.targetCellHeight == 1
        } else {
            info.minWidth < 60 && info.minHeight < 60
        }
    }

    private fun showList(apps: List<ChildApp>) {
        if (apps.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            recycler.visibility = View.GONE
            return
        }
        emptyView.visibility = View.GONE
        recycler.visibility = View.VISIBLE
        recycler.adapter = WidgetConfigAdapter(apps) { child -> onChildSelected(child) }
    }

    private fun onChildSelected(child: ChildApp) {
        WidgetBindings.bind(this, appWidgetId, child.id)
        setResult(RESULT_OK, resultIntent())
        // Directly push the updated RemoteViews so the widget re-renders immediately.
        // We can't rely on the framework's post-config onUpdate callback (many launchers skip it).
        val renderer = widgetRenderer
        if (renderer != null) {
            renderer(child)
        } else {
            val mgr = AppWidgetManager.getInstance(this)
            val views = Widget1x1Provider().buildViews(this, appWidgetId, child.id, child)
            mgr.updateAppWidget(appWidgetId, views)
        }
        finish()
    }

    private fun resultIntent(): Intent =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    @Deprecated("Delegated to the platform back-pressed dispatcher for forward compatibility.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Cancel = no widget dropped (spec "未选择不落桌").
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }

    // ---------------- picker adapter ----------------

    /**
     * Minimal single-row adapter for the child-app picker: icon (via [IconResolver]) + name.
     * Reuses the shared [R.layout.widget_config_item] layout; intentionally not shared with the
     * hub's `ChildAppAdapter` (different row layout / interaction model — no long-press, no recency).
     */
    private class WidgetConfigAdapter(
        private val items: List<ChildApp>,
        private val onClick: (ChildApp) -> Unit
    ) : RecyclerView.Adapter<WidgetConfigAdapter.VH>() {

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: ImageView = itemView.findViewById(R.id.config_item_icon)
            val name: TextView = itemView.findViewById(R.id.config_item_name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context)
                .inflate(R.layout.widget_config_item, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val child = items[position]
            val bmp = IconResolver.resolveIconBitmap(holder.itemView.context, child)
            holder.icon.setImageBitmap(bmp)
            holder.name.text = child.name
            holder.itemView.setOnClickListener { onClick(child) }
        }

        override fun getItemCount(): Int = items.size
    }
}