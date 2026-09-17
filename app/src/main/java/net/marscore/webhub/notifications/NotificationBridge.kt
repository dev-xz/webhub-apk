package net.marscore.webhub.notifications

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.webkit.JavascriptInterface
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.shell.TaskIconResolver
import net.marscore.webhub.shell.WebAppActivity

/**
 * JS bridge exposed to the page as `__webHubNotifBridge` (task 6.1). Routes `window.Notification`
 * calls to system notifications on the child's own channel (`site_<childId>`).
 *
 * Carries the child identity so notifications are tagged to the right child even when multiple
 * shells are alive. The bridge is registered exactly once, in `WebAppActivity.configureWebView`,
 * **not** in onStart — that fixes the reference project's duplicate-registration hazard.
 *
 * Permission handling (tasks 6.5 + requestPermission): `show()` silently no-ops when
 * POST_NOTIFICATIONS is not granted (the system would drop the notification anyway; we skip
 * building it to avoid noisy logs). `requestPermission()` triggers the runtime permission request
 * on API 33+; the page always sees `permission = 'granted'` regardless, so web flows never stall.
 */
class NotificationBridge(
    private val context: Context,
    private val child: ChildApp
) {

    @JavascriptInterface
    fun show(title: String, body: String, tag: String, iconUrl: String) {
        // Task 6.5: silently drop when the app can't post notifications.
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        // Safety net: make sure the child's channel exists before posting (task 6.2).
        ChildNotificationChannels.ensureChannel(context, child)

        val notificationId = NotificationIds.forTag(child.id, tag)
        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            WebAppActivity.createIntent(context, child.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(context, ChildNotificationChannels.channelIdFor(child.id))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title.ifEmpty { child.name })
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)

        // notification-child-icon task 1.1: show the child app's icon as the notification's large
        // icon. Resolution reuses TaskIconResolver (same source as the hub list + recents card —
        // design D1), so a favicon/upload child shows its favicon and a preset child shows its
        // preset drawable. Best-effort (design D2): show() runs on the JS-bridge binder thread,
        // a one-shot small-icon disk decode is acceptable, and ANY failure falls back to "no large
        // icon" — never block the send. Small icon stays untouched (design D3: status-bar small
        // icons are forced alpha silhouettes and can't carry per-child color bitmaps).
        try {
            TaskIconResolver.resolve(context, child)?.let { icon ->
                builder.setLargeIcon(icon)
            }
        } catch (_: Throwable) {
            // Decode/render failure → post without a large icon (identical to pre-change behavior).
        }

        try {
            // NotificationManagerCompat handles the API-26 channel gate; on TIRAMISU+ it throws
            // SecurityException if POST_NOTIFICATIONS is not granted — caught below (task 6.5).
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the post — drop silently (task 6.5).
        }
    }

    @JavascriptInterface
    fun requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Run on the UI thread: permission requests must originate from an activity context.
            val activity = context as? android.app.Activity ?: return
            activity.runOnUiThread {
                activity.requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQ_POST_NOTIFICATIONS
                )
            }
        }
    }

    @JavascriptInterface
    fun permissionGranted(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    companion object {
        const val REQ_POST_NOTIFICATIONS = 0xA2
    }
}