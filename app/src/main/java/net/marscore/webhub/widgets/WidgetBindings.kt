package net.marscore.webhub.widgets

import android.content.Context

/**
 * Persists the `appWidgetId → childId` binding for 1×1 home-screen widgets (design D4).
 *
 * 1×1 widgets are user-configured to point at exactly one child app; that choice is stored in
 * a private [SharedPreferences] table keyed by the widget id so that [Widget1x1Provider.onUpdate]
 * can re-render the right child across reboots / widget host restarts without re-prompting the
 * user. Matrix widgets don't use this — they always query the DB by recency.
 *
 * The `-1L` sentinel encodes "unbound" (no child app has id -1): [lookup] returns null for it so
 * callers can render the "tap to configure" placeholder (design risks "Rom 兼容" mitigation).
 */
object WidgetBindings {

    private const val PREFS = "widget_bindings"
    private const val UNBOUND = -1L

    /** Persist that [appWidgetId] is bound to [childId]. Overwrites any prior binding. */
    fun bind(context: Context, appWidgetId: Int, childId: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(key(appWidgetId), childId)
            .apply()
    }

    /**
     * @return the child id bound to [appWidgetId], or null when the widget has not been
     * configured yet (or was unbound).
     */
    fun lookup(context: Context, appWidgetId: Int): Long? {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(key(appWidgetId), UNBOUND)
        return if (v == UNBOUND) null else v
    }

    /** Remove the binding for [appWidgetId] (e.g. when the widget instance is deleted). */
    fun unbind(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(appWidgetId))
            .apply()
    }

    private fun key(appWidgetId: Int) = "widget_$appWidgetId"
}