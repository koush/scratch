package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import kotlin.reflect.KClass

interface AsyncHttpClientExecutor {
    suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse
    val affinity: AsyncAffinity
}

interface AsyncHttpClientWrappingExecutor : AsyncHttpClientExecutor {
    val next: AsyncHttpClientExecutor
    override val affinity: AsyncAffinity
        get() = next.affinity
}

fun AsyncHttpClientExecutor.getWrappedAsyncHttpClientExecutor(): AsyncHttpClientExecutor? {
    if (this !is AsyncHttpClientWrappingExecutor)
        return null
    return this.next
}

fun AsyncHttpClientExecutor.getHttpClientExecutorChain(): Array<AsyncHttpClientExecutor> {
    val list = mutableListOf<AsyncHttpClientExecutor>()
    var current: AsyncHttpClientExecutor? = this
    while (current != null) {
        list.add(current)
        current = current.getWrappedAsyncHttpClientExecutor()
    }
    return list.toTypedArray()
}

fun AsyncHttpClientExecutor.findHttpClientExecutor(kclass: KClass<*>): AsyncHttpClientExecutor? {
    var current: AsyncHttpClientExecutor? = this
    while (current != null) {
        if (kclass.isInstance(current))
            return current
    }
    return null
}
