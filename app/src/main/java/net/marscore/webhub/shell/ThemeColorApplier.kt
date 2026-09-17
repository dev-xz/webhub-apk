package net.marscore.webhub.shell

import android.view.View
import android.view.Window

/**
 * Status-bar tinting + video-fullscreen color save/restore helper.
 *
 * OpenSpec improve-webapp-shell-ux tasks 2.3 / 2.4 / design D3, D4.
 *
 * The Android-touching parts (`Window`) are kept thin; the flag-arithmetic
 * lives in pure functions that are unit-testable on a plain JVM (mirrors the
 * `TaskIconResolver` / `ThemeColorReader` pattern).
 *
 * Behavior:
 *  - [computeSystemUiVisibility] adds `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` when
 *    the theme color is light (so status-bar icons turn dark), and removes it
 *    when dark (icons stay light). Other flags are preserved.
 *  - [snapshot] / [BarState] capture the current `statusBarColor` and the
 *    `LIGHT_STATUS_BAR` bit so video fullscreen can clear them and restore
 *    them on exit (design D4: theme-color 缺失/非法时不改 statusBarColor, so the
 *    snapshot keeps the "no theme color applied" state as `color = null`).
 *  - [restore] writes the saved color back and re-applies the saved
 *    `LIGHT_STATUS_BAR` bit. No-op when [BarState] is null (no snapshot taken).
 */
internal object ThemeColorApplier {

    /**
     * Pure function: given the current `systemUiVisibility` flags and whether
     * the active theme color is light, return the new flag set with
     * `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR` added (light) or removed (dark),
     * preserving every other flag.
     */
    fun computeSystemUiVisibility(currentFlags: Int, isLight: Boolean): Int {
        return if (isLight) {
            currentFlags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            currentFlags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    /** Whether the `LIGHT_STATUS_BAR` bit is set in [flags]. */
    fun isLightStatusBarSet(flags: Int): Boolean =
        (flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0

    /**
     * Captured bar state — `color == null` means "no theme-color applied; do
     * not touch `statusBarColor` on restore" (design D4).
     */
    data class BarState(
        val color: Int?,
        val lightStatusBar: Boolean,
    )

    /**
     * Snapshot the current status-bar color and the `LIGHT_STATUS_BAR` bit.
     * Pass `themeColor = null` when the page declared no usable theme-color —
     * the snapshot then records `color = null` so [restore] leaves
     * `statusBarColor` untouched (keeps the app theme's value).
     */
    fun snapshot(themeColor: Int?, currentFlags: Int): BarState =
        BarState(color = themeColor, lightStatusBar = isLightStatusBarSet(currentFlags))

    /**
     * Apply a theme color to [window] now (the non-fullscreen path). When
     * [color] is null, do NOT touch `statusBarColor` (design D4) — but still
     * normalize the `LIGHT_STATUS_BAR` bit via [computeSystemUiVisibility] so
     * callers can pass `isLight = false` to clear a stale light flag.
     */
    fun applyStatusBarColor(window: Window, color: Int?, isLight: Boolean) {
        if (color != null) {
            window.statusBarColor = color
        }
        window.decorView.systemUiVisibility =
            computeSystemUiVisibility(window.decorView.systemUiVisibility, isLight)
    }

    /**
     * Restore a previously snapshotted [BarState] to [window]. No-op when
     * [state] is null (e.g. fullscreen entered before any page finished).
     * When `state.color == null`, only the `LIGHT_STATUS_BAR` bit is restored;
     * `statusBarColor` is left as the app theme's value (design D4).
     */
    fun restore(window: Window, state: BarState?) {
        if (state == null) return
        if (state.color != null) {
            window.statusBarColor = state.color
        }
        val current = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility =
            computeSystemUiVisibility(current, state.lightStatusBar)
    }
}