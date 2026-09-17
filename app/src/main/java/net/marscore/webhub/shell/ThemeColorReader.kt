package net.marscore.webhub.shell

import android.graphics.Color

/**
 * Theme-color parsing and brightness heuristic for status-bar icon tinting.
 *
 * OpenSpec improve-webapp-shell-ux task 2.1 / design D3.
 *
 * `WebAppActivity` injects a one-shot JS snippet in `onPageFinished` that reads
 * `<meta name="theme-color">` and hands the `content` attribute back to Kotlin.
 * This object turns that raw string into a color Int and decides whether the
 * status-bar icons should flip to dark (light background) or stay light (dark
 * background), using the standard luminance heuristic.
 *
 * Pure helper — no Android widgets touched, so the logic is Robolectric-unit-
 * testable (mirrors the `TaskIconResolver` / `ZoomScale` pattern).
 */
internal object ThemeColorReader {

    /**
     * Parse a `<meta name="theme-color">` content string into a color Int.
     *
     * Accepts `#RGB`, `#ARGB`, `#RRGGBB`, `#AARRGGBB` (with or without a leading
     * `#`). Three-/four-digit forms are expanded to six-/eight-digit; six- and
     * three-digit forms get full alpha (`0xFF`).
     *
     * Implemented as a small hand-rolled hex parser rather than delegating to
     * `android.graphics.Color.parseColor`: Robolectric's `Color` shadow is
     * stricter than real Android (it rejects the no-`#` form and 3-digit
     * shorthand), which would make the unit tests non-representative. The
     * manual parser is deterministic across plain JVM, Robolectric, and real
     * devices, and accepts a superset of what `Color.parseColor` takes.
     *
     * @param metaContent the raw `content` attribute, may be null/empty.
     * @return the packed `0xAARRGGBB` Int (full-alpha for 6/3-digit forms), or
     *         null on null/empty/illegal values (e.g. "notacolor", "#gggggg").
     */
    fun parseThemeColor(metaContent: String?): Int? {
        if (metaContent.isNullOrEmpty()) return null
        val raw = if (metaContent.startsWith("#")) metaContent.substring(1) else metaContent
        // Expand 3-digit (#RGB) and 4-digit (#ARGB) shorthand to 6/8-digit.
        val expanded = when (raw.length) {
            3 -> raw.map { "$it$it" }.joinToString("")
            4 -> raw.map { "$it$it" }.joinToString("")
            6, 8 -> raw
            else -> return null
        }
        if (expanded.any { it !in "0123456789abcdefABCDEF" }) return null
        val alpha = if (expanded.length == 8) expanded.substring(0, 2).toInt(16) else 0xFF
        val rgb = if (expanded.length == 8) expanded.substring(2) else expanded
        val rgbInt = rgb.toInt(16)
        return (alpha shl 24) or rgbInt
    }

    /**
     * Brightness heuristic (design D3): `0.299R + 0.587G + 0.114B > 0.5` → the
     * color is "light", so the caller should request dark status-bar icons via
     * `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`.
     *
     * Alpha is ignored; R/G/B are normalized to `[0, 1]`.
     *
     * @param color a packed `0xAARRGGBB` Int.
     * @return true if the color is light enough that dark icons read better.
     */
    fun isLightColor(color: Int): Boolean {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b > 0.5f
    }
}