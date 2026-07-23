package com.koushikdutta.scratch

import com.koushikdutta.scratch.uri.URI
import org.junit.Test
import kotlin.test.*

class URITests {
    fun testBoth(uri: String): URI? {
        val s1 = try {
            URI(uri)
        }
        catch (throwable: Throwable) {
            null
        }
        val s2 = try {
            java.net.URI(uri)
        }
        catch (throwable: Throwable) {
            null
        }

        assertFalse((s1 == null) xor (s2 == null), "behavior mismatch")
        if (s1 == null || s2 == null)
            return null

        assertEquals(s1.host, s2.host)
        assertEquals(s1.port, s2.port)
        assertEquals(s1.scheme, s2.scheme)
        assertEquals(s1.authority, s2.authority)
        assertEquals(s1.query, s2.query)

        return s1
    }

    @Test
    fun testBehaviors() {
        testBoth("http://example.com")
        testBoth("http://example.com/path")
        testBoth("http://example.com:80/path")
        testBoth("http://foo:bar@example.com:80/path")
        testBoth("/pathOnly")
        testBoth("/pathOnly?")
        testBoth("/pathOnly?foo=bar")
        testBoth("/pathOnly?foo=bar&biz=baz")
        assertNotNull(testBoth("scheme:host:80"))

        // succeeds
        assertNotNull(testBoth("example.com:80"))
        // fails
        assertNull(testBoth("127.0.0.1:80"))
    }
}