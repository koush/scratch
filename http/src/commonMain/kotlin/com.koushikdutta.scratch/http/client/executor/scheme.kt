package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.IOException
import com.koushikdutta.scratch.http.AsyncHttpRequest
import com.koushikdutta.scratch.http.client.AsyncHttpExecutor
import com.koushikdutta.scratch.uri.URI
import com.koushikdutta.scratch.uri.scheme

class SchemeUnhandledException(uri: URI) : IOException("unable to find scheme handler for ${uri}")

class SchemeExecutor(override val affinity: AsyncAffinity) : RegisterKeyPoolExecutor() {
    override var unhandled: AsyncHttpExecutor = {
        throw SchemeUnhandledException(it.uri)
    }

    override fun getKey(request: AsyncHttpRequest): String {
        if (request.uri.scheme == null)
            return ""
        return request.uri.scheme!!
    }

    fun register(scheme: String, executor: AsyncHttpExecutor): SchemeExecutor {
        pool[scheme] = executor
        return this
    }
}
