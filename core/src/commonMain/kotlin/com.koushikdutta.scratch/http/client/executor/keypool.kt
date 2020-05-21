package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.client.AsyncHttpClientException
import com.koushikdutta.scratch.http.client.AsyncHttpClientExecutor
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor

typealias KeyPoolExecutorFactory = (request: AsyncHttpRequest) -> AsyncHttpExecutor

private val Unhandled: AsyncHttpExecutor = {
    throw AsyncHttpClientException("unable to find transport to exchange headers for uri ${it.uri}")
}

abstract class KeyPoolExecutor(var unhandled: AsyncHttpExecutor = Unhandled) : AsyncHttpClientExecutor {
    protected val pool = mutableMapOf<String, AsyncHttpExecutor>()
    abstract fun getKey(request: AsyncHttpRequest): String
}

abstract class RegisterKeyPoolExecutor: KeyPoolExecutor() {
    override suspend fun execute(request: AsyncHttpRequest): AsyncHttpResponse {
        eventLoop.await()

        val key = getKey(request)
        val executor = pool[key]
        if (executor == null)
            return unhandled(request)

        return executor(request)
    }
}

abstract class CreateKeyPoolExecutor(private val create: KeyPoolExecutorFactory) : KeyPoolExecutor() {
    override suspend fun execute(request: AsyncHttpRequest): AsyncHttpResponse {
        eventLoop.await()

        val key = getKey(request)
        val executor = pool.getOrPut(key) {
            create(request)
        }

        return executor(request)
    }
}