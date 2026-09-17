package net.marscore.webhub.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import net.marscore.webhub.R

/**
 * Base [AppWidgetProvider] for the matrix-style home-screen widgets (2×1 / 2×2 / 4×2).
 *
 * Each subclass declares a fixed [capacity] (2 / 4 / 8 cells) and the matching fixed-cell
 * RemoteViews layout [gridLayoutRes]. `onUpdate` delegates to [WidgetLauncherRenderer.render],
 * which directly populates each cell (icon + label + per-cell click PendingIntent) from the
 * recency-ordered DB list and hides empty cells.
 *
 * This is the **direct fixed-cell render** path (same mechanism as the 1×1 widget), which
 * replaces the previous `GridView` + `RemoteViewsService` + `RemoteViewsFactory` collection
 * pattern. The collection path caused two device bugs:
 *  1. `setOnClickFillInIntent` merge semantics dropped the per-item child id → "应用不存在".
 *  2. Position-based stable ids in the Factory mismatched icons/labels after a recency reorder.
 *
 * Fixed cells eliminate both: each cell's PendingIntent captures the real child id at render
 * time, and a recency change re-renders every cell from scratch on the next `onUpdate`
 * (triggered by [WidgetUpdater] via an APPWIDGET_UPDATE broadcast, same as the 1×1 path).
 */
abstract class WebHubGridWidgetProvider : AppWidgetProvider() {

    /** Fixed cell count for this widget size (2 / 4 / 8). */
    abstract val capacity: Int

    /** The fixed-cell RemoteViews layout resource for this size. */
    protected abstract val gridLayoutRes: Int

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (id in appWidgetIds) {
            WidgetLauncherRenderer.render(context, appWidgetManager, id, capacity, gridLayoutRes)
        }
    }
}

/** 2×1 widget — 2 cells (1 row × 2 cols). */
class Widget2x1Provider : WebHubGridWidgetProvider() {
    override val capacity = 2
    override val gridLayoutRes = R.layout.widget_launcher_2
}

/** 2×2 widget — 4 cells (2 rows × 2 cols). */
class Widget2x2Provider : WebHubGridWidgetProvider() {
    override val capacity = 4
    override val gridLayoutRes = R.layout.widget_launcher_4
}

/** 4×2 widget — 8 cells (2 rows × 4 cols). */
class Widget4x2Provider : WebHubGridWidgetProvider() {
    override val capacity = 8
    override val gridLayoutRes = R.layout.widget_launcher_8
}
