package com.koushikdutta.scratch

import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.async
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.body.StringBody
import com.koushikdutta.scratch.parser.Multipart
import com.koushikdutta.scratch.parser.Part
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.reader
import org.junit.Test

class MultipartTests {
    @Test
    fun testMultipart() {
        val expected = (""
                + "--123\r\n"
                + "Content-Length: 5\r\n"
                + "\r\n"
                + "Quick\r\n"
                + "--123\r\n"
                + "Content-Length: 5\r\n"
                + "\r\n"
                + "Brown\r\n"
                + "--123\r\n"
                + "Content-Length: 3\r\n"
                + "\r\n"
                + "Fox\r\n"
                + "--123--\r\n")

        val bb = ByteBufferList()
        bb.putUtf8String(expected)

        val read = bb.reader()

        val reader = AsyncReader(read)

        val expectedStrings = mutableListOf("Quick", "Brown", "Fox")

        var partsFound = 0
        async {
            val multipart = Multipart.parseMultipart("123", reader)
            for (part in multipart) {
                val found = readAllString(part.body)
                assert(expectedStrings.removeAt(0) == found)
                partsFound++
            }
        }

        assert(partsFound == 3)
    }

    @Test
    fun testMultipartRoundtrip() {
        val parts = listOf(Part(body = StringBody("Hello")), Part(body = StringBody("World")))
        val multipart = Multipart(parts)

        var combined = ""
        async {
            val multipartParsed =
                Multipart.parseMultipart(multipart.boundary, AsyncReader(multipart.read))

            for (part in multipartParsed) {
                val found = readAllString(part.body)
                combined += found
            }
        }

        assert(combined == "HelloWorld")
    }
}
