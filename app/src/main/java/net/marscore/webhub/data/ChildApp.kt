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
    val createdAt: Long = System.currentTimeMillis()
)