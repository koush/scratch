package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.AsyncIterable
import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.createAsyncIterable
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor
import com.koushikdutta.scratch.http.client.createEndWatcher
import kotlinx.coroutines.yield

typealias AsyncHttpConnectSocket = suspend () -> AsyncSocket

class AsyncHttpConnectSocketExecutor(override val affinity: AsyncAffinity = AsyncAffinity.NO_AFFINITY, private val connect: AsyncHttpConnectSocket): AsyncHttpClientExecutor {
    private val keepaliveSockets = mutableListOf<AsyncHttpSocketExecutor>()
    val keepaliveSocketSize
        get() = keepaliveSockets.size
    var reusedSocketCount = 0
        internal set

    override suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        affinity.await()

        val executor: AsyncHttpSocketExecutor
        if (keepaliveSockets.isEmpty()) {
            executor = AsyncHttpSocketExecutor(connect())
        }
        else {
            var found: AsyncHttpSocketExecutor? = null
            while (keepaliveSockets.isNotEmpty()) {
                val check = keepaliveSockets.removeFirst()
                if (check.isAlive) {
                    found = check
                    reusedSocketCount++
                    break
                }
            }

            executor = found ?: AsyncHttpSocketExecutor(connect())
        }

        val response = executor(request)
        if (!executor.isAlive) {
            return AsyncHttpResponse(response.responseLine, response.headers, response.body) {
                executor.socket.close()
            }
        }

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
            // no body means it can be immediately reused.
            reuse()
            null
        }

        return AsyncHttpResponse(response.responseLine, response.headers, responseBody) {
            affinity.await()
            if (!reused) {
                closed = true
                executor.socket.close()
            }
        }
    }
}
