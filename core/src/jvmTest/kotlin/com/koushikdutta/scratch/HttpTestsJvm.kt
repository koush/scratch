package com.koushikdutta.scratch

import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.runUnit
import com.koushikdutta.scratch.http.createFileResponse
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import java.io.File

class HttpTestsJvm {
//    @Test
    fun testRunSleep() = AsyncEventLoop().runUnit {
        val cwd = File("").absolutePath
        AsyncHttpServer {
            it.createFileResponse(AsyncFile(cwd, it.uri.path!!))
        }
        .listen(3000)
        .awaitClose()
    }
}
