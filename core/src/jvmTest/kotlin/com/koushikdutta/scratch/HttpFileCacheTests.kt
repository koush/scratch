package com.koushikdutta.scratch

import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor
import com.koushikdutta.scratch.http.client.buildUpon
import com.koushikdutta.scratch.http.client.executor.AsyncHttpClientExecutor
import com.koushikdutta.scratch.http.client.middleware.useFileCache

class HttpFileCacheTests: HttpCacheTests() {
    override fun createClient(loop: AsyncEventLoop, callback: AsyncHttpExecutor): AsyncHttpClientExecutor {
        val client = AsyncHttpClient(loop)
        client.schemeExecutor.unhandled = callback
        return client
                .buildUpon()
                .useFileCache(loop)
                .build()
    }
}
