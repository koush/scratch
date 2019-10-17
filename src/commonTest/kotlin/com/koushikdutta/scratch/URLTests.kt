package com.koushikdutta.scratch

import com.koushikdutta.scratch.uri.URI
import com.koushikdutta.scratch.uri.URLDecoder
import com.koushikdutta.scratch.uri.URLEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

class URLTests {
    @Test
    fun testDecodeEncode() {
        val test = ",<.>/?`~!@#\$%^&*()-_=+abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890"
        // tested vs browser
        assertEquals(URLEncoder.encode(test), "%2C%3C.%3E%2F%3F%60~%21%40%23%24%25%5E%26%2A%28%29-_%3D%2BabcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890")
        assertEquals(test, URLDecoder.decode(URLEncoder.encode(test)))
    }

    @Test
    fun testURI() {
        val uri = URI.create("https://foo.com:34/poops?qu%20ery=44#hash")
        assertEquals("https", uri.scheme)
        assertEquals("hash", uri.fragment)
        assertEquals(uri.query, "qu ery=44")
    }
}