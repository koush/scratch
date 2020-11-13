package com.koushikdutta.scratch;

import com.koushikdutta.scratch.collections.StringMultimap
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.toStringMultimap
import org.junit.Test
import java.io.File
import java.net.CookieManager
import java.net.HttpCookie
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SanityTests {
    @Test
    fun testBinary() {
        val i = 34
        assertEquals(Integer.toBinaryString(i), i.toString(2))
    }

    @Test
    fun testWeirdUri() {
        val uri = URI.create("/testbar")
        println(uri)
    }

    @Test
    fun testFileExt() {
        println(File("/tmp/bar", ".tmp"))
    }

    fun verifyCookies(cookies: List<HttpCookie>, vararg expected: Pair<String, String>) {
        for (expect in expected) {
            assertNotNull(cookies.find {
                it.name == expect.first && it.value == expect.second
            })
        }
    }

    @Test
    fun testCookieManager() {
        val manager = CookieManager(null, null)
        val uri = URI.create("http://example.com/")
        val headers = Headers()
        headers["Set-Cookie"] = "foo=bar"
        manager.put(uri, headers.toStringMultimap())

        verifyCookies(manager.cookieStore.get(uri), Pair("foo", "bar"))

        val uri2 = URI.create("http://example.com/path")
        val headers2 = Headers()
        headers2["Set-Cookie"] = "foo2=bar2; path=/path"
        manager.put(uri2, headers2.toStringMultimap())

        manager.cookieStore.get(uri2)
        verifyCookies(manager.cookieStore.get(uri2), Pair("foo", "bar"), Pair("foo2", "bar2"))

        val cookies = manager.cookieStore.get(uri2)

        assertEquals("/", cookies[0].path)
        assertEquals("/path", cookies[1].path)
    }
}
