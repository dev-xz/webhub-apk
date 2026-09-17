package net.marscore.webhub.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetIconsTest {

    @Test fun allHasAtLeastEightPresets() {
        assertTrue(PresetIcons.all().size >= 8)
    }

    @Test fun pickForIdIsDeterministic() {
        // Same id always yields the same preset.
        val a = PresetIcons.pickForId(42L)
        val b = PresetIcons.pickForId(42L)
        assertEquals(a.key, b.key)
        assertEquals(a.resId, b.resId)
    }

    @Test fun pickForIdDistributesAcrossCatalog() {
        val seenKeys = (0L until PresetIcons.size.toLong() * 3L)
            .map { PresetIcons.pickForId(it).key }
            .toSet()
        // Over a sweep covering several full cycles, every preset should appear at least once.
        assertEquals(PresetIcons.all().map { it.key }.toSet(), seenKeys)
    }

    @Test fun pickForIdNegativeIdStable() {
        // Negative ids (shouldn't happen in prod, but the mod math must not crash) still resolve
        // to a valid index and deterministically.
        val a = PresetIcons.pickForId(-7L)
        val b = PresetIcons.pickForId(-7L)
        assertEquals(a.key, b.key)
        assertTrue(PresetIcons.all().contains(a))
    }

    @Test fun resForKeyResolvesKnownAndRejectsUnknown() {
        val first = PresetIcons.all().first()
        assertEquals(first.resId, PresetIcons.resForKey(first.key))
        assertEquals(0, PresetIcons.resForKey("nonexistent_key"))
    }

    @Test fun everyPresetHasUniqueKey() {
        val keys = PresetIcons.all().map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}