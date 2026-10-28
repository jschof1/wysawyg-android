package com.cragnet.wysawyg

import org.junit.Assert.*
import org.junit.Test

class CloudDestinationTest {
    @Test fun consentIsBoundToTheCanonicalProviderOrigin() {
        val first = CloudDestination.parse("https://EXAMPLE.com:443/v1?model=speech")
        assertEquals("https://example.com/", first.origin)
        assertEquals(first.origin, CloudDestination.parse("https://example.com/api/chat").origin)
        assertNotEquals(first.origin, CloudDestination.parse("https://other.example.com/v1").origin)
        assertNotEquals(first.origin, CloudDestination.parse("https://example.com:8443/v1").origin)
    }

    @Test fun releaseDestinationsRejectPlaintextCredentialsAndFragments() {
        listOf("http://example.com/v1", "file:///tmp/audio", "not a URL",
            "https://user:secret@example.com/v1", "https://example.com/v1#secret").forEach {
            assertTrue(it, runCatching { CloudDestination.parse(it) }.isFailure)
        }
    }

    @Test fun loopbackHttpRequiresAnExplicitDevelopmentOverride() {
        assertTrue(runCatching { CloudDestination.parse("http://127.0.0.1:1234/v1") }.isFailure)
        assertEquals("http://127.0.0.1:1234/",
            CloudDestination.parse("http://127.0.0.1:1234/v1", allowHttp = true).origin)
    }
}
