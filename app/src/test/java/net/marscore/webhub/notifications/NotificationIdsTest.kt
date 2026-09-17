package net.marscore.webhub.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationIdsTest {

    @Test fun sameChildAndTagIsStable() {
        val a = NotificationIds.forTag(42L, "msg-1")
        val b = NotificationIds.forTag(42L, "msg-1")
        assertEquals(a, b)
    }

    @Test fun differentTagsOnSameChildDoNotCollide() {
        // Not a strict guarantee for all inputs, but for typical short tags the ids differ.
        val a = NotificationIds.forTag(42L, "msg-1")
        val b = NotificationIds.forTag(42L, "msg-2")
        assertNotEquals(a, b)
    }

    @Test fun sameTagAcrossDifferentChildrenDoesNotCollide() {
        val a = NotificationIds.forTag(1L, "shared-tag")
        val b = NotificationIds.forTag(2L, "shared-tag")
        assertNotEquals(a, b)
    }

    @Test fun idIsAlwaysPositiveAndNonZero() {
        // Sweep a range of (childId, tag) combos — none may produce 0 or a negative id.
        for (childId in 0L..500L) {
            for (tag in listOf("", "a", "n_123", "x", "longer-tag-string")) {
                val id = NotificationIds.forTag(childId, tag)
                assertTrue("id must be > 0 for ($childId, $tag), got $id", id > 0)
            }
        }
    }

    @Test fun highBitChildIdsFoldedIntoLowBits() {
        // A childId that uses the high 32 bits must still produce a stable, non-colliding id
        // distinct from the low-32-only equivalent.
        val a = NotificationIds.forTag(0x1_0000_0000L, "tag")
        val b = NotificationIds.forTag(0L, "tag")
        assertNotEquals(a, b)
        assertEquals(a, NotificationIds.forTag(0x1_0000_0000L, "tag"))
    }
}