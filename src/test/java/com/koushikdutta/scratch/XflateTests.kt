package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.DeflatePipe
import com.koushikdutta.scratch.filters.InflatePipe
import com.koushikdutta.scratch.parser.readAllString
import org.junit.Test

class XflateTests {
    @Test
    fun testXflate() {
        val bb = ByteBufferList()
        val text = "quick brown fox something something dog barf\n"

        var finalText = ""
        for (i in 1..10) {
            bb.putString(text)
            finalText += text
        }

        val read = bb.reader().pipe(DeflatePipe).pipe(InflatePipe)

        var parsed = ""
        async {
            parsed = readAllString(read)
        }

        assert(parsed == finalText)
    }
}