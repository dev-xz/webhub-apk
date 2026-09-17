package net.marscore.webhub.shell

import net.marscore.webhub.data.ChildApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM unit tests for [JumpMatcher]. No Robolectric needed: [JumpMatcher] only uses
 * [java.net.URI] and the plain data class [ChildApp] (Room annotations are runtime-retained and
 * don't require Android runtime resolution).
 */
class JumpMatcherTest {

    private fun child(id: Long, url: String, createdAt: Long = id * 1000): ChildApp =
        ChildApp(id = id, name = "n$id", url = url, createdAt = createdAt)

    @Test fun originMatchWithExplicitPort() {
        val c = child(1, "https://code.marscore.net:31410/")
        val result = JumpMatcher.match("https://code.marscore.net:31410/?folder=/x", listOf(c))
        assertEquals(c, result)
    }

    @Test fun differentPortDoesNotMatch() {
        val c = child(1, "https://code.marscore.net/")
        val result = JumpMatcher.match("https://code.marscore.net:31410/some/path", listOf(c))
        assertNull(result)
    }

    @Test fun defaultPortNormalization() {
        val c = child(1, "https://example.com:443/foo")
        val result = JumpMatcher.match("https://example.com/foo", listOf(c))
        assertEquals(c, result)
    }

    @Test fun longestPathPrefixWins() {
        val a = child(1, "https://h/")
        val b = child(2, "https://h/admin/")
        val result = JumpMatcher.match("https://h/admin/users", listOf(a, b))
        assertEquals(b, result)
    }

    @Test fun pathSegmentBoundary() {
        val a = child(1, "https://h/admin/")
        val result = JumpMatcher.match("https://h/adminfoo", listOf(a))
        assertNull(result)
    }

    @Test fun tieBreakOldestCreatedAt() {
        // Older child has larger id to confirm createdAt is the first tie-break, not id.
        val older = child(10, "https://h/admin/", createdAt = 1000L)
        val newer = child(2, "https://h/admin/", createdAt = 2000L)
        val result = JumpMatcher.match("https://h/admin/users", listOf(newer, older))
        assertEquals(older, result)
    }

    @Test fun tieBreakSmallestIdWhenCreatedAtEqual() {
        val a = child(5, "https://h/admin/", createdAt = 1000L)
        val b = child(3, "https://h/admin/", createdAt = 1000L)
        val result = JumpMatcher.match("https://h/admin/users", listOf(a, b))
        assertEquals(b, result)
    }

    @Test fun invalidTargetUrlReturnsNull() {
        val c = child(1, "https://h/")
        val result = JumpMatcher.match("not a url", listOf(c))
        assertNull(result)
    }

    @Test fun invalidChildUrlIsDropped() {
        val a = child(1, "javascript:bad")
        val b = child(2, "https://h/")
        val result = JumpMatcher.match("https://h/some/deep/path", listOf(a, b))
        assertEquals(b, result)
    }

    @Test fun schemeCaseInsensitive() {
        val c = child(1, "https://example.com/foo")
        val result = JumpMatcher.match("HTTPS://Example.COM/Foo", listOf(c))
        assertEquals(c, result)
    }

    @Test fun rootPathChildMatchesAny() {
        val c = child(1, "https://h/")
        val result = JumpMatcher.match("https://h/anything/deep", listOf(c))
        assertEquals(c, result)
    }
}