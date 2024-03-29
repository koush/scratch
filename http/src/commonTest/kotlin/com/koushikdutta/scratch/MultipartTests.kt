package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.body.Multipart
import com.koushikdutta.scratch.http.body.Part
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.parser.*
import kotlin.test.Test
import kotlin.test.assertEquals

class MultipartTests {
    @Test
    fun testMultipart() {
        val expected = (""
                + "--123\r\n"
                + "Content-Length: 5\r\n"
                + "Content: Quick\r\n"
                + "\r\n"
                + "Quick\r\n"
                + "--123\r\n"
                + "Content-Length: 5\r\n"
                + "Content: Brown\r\n"
                + "\r\n"
                + "Brown\r\n"
                + "--123\r\n"
                + "Content-Length: 3\r\n"
                + "Content: Fox\r\n"
                + "\r\n"
                + "Fox\r\n"
                + "--123--\r\n")

        val bb = ByteBufferList()
        bb.putUtf8String(expected)

        val read = bb.createReader()

        val reader = AsyncReader(read)

        val expectedStrings = mutableListOf("Quick", "Brown", "Fox")

        var partsFound = 0
        async {
            val multipart = Multipart.parseMultipart("123", reader)
            for (part in multipart) {
                val found = part.body.parse().readString()
                assertEquals(expectedStrings.removeAt(0), found)
                partsFound++
            }
        }

        assertEquals(partsFound, 3)
    }

    @Test
    fun testMultipartRoundtrip() {
        val parts = listOf(Part(body = Utf8StringBody("Hello")), Part(body = Utf8StringBody("World")))
        val multipart = Multipart(parts)

        var combined = ""
        async {
            val multipartParsed =
                Multipart.parseMultipart(multipart.boundary, AsyncReader(multipart))

            for (part in multipartParsed) {
                val found = part.body.parse().readString()
                combined += found
            }
        }

        assertEquals(combined, "HelloWorld")
    }
}
