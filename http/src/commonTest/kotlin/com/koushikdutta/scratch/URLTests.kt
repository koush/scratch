package com.koushikdutta.scratch

import com.koushikdutta.scratch.collections.getFirst
import com.koushikdutta.scratch.uri.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class URLTests {
    @Test
    fun testDecodeEncode() {
        val test = ",<.>/?`~!@#\$%^&*()-_=+abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
        // tested vs browser
        val valid = mutableSetOf<String>()
        valid.add("%2C%3C.%3E%2F%3F%60~%21%40%23%24%25%5E%26%2A%28%29-_%3D%2BabcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890")
        valid.add("%2C%3C.%3E%2F%3F%60%7E%21%40%23%24%25%5E%26*%28%29-_%3D%2BabcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890")
        assertTrue(valid.contains(URLEncoder.encode(test)))
        assertEquals(test, URLDecoder.decode(URLEncoder.encode(test)))
    }

    @Test
    fun testURI() {
        val uri = URI("https://foo.com:34/poops?qu%20ery=44#hash")
        assertEquals("https", uri.scheme)
        assertEquals("hash", uri.fragment)
        assertEquals(uri.query, "qu ery=44")
    }

    @Test
    fun testDecodeQuery() {
        val query = URI("https://example.com/?foo=bar&test=hello").parseQuery()
        assertEquals(query.getFirst("foo"), "bar")
        assertEquals(query.getFirst("test"), "hello")

        val query2 = URI("https://example.com/?foo=bar&foo=hello").parseQuery()
        assertEquals(query2.get("foo")!![0], "bar")
        assertEquals(query2.get("foo")!![1], "hello")
    }
}