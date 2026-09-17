package net.marscore.webhub.shell

import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenSpec improve-webapp-shell-ux tasks 2.3 / 2.4 / design D3, D4.
 *
 * Pure JVM tests of the flag-arithmetic and the snapshot/restore data flow —
 * no `Window` touched, so no Robolectric needed. `View.SYSTEM_UI_FLAG_*` are
 * compile-time `static final int` constants on `android.view.View`, available
 * on a plain JVM via the stubbed `android.jar` that the unit-test runtime
 * uses (`testOptions.unitTests.isReturnDefaultValues = true`).
 */
class ThemeColorApplierTest {

    // ---- computeSystemUiVisibility ----

    @Test
    fun compute_isLight_addsLightStatusBarFlagPreservingOthers() {
        val other = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        val result = ThemeColorApplier.computeSystemUiVisibility(other, isLight = true)
        assertTrue(
            "LIGHT_STATUS_BAR must be set when light",
            (result and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0
        )
        // Other flags preserved.
        assertEquals(other or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, result)
    }

    @Test
    fun compute_isDark_removesLightStatusBarFlagPreservingOthers() {
        val withLight = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val result = ThemeColorApplier.computeSystemUiVisibility(withLight, isLight = false)
        assertFalse(
            "LIGHT_STATUS_BAR must be cleared when dark",
            (result and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0
        )
        // The non-LIGHT_STATUS_BAR flags survive.
        assertEquals(View.SYSTEM_UI_FLAG_LAYOUT_STABLE, result)
    }

    @Test
    fun compute_isDark_whenFlagAlreadyAbsent_leavesFlagsUnchanged() {
        val flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        val result = ThemeColorApplier.computeSystemUiVisibility(flags, isLight = false)
        assertEquals(flags, result)
    }

    @Test
    fun compute_isLight_whenFlagAlreadySet_isIdempotent() {
        val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        val result = ThemeColorApplier.computeSystemUiVisibility(flags, isLight = true)
        assertEquals(flags, result)
    }

    @Test
    fun compute_zeroFlags_isLight_addsOnlyLightStatusBar() {
        val result = ThemeColorApplier.computeSystemUiVisibility(0, isLight = true)
        assertEquals(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, result)
    }

    // ---- isLightStatusBarSet ----

    @Test
    fun isLightStatusBarSet_detectsTheBit() {
        assertTrue(ThemeColorApplier.isLightStatusBarSet(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR))
        assertFalse(ThemeColorApplier.isLightStatusBarSet(0))
        assertFalse(
            ThemeColorApplier.isLightStatusBarSet(View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        )
    }

    // ---- snapshot / restore (data flow, no Window) ----

    @Test
    fun snapshot_capturesColorAndLightBit() {
        val state = ThemeColorApplier.snapshot(
            themeColor = 0xFF1A73E8.toInt(),
            currentFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,
        )
        assertEquals(0xFF1A73E8.toInt(), state.color)
        assertTrue(state.lightStatusBar)
    }

    @Test
    fun snapshot_nullThemeColor_recordsNullColorButStillCapturesLightBit() {
        // D4: when the page has no valid theme-color, we record color=null so
        // restore won't touch statusBarColor, but we still remember the
        // LIGHT_STATUS_BAR bit so restore can re-apply it.
        val state = ThemeColorApplier.snapshot(
            themeColor = null,
            currentFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR,
        )
        assertNull(state.color)
        assertTrue(state.lightStatusBar)
    }

    @Test
    fun snapshot_darkThemeColor_recordsLightBitFalse() {
        val state = ThemeColorApplier.snapshot(
            themeColor = 0xFF000000.toInt(),
            currentFlags = 0, // LIGHT_STATUS_BAR not set
        )
        assertEquals(0xFF000000.toInt(), state.color)
        assertFalse(state.lightStatusBar)
    }
}