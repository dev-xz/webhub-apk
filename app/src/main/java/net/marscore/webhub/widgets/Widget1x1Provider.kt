package net.marscore.webhub.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import net.marscore.webhub.R
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.data.ChildAppRepository
import net.marscore.webhub.icons.IconResolver
import net.marscore.webhub.icons.PresetIcons
import net.marscore.webhub.shell.WebAppActivity
import net.marscore.webhub.ui.hub.HubActivity

/**
 * 1×1 home-screen widget provider — renders a single bound child app's icon and opens its
 * WebView shell on tap (spec "提供 1×1 单图标小组件").
 *
 * Resolution per [WidgetBindings.lookup] (design D7):
 *  - bound + child exists → icon + label, tap → [WebAppActivity.createIntent].
 *  - bound but child deleted → "已删除" placeholder, tap → [HubActivity] (spec "删除子应用后
 *    1×1 兜底"; MUST NOT auto-remove the widget instance).
 *  - not bound (ROM skipped the config flow, or widget dropped without configuration) →
 *    "点击配置" placeholder, tap → [WidgetConfigurationActivity] carrying the widget id so the
 *    user can re-enter the picker (design risks "Rom 兼容" mitigation).
 *
 * `onUpdate` is synchronous and short-lived; the single `getById` query is wrapped in
 * `runBlocking` on [Dispatchers.IO] (matches the matrix Factory's pattern and the
 * Android-documented widget data-loading allowance for brief synchronous IO).
 */
class Widget1x1Provider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) {
            updateOne(context, appWidgetManager, id)
        }
    }

    private fun updateOne(context: Context, mgr: AppWidgetManager, appWidgetId: Int) {
        val childId = WidgetBindings.lookup(context, appWidgetId)
        // Brief synchronous DB read (one row by PK); runBlocking on IO mirrors the matrix Factory.
        val child = if (childId != null) {
            runBlocking(Dispatchers.IO) { ChildAppRepository(context).getById(childId) }
        } else null
        val views = buildViews(context, appWidgetId, childId, child)
        mgr.updateAppWidget(appWidgetId, views)
    }

    /**
     * Pure builder for the 1×1 RemoteViews, factored out so the render decision is testable
     * without Robolectric's [AppWidgetManager] shadow (design risks "Robolectric"). Decides the
     * three render states via [decideState] and applies icon/label/click-intent accordingly.
     */
    @VisibleForTesting
    internal fun buildViews(
        context: Context,
        appWidgetId: Int,
        childId: Long?,
        child: ChildApp?
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_1x1)
        when (val state = decideState(childId, child)) {
            Widget1x1State.Bound -> {
                val c = child!!
                val bitmap = WidgetIcons.composeTile(context, c, 192)
                views.setImageViewBitmap(R.id.widget_icon, bitmap)
                views.setTextViewText(R.id.widget_label, c.name)
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    activityPendingIntent(context, WebAppActivity.createIntent(context, c.id))
                )
            }
            Widget1x1State.Deleted -> {
                views.setImageViewBitmap(
                    R.id.widget_icon,
                    WidgetIcons.composeTileFromBitmap(
                        IconResolver.renderDrawableToBitmap(
                            context,
                            PresetIcons.pickForId(childId ?: 0L).resId
                        ),
                        192
                    )
                )
                views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_child_deleted))
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    activityPendingIntent(context, Intent(context, HubActivity::class.java))
                )
            }
            Widget1x1State.NotBound -> {
                views.setImageViewBitmap(
                    R.id.widget_icon,
                    WidgetIcons.composeTileFromBitmap(
                        IconResolver.renderDrawableToBitmap(context, PresetIcons.pickForId(0L).resId),
                        192
                    )
                )
                views.setTextViewText(R.id.widget_label, context.getString(R.string.widget_configure_placeholder))
                val configIntent = Intent(context, WidgetConfigurationActivity::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                views.setOnClickPendingIntent(R.id.widget_root, activityPendingIntent(context, configIntent))
            }
        }
        return views
    }

    /**
     * Decides the 1×1 render state from the binding + DB lookup. Pure function (no Android
     * objects) so the spec's three branches can be unit-verified without standing up a widget.
     *
     *  - [Widget1x1State.Bound]: bound child id and the child row both exist.
     *  - [Widget1x1State.Deleted]: a child id is bound but no longer present in the DB.
     *  - [Widget1x1State.NotBound]: no binding recorded for this widget instance.
     */
    @VisibleForTesting
    internal fun decideState(childId: Long?, child: ChildApp?): Widget1x1State = when {
        childId != null && child != null -> Widget1x1State.Bound
        childId != null -> Widget1x1State.Deleted
        else -> Widget1x1State.NotBound
    }

    private fun activityPendingIntent(context: Context, intent: Intent): PendingIntent {
        // minSdk 26 → FLAG_IMMUTABLE is available; required on API 31+. FLAG_UPDATE_CURRENT so a
        // re-bind (reconfigure) refreshes the captured intent extras/data.
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return PendingIntent.getActivity(context, 0, intent, flags)
    }
}

/** The three render states of a 1×1 widget (see [Widget1x1Provider.decideState]). */
@VisibleForTesting
sealed class Widget1x1State {
    /** Bound to an existing child app — render its icon and launch its shell on tap. */
    object Bound : Widget1x1State()
    /** Bound to a child id that no longer exists — show the "已删除" placeholder. */
    object Deleted : Widget1x1State()
    /** No binding recorded — show the "点击配置" placeholder and re-open the config activity. */
    object NotBound : Widget1x1State()
}
