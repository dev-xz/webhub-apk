package net.marscore.webhub.icons

import androidx.annotation.DrawableRes
import net.marscore.webhub.R

/**
 * A bundled preset icon referenced by resource id (no on-disk file).
 *
 * @param key   stable string id, also stored as [net.marscore.webhub.data.ChildApp.iconPath]
 *              for preset icons.
 * @param resId the drawable resource to render (PNG bitmap asset as of revamp-hub-ux D1/D2).
 * @param label human-readable label for the picker UI.
 */
data class PresetIcon(
    val key: String,
    @DrawableRes val resId: Int,
    val label: String
)

/**
 * The bundled preset icon catalog and the deterministic assignment policy.
 *
 * Assignment is by `(childId % size)` index — stable for a given child and uniformly distributed
 * across the catalog. Per design.md this avoids icon drift on edits (no randomness).
 *
 * Catalog (revamp-hub-ux D1/D2): 9 bitmap tiles in row-major order from the 3×3 source sheet:
 * `mail, play, note, check, code, doc, calendar, clock, image`. Replaces the old 8-vector set;
 * no migration of stale keys (app unreleased — see design D2).
 */
object PresetIcons {

    private val presets: List<PresetIcon> = listOf(
        PresetIcon("mail", R.drawable.preset_mail, "邮件"),
        PresetIcon("play", R.drawable.preset_play, "视频"),
        PresetIcon("note", R.drawable.preset_note, "笔记"),
        PresetIcon("check", R.drawable.preset_check, "待办"),
        PresetIcon("code", R.drawable.preset_code, "代码"),
        PresetIcon("doc", R.drawable.preset_doc, "文档"),
        PresetIcon("calendar", R.drawable.preset_calendar, "日历"),
        PresetIcon("clock", R.drawable.preset_clock, "时钟"),
        PresetIcon("image", R.drawable.preset_image, "相册"),
    )

    /** The full catalog in stable order. */
    fun all(): List<PresetIcon> = presets

    /** The number of available presets. */
    val size: Int get() = presets.size

    /**
     * Resolve a preset's resource id by its [key] (e.g. stored on a ChildApp's iconPath).
     * Returns 0 when the key is unknown (0 is never a valid resource id, callers can guard).
     */
    fun resForKey(key: String): Int = presets.firstOrNull { it.key == key }?.resId ?: 0

    /**
     * Deterministically pick a preset for [childId]: `(childId % size)` as the index.
     * Stable for a given id and evenly distributed across the catalog.
     */
    fun pickForId(childId: Long): PresetIcon {
        val idx = (childId % presets.size).toInt()
        return presets[if (idx < 0) idx + presets.size else idx]
    }
}