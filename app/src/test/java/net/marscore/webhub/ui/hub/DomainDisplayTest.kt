package net.marscore.webhub.ui.hub

import org.junit.Assert.assertEquals
import org.junit.Test

class DomainDisplayTest {

    @Test fun returnsHostWithoutPath() {
        assertEquals("hub.example.net", DomainDisplay.of("https://hub.example.net/docs/page?q=1"))
    }

    @Test fun keepsNonDefaultPort() {
        assertEquals("hub.example.net:31406", DomainDisplay.of("https://hub.example.net:31406/docs/"))
    }

    @Test fun omitsDefaultHttpsPort() {
        assertEquals("hub.example.net", DomainDisplay.of("https://hub.example.net:443/docs/"))
    }

    @Test fun omitsDefaultHttpPort() {
        assertEquals("hub.example.net", DomainDisplay.of("http://hub.example.net:80/docs/"))
    }

    @Test fun returnsRawUrlWhenParsingFails() {
        val malformed = "https://bad host.example/path"
        assertEquals(malformed, DomainDisplay.of(malformed))
    }
}
