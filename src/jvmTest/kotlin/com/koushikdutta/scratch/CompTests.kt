package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import org.junit.Test

class CompTests {

    @Test
    fun testDiscardServer() {
        val port = 8080

        DiscardServer(port).run();
    }

    @Test
    fun testScratchServer() {
        val loop = AsyncEventLoop()
        loop.async {
            val server = loop.listen(8080)

            server.accept().receive {
                val start = ByteBufferList.totalObtained
                val buffer = ByteBufferList()
                while (read(buffer)) {
                    write(buffer)
                }
                println("alloc: ${ByteBufferList.totalObtained - start}")
            }
        }

        loop.run()
    }
}
