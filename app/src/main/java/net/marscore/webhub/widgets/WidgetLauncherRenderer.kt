package net.marscore.webhub.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.data.ChildAppRepository
import net.marscore.webhub.shell.WebAppActivity

/**
 * Direct fixed-cell renderer for the matrix home-screen widgets (2×1 / 2×2 / 4×2).
 *
 * Replaces the previous `GridView` + `RemoteViewsService` + `RemoteViewsFactory` collection
 * pattern, which caused two device bugs:
 *  1. `setOnClickFillInIntent` merge semantics dropped the per-item child id → "应用不存在".
 *  2. Position-based stable ids in the Factory mismatched icons/labels after a recency reorder.
 *
 * Fixed-cell rendering eliminates both failure classes: each cell is a dedicated view in the
 * layout (`cell_root_N` / `cell_icon_N` / `cell_label_N`), populated directly here and launched
 * via a per-cell `setOnClickPendingIntent` (same mechanism as the working 1×1 widget). No
 * remote adapter, no fill-in intent merge, no Factory → the per-cell PendingIntent captures the
 * real child id at render time, and a recency change re-renders every cell from scratch on the
 * next `onUpdate`.
 *
 * Behavior contract (unchanged from the collection version):
 *  - Cells are filled from [ChildAppRepository.getAllByRecency] (DAO-ordered:
 *    `lastOpenedAt DESC, createdAt ASC`), truncated to [capacity] by [WidgetGridBuilder].
 *  - Each cell renders a launcher-style tile icon ([WidgetIcons.composeTile], 96px) + white
 *    10sp label below; tap → [WebAppActivity.createIntent] for that child.
 *  - Empty cells (fewer children than capacity) are set to [View.INVISIBLE] so the grid slots
 *    stay aligned but nothing is drawn (spec "子应用数不足格数时留空").
 */
object WidgetLauncherRenderer {

    /**
     * Re-renders the widget [appWidgetId] with the current DB state and pushes the RemoteViews
     * to the manager. Called from each matrix provider's `onUpdate`.
     */
    fun render(context: Context, mgr: AppWidgetManager, appWidgetId: Int, capacity: Int, layoutRes: Int) {
        val items = itemsFor(context, capacity)
        val views = buildViews(context, appWidgetId, capacity, layoutRes, items)
        mgr.updateAppWidget(appWidgetId, views)
    }

    /**
     * Loads the recency-ordered, capacity-truncated child list. Brief synchronous DB read
     * wrapped in `runBlocking` on [Dispatchers.IO] — matches the 1×1 provider's pattern and the
     * Android-documented widget data-loading allowance for brief synchronous IO in `onUpdate`.
     */
    @VisibleForTesting
    internal fun itemsFor(context: Context, capacity: Int): List<ChildApp> =
        runBlocking(Dispatchers.IO) {
            WidgetGridBuilder.buildItems(
                ChildAppRepository(context).getAllByRecency(),
                capacity
            )
        }

    /**
     * Pure builder for the matrix RemoteViews, factored out so the per-cell render decision is
     * testable without Robolectric's [AppWidgetManager] shadow (design risks "Robolectric").
     *
     * For each cell index `0..capacity-1`:
     *  - if a child exists at that index → VISIBLE, tile icon, label, per-cell click PendingIntent;
     *  - else → INVISIBLE (slot stays aligned, nothing drawn).
     *
     * Cell view ids are resolved by name (`cell_root_N` / `cell_icon_N` / `cell_label_N`) via
     * [Context.resources.getIdentifier] so this object stays decoupled from the generated `R`
     * class for the layout-specific ids (same approach [WidgetUpdater] used for `grid_view`).
     *
     * @param appWidgetId used to derive a unique per-cell PendingIntent requestCode
     *   (`appWidgetId * 16 + cellIndex`) so cells across widgets and within a widget don't share
     *   PendingIntents (FLAG_UPDATE_CURRENT would otherwise have them overwrite each other).
     */
    @VisibleForTesting
    internal fun buildViews(
        context: Context,
        appWidgetId: Int,
        capacity: Int,
        layoutRes: Int,
        items: List<ChildApp>
    ): RemoteViews {
        val views = RemoteViews(context.packageName, layoutRes)
        for (i in 0 until capacity) {
            val rootId = idByName(context, "cell_root_$i")
            val iconId = idByName(context, "cell_icon_$i")
            val labelId = idByName(context, "cell_label_$i")
            val child = items.getOrNull(i)
            if (child == null) {
                views.setViewVisibility(rootId, View.INVISIBLE)
            } else {
                views.setViewVisibility(rootId, View.VISIBLE)
                views.setImageViewBitmap(iconId, WidgetIcons.composeTile(context, child, 96))
                views.setTextViewText(labelId, child.name)
                views.setOnClickPendingIntent(rootId, pendingIntent(context, appWidgetId, i, child.id))
            }
        }
        return views
    }

    private fun idByName(context: Context, name: String): Int =
        context.resources.getIdentifier(name, "id", context.packageName)

    private fun pendingIntent(context: Context, appWidgetId: Int, cellIndex: Int, childId: Long): PendingIntent {
        // Unique requestCode per widget instance + cell so cells don't share PendingIntents
        // (FLAG_UPDATE_CURRENT + identical requestCode+intent would overwrite one cell's PI with
        // another's). minSdk 26 → FLAG_IMMUTABLE available; required on API 31+. FLAG_UPDATE_CURRENT
        // so a re-render refreshes the captured intent extras/data.
        val requestCode = appWidgetId * 16 + cellIndex
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(
            context,
            requestCode,
            WebAppActivity.createIntent(context, childId),
            flags
        )
    }
}
