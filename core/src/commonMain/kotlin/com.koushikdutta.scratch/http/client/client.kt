package com.koushikdutta.scratch.http.client

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.client.executor.AsyncHttpClientExecutor
import com.koushikdutta.scratch.http.client.executor.SchemeExecutor
import com.koushikdutta.scratch.http.client.executor.useHttpExecutor
import com.koushikdutta.scratch.http.client.executor.useHttpsExecutor

typealias AsyncHttpResponseHandler<R> = suspend (response: AsyncHttpResponse) -> R

interface AsyncHttpDetachedSocket {
    val socket: AsyncSocket
    val socketReader: AsyncReader
}

fun AsyncHttpExecutorBuilder.addDefaultHeaders(userAgent: String = "scratch/1.0"): AsyncHttpExecutorBuilder {
    wrapExecutor {
        DefaultHeadersExecutor(it, userAgent)
    }
    return this
}


class DefaultHeadersExecutor(val next: AsyncHttpClientExecutor, val userAgent: String) : AsyncHttpClientExecutor {
    override val affinity: AsyncAffinity = next.affinity

    private fun addHeaderIfNotExists(headers: Headers, name: String, value: String) {
        if (!headers.contains(name))
            headers.add(name, value)
    }

    override suspend fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        affinity.await()

        val host = request.uri.host
        if (host != null)
            addHeaderIfNotExists(request.headers, "Host", host)
        addHeaderIfNotExists(request.headers, "User-Agent", userAgent)

        return next(request)
    }
}

class AsyncHttpClient(val eventLoop: AsyncEventLoop = AsyncEventLoop.default): AsyncHttpClientExecutor {
    private val executor: AsyncHttpExecutor
    val schemeExecutor: SchemeExecutor
    override val affinity = eventLoop

    init {
        schemeExecutor = SchemeExecutor(affinity)
        schemeExecutor.useHttpExecutor(eventLoop)
        schemeExecutor.useHttpsExecutor(eventLoop)
        executor = schemeExecutor.buildUpon().addDefaultHeaders().build()::invoke
    }

    override suspend operator fun invoke(request: AsyncHttpRequest) = executor(request)
}
