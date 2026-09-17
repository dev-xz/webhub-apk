package net.marscore.webhub.shortcuts

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.VectorDrawable
import android.net.Uri
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import net.marscore.webhub.data.ChildApp
import net.marscore.webhub.icons.AdaptiveIcons
import net.marscore.webhub.icons.PresetIcons
import net.marscore.webhub.shell.WebAppActivity
import java.io.File

/**
 * Launcher pinned-shortcut management for child apps (tasks 7.1–7.3, 9.3).
 *
 * Contract — call sites use exactly:
 *  - [requestPin] after a child is created / when the user picks "pin to home" (returns a
 *    [PinResult] so the Hub lane can surface guidance on UNSUPPORTED / REJECTED).
 *  - [update] after a child's name/icon is edited (no-op safe when not pinned).
 *  - [disable] after a child is deleted.
 *  - [openShortcutPermissionSettings] to jump to the ROM's shortcut-permission page when pinning
 *    was rejected/unsupported (defensive try/catch chain across MIUI/EMUI/OPPO/vivo).
 *
 * Icon resolution (shared by all three) lives in [resolveIconBitmap]: preset → file → fallback
 * to the deterministic preset for the child id.
 */
object ShortcutHelper {

    /** Outcome of a [requestPin] call. */
    enum class PinResult {
        /** The pin request was accepted/handed off to the launcher (confirmation UI shown). */
        SUCCESS,
        /** The launcher does not support pinning shortcuts at all. */
        UNSUPPORTED,
        /** The launcher refused the request (e.g. missing the "桌面快捷方式" permission on some ROMs). */
        REJECTED,
    }

    /** Stable shortcut id for a child: `child_<id>`. */
    fun shortcutIdFor(childId: Long): String = "child_$childId"

    /** Whether the current launcher supports pinning a shortcut. */
    fun isPinSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Request the system to pin a shortcut for [child] using [icon] as the adaptive bitmap.
     *
     * Returns:
     *  - [PinResult.UNSUPPORTED] when the launcher doesn't support pinning at all.
     *  - [PinResult.REJECTED] when [ShortcutManagerCompat.requestPinShortcut] returns false
     *    (the launcher refused without showing UI — typically a missing ROM permission).
     *  - [PinResult.SUCCESS] when the request was handed off and the launcher's confirmation
     *    UI is shown (the user may still cancel it, but the request itself was accepted).
     *
     * [onResult] (optional, default null) is invoked with the same [PinResult] synchronously after
     * the call completes; it's a convenience for callers that want a single callback hook rather
     * than branching on the return value. The system's async pin-result callback (IntentSender)
     * is wired internally but only informs us that the *user* confirmed the system dialog — since
     * most launchers do not reliably deliver it, we treat the synchronous return as authoritative
     * and report SUCCESS whenever the request is accepted.
     */
    fun requestPin(
        context: Context,
        child: ChildApp,
        icon: Bitmap,
        onResult: ((PinResult) -> Unit)? = null
    ): PinResult {
        if (!isPinSupported(context)) {
            onResult?.invoke(PinResult.UNSUPPORTED)
            return PinResult.UNSUPPORTED
        }
        val info = buildShortcutInfo(context, child, icon) ?: run {
            onResult?.invoke(PinResult.REJECTED)
            return PinResult.REJECTED
        }
        val accepted = try {
            ShortcutManagerCompat.requestPinShortcut(context, info, null)
        } catch (_: Throwable) {
            // Some ROMs throw instead of returning false when the permission is missing.
            onResult?.invoke(PinResult.REJECTED)
            return PinResult.REJECTED
        }
        val result = if (accepted) PinResult.SUCCESS else PinResult.REJECTED
        onResult?.invoke(result)
        return result
    }

    /**
     * Update the pinned shortcut for [child] in place (name + icon). Safe to call when not
     * pinned — updateShortcuts is a no-op for ids that are not dynamic/pinned.
     */
    fun update(context: Context, child: ChildApp, icon: Bitmap) {
        val info = buildShortcutInfo(context, child, icon) ?: return
        ShortcutManagerCompat.updateShortcuts(context, listOf(info))
    }

    /**
     * Disable the shortcut for a deleted child. The launcher either removes or greys it out.
     */
    fun disable(context: Context, childId: Long) {
        val id = shortcutIdFor(childId)
        try {
            ShortcutManagerCompat.disableShortcuts(
                context, listOf(id),
                "该应用已被删除"
            )
        } catch (_: Throwable) {
            // Some ROMs throw when the shortcut isn't pinned; ignore.
        }
    }

