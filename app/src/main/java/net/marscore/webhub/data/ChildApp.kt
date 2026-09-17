package net.marscore.webhub.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A managed child web app inside WebHub.
 *
 * @param id          stable identifier (auto-generated); also names its icon file, WebView profile, notif channel.
 * @param name        display name (also used as launcher shortcut label & notification channel name).
 * @param url         target URL the shell loads.
 * @param iconSource  one of "favicon" | "upload" | "preset".
 * @param iconPath    absolute file path for favicon/upload icons; preset resource name for preset icons; null when unset.
 * @param uaMode      one of "default" | "mobile" | "tablet" | "desktop".
 * @param zoomPercent fixed initial zoom percent; 0 = not fixed (use WebView default).
 * @param ignoreSsl   when true, the shell proceeds on invalid SSL certs.
 * @param createdAt   creation epoch millis; used as the default sort order (ascending).
 * @param lastOpenedAt epoch millis of the most recent time the user opened this child app's
 *                    WebView shell; 0 means never opened. Used as the primary sort key for
 *                    home-screen widgets (most-recently-opened first; ties fall back to
 *                    [createdAt] ascending). Updated via [ChildAppRepository.touchLastOpened].
 * @param displayMode one of "system" | "fullscreen". "system" (default): status bar stays
 *                    visible, app theme-color tints the status bar, and `adjustResize` natively
 *                    avoids the IME. "fullscreen": immersive sticky flags + manual
 *                    `WindowInsetsCompat` IME bottom padding avoidance; no theme-color tinting.
 *                    See OpenSpec improve-webapp-shell-ux task 5.1 / design D1.
 */
@Entity(tableName = "child_app")
data class ChildApp(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String,
    val iconSource: String = "favicon",
    val iconPath: String? = null,
    val uaMode: String = "default",
    val zoomPercent: Int = 0,
    val ignoreSsl: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long = 0,
    val displayMode: String = "system"
)