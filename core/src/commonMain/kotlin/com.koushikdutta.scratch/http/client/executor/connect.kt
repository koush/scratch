package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.client.middleware.createEndWatcher

typealias AsyncHttpConnectSocket = suspend () -> AsyncSocket

class AsyncHttpConnectSocketExecutor(private val affinity: AsyncAffinity = AsyncAffinity.NO_AFFINITY, private val connect: AsyncHttpConnectSocket) {
    private val keepaliveSockets = mutableListOf<AsyncHttpSocketExecutor>()
    val keepaliveSocketSize
        get() = keepaliveSockets.size
    var reusedSocketCount = 0
        internal set

    suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        affinity.await()

        val executor = if (keepaliveSockets.isEmpty()) {
            AsyncHttpSocketExecutor(connect())
        }
        else {
            reusedSocketCount++
            keepaliveSockets.removeFirst()
        }

        val response = executor(request)
        if (!executor.isAlive)
            return response

        // watch the response body to reuse the socket once it is drained.
        val body = response.body
        var reused = false
        var closed = false

        fun reuse() {
            reused = true
            keepaliveSockets.add(executor)
        }

        val responseBody = if (body != null) {
            createEndWatcher(body) {
                affinity.await()
                if (closed)
                    return@createEndWatcher
                reuse()
            }
        }
        else {
            // no body means it can be immediatley reused.
            reuse()
            null
        }

        return AsyncHttpResponse(response.responseLine, response.headers, responseBody) {
            affinity.await()
            if (!reused) {
                closed = true
                response.close()
            }
        }
    }
}
