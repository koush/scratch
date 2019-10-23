package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import org.junit.Test

class CompTests {
//    @Test
//    fun testDispatcher() {
//        val loop = AsyncEventLoop()
//        loop.launch {
//            val server = loop.listen(8080)
//
//            val ret = server.acceptAsync {
//                val start = ByteBufferList.totalObtained
//                val buffer = ByteBufferList()
//                while (read(buffer)) {
//                    write(buffer)
//                }
//                println("alloc: ${ByteBufferList.totalObtained - start}")
//            }
//        }
//        loop.run()
//    }
//
//    @Test
//    fun testDiscardServer() {
//        val port = 8080
//
//        DiscardServer(port).run();
//    }
//
//    @Test
//    fun testScratchServer() {
//        val loop = AsyncEventLoop()
//        loop.async {
//            val server = loop.listen(8080)
//
//            server.acceptAsync {
//                try {
//                    val start = ByteBufferList.totalObtained
//                    val buffer = ByteBufferList()
//                    while (read(buffer)) {
//                        write(buffer)
//                    }
//                    println("alloc: ${ByteBufferList.totalObtained - start}")
//                }
//                finally {
//                    println(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
//
//                }
//            }
//            .closeOnError()
//        }
//
//        loop.run()
//    }
}
