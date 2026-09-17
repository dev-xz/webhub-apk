package net.marscore.webhub.shell

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenSpec improve-webapp-shell-ux task 2.2 / design D2.
 *
 * Pure JVM string-structure assertions on [ThemeColorJs.jsSnippet] — no
 * Robolectric needed (no Android classes touched). The snippet is later
 * `evaluateJavascript`-ed by `WebAppActivity.onPageFinished`; we assert it
 * wires up the bridge name and the dark/light matchMedia selection logic.
 */
class ThemeColorJsTest {

    @Test
    fun snippet_isNonEmpty() {
        val js = ThemeColorJs.jsSnippet()
        assertNotNull(js)
        assertTrue(js.isNotEmpty())
    }

    @Test
    fun snippet_callsBridgeOnThemeColor() {
        val js = ThemeColorJs.jsSnippet()
        assertTrue(
            "snippet must hand the color back to __webHubThemeBridge.onThemeColor",
            js.contains("__webHubThemeBridge.onThemeColor")
        )
    }

    @Test
    fun snippet_usesMatchMediaAndPrefersColorScheme() {
        val js = ThemeColorJs.jsSnippet()
        assertTrue("snippet must use matchMedia", js.contains("matchMedia"))
        assertTrue(
            "snippet must select the dark/light variant via prefers-color-scheme",
            js.contains("prefers-color-scheme")
        )
    }

    @Test
    fun snippet_isSelfGuardedWithTryCatch() {
        val js = ThemeColorJs.jsSnippet()
        // Self-guard: a JS error must never break page loading — the catch
        // path still delivers null to the bridge so the status bar falls back
        // to the app theme color (design D4).
        assertTrue("snippet must wrap the read in try/catch", js.contains("try"))
        assertTrue("snippet must catch and hand null to the bridge", js.contains("catch"))
    }

    @Test
    fun snippet_selectsDarkVariantWhenDarkSchemeActive() {
        val js = ThemeColorJs.jsSnippet()
        assertTrue(
            "snippet must check darkMq.matches before picking the dark variant",
            js.contains("darkMq.matches")
        )
    }

    @Test
    fun snippet_fallsBackToNoMediaVariant() {
        val js = ThemeColorJs.jsSnippet()
        assertTrue(
            "snippet must fall back to the no-media variant",
            js.contains("noMedia")
        )
    }

    @Test
    fun bridgeName_isTheExpectedToken() {
        assertTrue(ThemeColorJs.bridgeName == "__webHubThemeBridge")
    }
}