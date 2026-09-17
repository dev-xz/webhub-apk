package net.marscore.webhub.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import net.marscore.webhub.data.ChildApp

/**
 * Per-child-app notification channels (task 6.2).
 *
 * Channel id = `site_<childId>`, channel name = the child app's display name. Created once per
 * child (idempotent) and **never** deleted-then-recreated on launch — that would reset the user's
 * per-channel system settings (sound/ importance/ vibration), which the reference project did and
 * WebHub deliberately fixes. Calling [ensureChannel] again after a rename updates the channel name
 * in place (the platform supports renaming an existing channel).
 *
 * The Hub lane creates the channel when a child is created/edited and removes it when the child is
 * deleted. WebAppActivity also calls [ensureChannel] on launch as a cheap safety net.
 */
object ChildNotificationChannels {

    /** Channel id scheme: `site_<childId>`. */
    fun channelIdFor(childId: Long): String = "site_$childId"

    /**
     * Create or refresh the channel for [child]. Idempotent: re-calling with the same id (e.g. after
     * the user renames the child) updates the user-visible name without resetting their settings.
     */
    fun ensureChannel(context: Context, child: ChildApp) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            channelIdFor(child.id),
            child.name,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "来自 ${child.name} 的网页通知"
            enableVibration(true)
            enableLights(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            setBypassDnd(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** Remove the channel for [childId]. Used by the Hub lane when a child is deleted. */
    fun removeChannel(context: Context, childId: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ContextCompat.getSystemService(context, NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel(channelIdFor(childId))
    }
}