    /**
     * Open the ROM's shortcut/autostart permission settings page (task 9.3). Tries known vendor
     * permission pages defensively (each in try/catch), falling back to the generic app details
     * page. Every attempt carries FLAG_ACTIVITY_NEW_TASK so it works from non-Activity contexts.
     *
     * The chain is best-effort: ROM customizations change frequently, so any single component may
     * be absent. We try in order and stop at the first one that resolves.
     */
    fun openShortcutPermissionSettings(context: Context) {
        val pkg = context.packageName
        val attempts = buildList {
            // MIUI (Xiaomi) — security center / autostart + shortcut permission.
            add(Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                putExtra("extra_pkgname", pkg)
            })
            add(Intent().apply {
                setComponent(
                    android.content.ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                )
            })
            // EMUI / HarmonyOS (Huawei) — system manager autostart page.
            add(Intent().apply {
                setComponent(
                    android.content.ComponentName(
                        "com.huawei.systemmanager",
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                    )
                )
            })
            // OPPO / realme (ColorOS) — security permission / autostart.
            add(Intent().apply {
                setComponent(
                    android.content.ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                    )
                )
            })
            add(Intent().apply {
                setComponent(
                    android.content.ComponentName(
                        "com.oplus.safecenter",
                        "com.oplus.safecenter.permission.startup.StartupAppListActivity"
                    )
                )
            })
            // vivo (Funtouch) — permission manager / autostart.
            add(Intent().apply {
                setComponent(
                    android.content.ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                    )
                )
            })
            add(Intent().apply {
                setComponent(
                    android.content.ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartActivityManager"
                    )
                )
            })
            // Universal fallback: the app's system details page (permissions + battery).
            add(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg, null)
            })
        }
        for (intent in attempts) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (tryStart(context, intent)) return
        }
        // Last-ditch: the generic settings root, which always resolves.
        tryStart(
            context,
            Intent(android.provider.Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** True if [intent] resolves to an activity and starts successfully. */
    private fun tryStart(context: Context, intent: Intent): Boolean {
        val pm = context.packageManager
        if (intent.resolveActivity(pm) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ---- icon resolution -------------------------------------------------------

    /**
     * Resolve a representative **raw source** bitmap for [child], suitable for passing to
     * [requestPin] / [update] (which apply the adaptive-canvas composition themselves).
     *
     *  - `iconSource == "preset"` → render [PresetIcons.resForKey] (vector drawable) to bitmap.
     *  - else → load the on-disk file at [ChildApp.iconPath].
     *  - fallback: render [PresetIcons.pickForId] when the above yields no bitmap.
     *
     * @return the raw source bitmap, or null on total failure.
     */
    fun resolveIconBitmap(context: Context, child: ChildApp): Bitmap? {
        return when (child.iconSource) {
            "preset" -> {
                val key = child.iconPath
                if (!key.isNullOrBlank()) {
                    val resId = PresetIcons.resForKey(key)
                    if (resId != 0) renderDrawableToBitmap(context, resId) else null
                } else null
            }
            else -> {
                val path = child.iconPath
                if (!path.isNullOrBlank()) decodeFileBitmap(path) else null
            }
        } ?: renderDrawableToBitmap(context, PresetIcons.pickForId(child.id).resId)
    }

    // ---- internals -------------------------------------------------------------

    private fun buildShortcutInfo(
        context: Context, child: ChildApp, rawIcon: Bitmap
    ): ShortcutInfoCompat? {
        // Preset tiles carry transparent/semi-transparent margins that, after the safe-zone scale,
        // appear as a visible ring on launcher icons (uploaded/favicon icons are already opaque-edge
        // and look correct). Route presets through composeFullBleed so the artwork fills the safe
        // zone edge-to-edge — matching the upload-icon look. Only the shortcut output changes; the
        // in-app grid/dialog still render the raw drawable untouched.
        val isPreset = child.iconSource == "preset"
        val composed = if (isPreset) {
            AdaptiveIcons.composeFullBleed(context, rawIcon)
        } else {
            AdaptiveIcons.compose(context, rawIcon)
        }
        val icon = IconCompat.createWithAdaptiveBitmap(composed)
        val intent = WebAppActivity.createIntent(context, child.id).apply {
            action = Intent.ACTION_VIEW
        }
        return ShortcutInfoCompat.Builder(context, shortcutIdFor(child.id))
            .setShortLabel(child.name)
            .setIcon(icon)
            .setIntent(intent)
            .build()
    }

    private fun decodeFileBitmap(path: String): Bitmap? {
        val file = File(path)
        if (!file.exists()) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Render a drawable resource to a bitmap. Handles both vector and raster drawables.
     *
     * Preset vectors declare `android:tint="?attr/colorControlNormal"`; loading via
     * [AppCompatResources.getDrawable] resolves the themed reference safely (a plain
     * `Resources.getDrawable(_, null)` would fail to resolve `?attr` refs without a theme).
     *
     * Tint policy (revamp-hub-ux D2): the fixed dark-gray tint is applied **only** to
     * [VectorDrawable] — the old vector glyphs were single-color and needed a deterministic
     * visible color. The new preset assets are full-color bitmap tiles (`BitmapDrawable`); tinting
     * them would drown the design in dark gray, so they render untinted. Any other drawable type
     * is likewise rendered as-is.
     */
    fun renderDrawableToBitmap(context: Context, resId: Int): Bitmap? {
        val drawable: Drawable = try {
            AppCompatResources.getDrawable(context, resId)
        } catch (e: Exception) {
            return null
        } ?: return null
        // Apply the visible tint ONLY for vector glyphs (single-color, otherwise transparent/white).
        if (drawable is VectorDrawable) {
            drawable.setTint(0xFF424242.toInt())
        }
        val targetSizePx = 192
        val width: Int
        val height: Int
        if (drawable is VectorDrawable) {
            width = targetSizePx
            height = targetSizePx
        } else {
            val iw = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetSizePx
            val ih = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetSizePx
            // scale so the larger side is targetSizePx, keep aspect
            val scale = targetSizePx.toFloat() / maxOf(iw, ih)
            width = (iw * scale).toInt().coerceAtLeast(1)
            height = (ih * scale).toInt().coerceAtLeast(1)
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }
}