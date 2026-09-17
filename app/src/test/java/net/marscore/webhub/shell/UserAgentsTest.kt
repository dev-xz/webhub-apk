package net.marscore.webhub.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAgentsTest {

    @Test fun defaultModeYieldsNull() {
        assertNull(UserAgents.forMode("default"))
    }

    @Test fun unknownModeYieldsNull() {
        assertNull(UserAgents.forMode("nonsense"))
        assertNull(UserAgents.forMode(""))
    }

    @Test fun mobileContainsMobileToken() {
        val ua = UserAgents.forMode("mobile")
        assertEquals(UserAgents.mobile, ua)
        assertTrue("mobile UA must contain ' Mobile '", ua!!.contains(" Mobile "))
        assertTrue(ua.contains("Android"))
        assertTrue(ua.contains("Chrome/"))
        // D8: frozen Android platform segment.
        assertTrue("mobile UA must use the frozen 'Linux; Android 10; K' segment", ua.contains("Linux; Android 10; K"))
    }

    @Test fun tabletLacksMobileToken() {
        val ua = UserAgents.forMode("tablet")
        assertEquals(UserAgents.tablet, ua)
        assertFalse("tablet UA must NOT contain ' Mobile '", ua!!.contains(" Mobile "))
        // Tablet is the mobile UA with the Mobile token stripped, so it stays an Android Chrome UA.
        assertTrue(ua.contains("Android"))
        assertTrue(ua.contains("Linux; Android 10; K"))
    }

    @Test fun desktopIsWindowsChrome() {
        val ua = UserAgents.forMode("desktop")
        assertEquals(UserAgents.desktop, ua)
        assertTrue(ua!!.contains("Windows NT 10.0"))
        assertTrue(ua.contains("Win64; x64"))
        assertFalse(ua.contains("Android"))
        assertFalse(ua.contains(" Mobile "))
    }
}