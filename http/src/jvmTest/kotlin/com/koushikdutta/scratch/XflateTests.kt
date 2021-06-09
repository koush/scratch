package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.filters.DeflatePipe
import com.koushikdutta.scratch.filters.InflatePipe
import com.koushikdutta.scratch.parser.parse
import com.koushikdutta.scratch.parser.readString
import org.junit.Test

class XflateTests {
    @Test
    fun testXflate() {
        val bb = ByteBufferList()
        val text = "quick brown fox something something dog barf\n"

        var finalText = ""
        for (i in 1..10) {
            bb.putUtf8String(text)
            finalText += text
        }

        val read = bb.createReader().pipe(DeflatePipe).pipe(InflatePipe)

        var parsed = ""
        async {
            parsed = read.parse().readString()
        }

        assert(parsed == finalText)
    }
}