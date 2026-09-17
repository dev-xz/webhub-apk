package net.marscore.webhub.shell

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * OpenSpec improve-webapp-shell-ux task 2.1 / design D3.
 *
 * `android.graphics.Color.parseColor` is not available on a plain JVM, so we
 * run under Robolectric. Per AGENTS.md pitfall: targetSdk 36 > Robolectric's
 * supported ceiling, so pin `@Config(sdk = [33], manifest = Config.NONE)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ThemeColorReaderTest {

    @Test
    fun parseThemeColor_validSixDigitHex_returnsFullAlphaColor() {
        val parsed = ThemeColorReader.parseThemeColor("#1a73e8")
        assertNotNull(parsed)
        assertEquals(0xFF1A73E8.toInt(), parsed)
    }

    @Test
    fun parseThemeColor_white_returnsWhite() {
        val parsed = ThemeColorReader.parseThemeColor("#ffffff")
        assertNotNull(parsed)
        assertEquals(0xFFFFFFFF.toInt(), parsed)
        assertEquals(-1, parsed)
    }

    @Test
    fun parseThemeColor_black_returnsBlack() {
        val parsed = ThemeColorReader.parseThemeColor("#000000")
        assertNotNull(parsed)
        assertEquals(0xFF000000.toInt(), parsed)
        assertEquals(-16777216, parsed)
    }

    @Test
    fun parseThemeColor_threeDigitHex_supportedByParseColor() {
        val parsed = ThemeColorReader.parseThemeColor("#fff")
        assertNotNull(parsed)
        assertEquals(0xFFFFFFFF.toInt(), parsed)
    }

    @Test
    fun parseThemeColor_withoutLeadingHash_acceptedByParseColor() {
        val parsed = ThemeColorReader.parseThemeColor("1a73e8")
        assertNotNull(parsed)
        assertEquals(0xFF1A73E8.toInt(), parsed)
    }

    @Test
    fun parseThemeColor_null_returnsNull() {
        assertNull(ThemeColorReader.parseThemeColor(null))
    }

    @Test
    fun parseThemeColor_emptyString_returnsNull() {
        assertNull(ThemeColorReader.parseThemeColor(""))
    }

    @Test
    fun parseThemeColor_garbageString_returnsNull() {
        assertNull(ThemeColorReader.parseThemeColor("notacolor"))
    }

    @Test
    fun parseThemeColor_illegalHexChars_returnsNull() {
        assertNull(ThemeColorReader.parseThemeColor("#gggggg"))
    }

    @Test
    fun isLightColor_white_isLight() {
        assertTrue(ThemeColorReader.isLightColor(0xFFFFFFFF.toInt()))
    }

    @Test
    fun isLightColor_black_isDark() {
        assertFalse(ThemeColorReader.isLightColor(0xFF000000.toInt()))
    }

    @Test
    fun isLightColor_googleBlue_isDark() {
        // 0.299*0.102 + 0.587*0.451 + 0.114*0.91 ≈ 0.378 < 0.5
        assertFalse(ThemeColorReader.isLightColor(0xFF1A73E8.toInt()))
    }

    @Test
    fun isLightColor_orange_isLight() {
        // 1.0*0.299 + 0.596*0.587 + 0.0*0.114 ≈ 0.648 > 0.5
        assertTrue(ThemeColorReader.isLightColor(0xFFFF9800.toInt()))
    }

    @Test
    fun parseThemeColor_resultFeedsIsLightColor() {
        val black = ThemeColorReader.parseThemeColor("#000000")!!
        assertEquals(Color.parseColor("#000000"), black)
        assertFalse(ThemeColorReader.isLightColor(black))
    }
}