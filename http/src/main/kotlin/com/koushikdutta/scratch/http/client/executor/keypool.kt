package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor

typealias KeyPoolExecutorFactory = (request: AsyncHttpRequest) -> AsyncHttpExecutor

abstract class KeyPoolExecutor : AsyncHttpClientExecutor {
    val pool = mutableMapOf<String, AsyncHttpExecutor>()
    abstract fun getKey(request: AsyncHttpRequest): String
}

abstract class RegisterKeyPoolExecutor: KeyPoolExecutor() {
    abstract var unhandled: AsyncHttpExecutor

    override suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        affinity.await()

        val key = getKey(request)
        val executor = pool[key]
        if (executor == null)
            return unhandled(request)

        return executor(request)
    }
}

abstract class CreateKeyPoolExecutor : KeyPoolExecutor() {
    override suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        affinity.await()

        val key = getKey(request)
        val executor = pool.getOrPut(key) {
            createExecutor(request)
        }

        return executor(request)
    }

    abstract suspend fun createExecutor(request: AsyncHttpRequest): AsyncHttpExecutor
}
