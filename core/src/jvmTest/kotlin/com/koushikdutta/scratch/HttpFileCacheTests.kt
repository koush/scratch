package com.koushikdutta.scratch

import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.client.AsyncHttpClientExecutor
import com.koushikdutta.scratch.http.client.buildUpon
import com.koushikdutta.scratch.http.client.middleware.useFileCache

class HttpFileCacheTests: HttpCacheTests() {
    override fun createClient(): AsyncHttpClientExecutor {
        return AsyncHttpClient()
                .buildUpon()
                .useFileCache()
                .build()
    }
}
