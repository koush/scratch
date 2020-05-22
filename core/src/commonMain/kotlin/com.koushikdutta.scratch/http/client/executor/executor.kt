package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.client.AsyncHttpClient


interface AsyncHttpClientExecutor {
    suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse
    val affinity: AsyncAffinity
}
