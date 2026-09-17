package net.marscore.webhub.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test fun validHttpsNormalizesWithTrailingSlash() {
        assertEquals("https://example.com/", UrlValidator.normalize("https://example.com"))
        assertEquals("https://example.com/", UrlValidator.normalize("https://example.com/"))
    }

    @Test fun validHttpAccepted() {
        assertEquals("http://example.com/", UrlValidator.normalize("http://example.com"))
    }

    @Test fun preservesPathAndQueryAndFragment() {
        assertEquals(
            "https://example.com/app/",
            UrlValidator.normalize("https://example.com/app")
        )
        assertEquals(
            "https://example.com/app/?q=1",
            UrlValidator.normalize("https://example.com/app?q=1")
        )
        assertEquals(
            "https://example.com/app/?q=1#frag",
            UrlValidator.normalize("https://example.com/app?q=1#frag")
        )
    }

    @Test fun missingSchemeRejected() {
        assertNull(UrlValidator.normalize("example.com"))
        assertNull(UrlValidator.normalize("://example.com"))
    }

    @Test fun nonHttpSchemeRejected() {
        assertNull(UrlValidator.normalize("ftp://example.com"))
        assertNull(UrlValidator.normalize("file:///etc/hosts"))
        assertNull(UrlValidator.normalize("intent://example.com"))
    }

    @Test fun badHostRejected() {
        assertNull(UrlValidator.normalize("https://"))
        assertNull(UrlValidator.normalize("https:///path"))
        assertNull(UrlValidator.normalize("https://exa mple.com"))
        // Single-label host (no dot, not IPv4) rejected per our explicit policy.
        assertNull(UrlValidator.normalize("https://localhost"))
    }

    @Test fun blankAndGarbageRejected() {
        assertNull(UrlValidator.normalize(null))
        assertNull(UrlValidator.normalize(""))
        assertNull(UrlValidator.normalize("   "))
        assertNull(UrlValidator.normalize("not a url at all"))
    }

    @Test fun explicitPortPreserved() {
        assertEquals("https://example.com:8443/", UrlValidator.normalize("https://example.com:8443"))
        // Default ports are stripped.
        assertEquals("https://example.com/", UrlValidator.normalize("https://example.com:443"))
        assertEquals("http://example.com/", UrlValidator.normalize("http://example.com:80"))
    }

    @Test fun ipv4Accepted() {
        assertNotNull(UrlValidator.normalize("http://192.168.1.10"))
        assertEquals("http://192.168.1.10/", UrlValidator.normalize("http://192.168.1.10"))
    }

    @Test fun subdomainAccepted() {
        assertEquals("https://app.example.com/", UrlValidator.normalize("https://app.example.com"))
    }

    @Test fun isInvalidHelper() {
        assertTrue(UrlValidator.isValid("https://example.com"))
        assertFalse(UrlValidator.isValid("example.com"))
        assertFalse(UrlValidator.isValid("ftp://example.com"))
    }
}