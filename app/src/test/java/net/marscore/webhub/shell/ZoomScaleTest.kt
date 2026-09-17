package net.marscore.webhub.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomScaleTest {

    @Test fun hundredPercentAtDensityTwoPointFive() {
        // The bug report: density≈2.5 device, 100% should map to 250 (the system default ratio).
        assertEquals(250, effectiveInitialScale(100, 2.5f))
    }

    @Test fun seventyFivePercentAtDensityTwo() {
        assertEquals(150, effectiveInitialScale(75, 2.0f))
    }

    @Test fun hundredPercentAtDensityOne() {
        assertEquals(100, effectiveInitialScale(100, 1.0f))
    }

    @Test fun clampsBelowOne() {
        // 0% × any density → clamped to 1 (avoids 0 which WebView treats oddly).
        assertEquals(1, effectiveInitialScale(0, 2.5f))
    }

    @Test fun clampsAboveThousand() {
        // An absurd 500% × 2.5 = 1250 → clamped to 1000.
        assertEquals(1000, effectiveInitialScale(500, 2.5f))
    }

    @Test fun roundingTruncates() {
        // 33 × 2.5 = 82.5 → truncated to 82 (toInt() truncates toward zero).
        assertEquals(82, effectiveInitialScale(33, 2.5f))
    }
}