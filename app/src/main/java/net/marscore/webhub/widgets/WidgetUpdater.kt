package net.marscore.webhub.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting

/**
 * Central event dispatcher that notifies [AppWidgetManager] when child-app data changes.
 *
 * Widget providers are short-lived (per-broadcast), so callers (HubActivity / WebAppActivity /
 * WidgetConfigurationActivity) invoke these methods after data mutations; the manager then
 * triggers each provider's `onUpdate`.
 *
 * Calls are debounced on a 300ms [Handler] to coalesce bursts (e.g. rapid open/close of child
 * apps). All public methods are safe to call from any thread.
 *
 * Provider classes ([Widget1x1Provider] / [Widget2x1Provider] / [Widget2x2Provider] /
 * [Widget4x2Provider]) are referenced by fully-qualified string class names so this file
 * compiles regardless of whether those classes (owned by other lanes) exist yet.
 *
 * All four providers now use the same refresh path: an APPWIDGET_UPDATE broadcast forces the
 * framework to re-invoke `onUpdate`, which re-renders the widget from the current DB state.
 * (The matrix widgets previously used `notifyAppWidgetViewDataChanged` against a `GridView`
 * collection, but that path was removed when the matrix widgets switched to direct fixed-cell
 * RemoteViews rendering — see [WidgetLauncherRenderer].)
 */
object WidgetUpdater {

    private const val DEBOUNCE_MS = 300L

    private const val PROVIDER_1x1 = "net.marscore.webhub.widgets.Widget1x1Provider"
    private const val PROVIDER_2x1 = "net.marscore.webhub.widgets.Widget2x1Provider"
    private const val PROVIDER_2x2 = "net.marscore.webhub.widgets.Widget2x2Provider"
    private const val PROVIDER_4x2 = "net.marscore.webhub.widgets.Widget4x2Provider"

    private val handler = Handler(Looper.getMainLooper())
    private val pending = mutableSetOf<String>()  // dedup keys for queued runnables

    @Volatile
    private var dispatchCount = 0

    /** Refresh all widgets (1×1 + matrix) — full re-render via APPWIDGET_UPDATE broadcast. */
    fun updateAllWidgets(context: Context) {
        schedule("updateAll:${context.packageName}") {
            val appCtx = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appCtx)
            notifyProviderUpdate(appCtx, mgr, PROVIDER_1x1)
            notifyProviderUpdate(appCtx, mgr, PROVIDER_2x1)
            notifyProviderUpdate(appCtx, mgr, PROVIDER_2x2)
            notifyProviderUpdate(appCtx, mgr, PROVIDER_4x2)
        }
    }

    /**
     * Refresh only matrix widgets. Used after `lastOpenedAt` changes (1×1 doesn't depend on
     * recency, only on its bound child which didn't change). Now uses the same APPWIDGET_UPDATE
     * broadcast path as [updateAllWidgets] — the matrix providers re-render their fixed cells
     * from the current recency-ordered DB list in `onUpdate`.
     */
    fun notifyAllWidgetsChanged(context: Context) {
        schedule("notifyChanged:${context.packageName}") {
            val appCtx = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appCtx)
            notifyProviderUpdate(appCtx, mgr, PROVIDER_2x1)
            notifyProviderUpdate(appCtx, mgr, PROVIDER_2x2)
            notifyProviderUpdate(appCtx, mgr, PROVIDER_4x2)
        }
    }

    /**
     * Refresh a single widget instance (used by WidgetConfigurationActivity after binding).
     *
     * No debounce — single widget, immediate. Runs on the main thread because
     * [AppWidgetManager] APIs are main-thread only. Sends an APPWIDGET_UPDATE broadcast to the
     * single widget id so its provider's `onUpdate` re-renders (works for both 1×1 and matrix
     * widgets; the matrix providers re-render their fixed cells from the DB).
     */
    fun updateSingleWidget(context: Context, appWidgetId: Int) {
        handler.post {
            val appCtx = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appCtx)
            // Query the installed widget info to get the provider class, then target it with an
            // APPWIDGET_UPDATE broadcast carrying just this widget id. Falls back to a no-op if
            // the widget isn't installed (info is null).
            val info = mgr.getAppWidgetInfo(appWidgetId)
            val cn = info?.provider
            if (cn != null) {
                val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
                    component = cn
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
                }
                appCtx.sendBroadcast(intent)
            }
        }
    }

    private fun notifyProviderUpdate(context: Context, mgr: AppWidgetManager, providerClassName: String) {
        val cn = ComponentName(context.packageName, providerClassName)
        val ids = mgr.getAppWidgetIds(cn)
        if (ids.isEmpty()) return
        // Send an APPWIDGET_UPDATE broadcast to the specific provider carrying the affected ids;
        // the framework then dispatches onUpdate, which re-renders each widget from the DB.
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE).apply {
            component = cn
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        }
        context.sendBroadcast(intent)
    }

    private fun schedule(key: String, block: () -> Unit) {
        synchronized(pending) {
            if (key in pending) return  // already queued — coalesce
            pending.add(key)
        }
        handler.postDelayed({
            synchronized(pending) { pending.remove(key) }
            block()
            dispatchCount++
        }, DEBOUNCE_MS)
    }

    @VisibleForTesting
    internal fun resetForTest() {
        synchronized(pending) { pending.clear() }
        handler.removeCallbacksAndMessages(null)
        dispatchCount = 0
    }

    @VisibleForTesting
    internal fun getDispatchCountForTest(): Int = dispatchCount
}