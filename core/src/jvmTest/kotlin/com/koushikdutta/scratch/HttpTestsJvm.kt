package com.koushikdutta.scratch

import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.runUnit
import com.koushikdutta.scratch.http.createResponse
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import org.junit.Test
import java.io.File

class HttpTestsJvm {
//    @Test
    fun testRunSleep() = AsyncEventLoop().runUnit {
        val cwd = File("").absolutePath
        AsyncHttpServer {
            createResponse(AsyncFile(cwd, request.uri.path!!))
        }
        .listen(3000)
        .awaitClose()
    }
}
