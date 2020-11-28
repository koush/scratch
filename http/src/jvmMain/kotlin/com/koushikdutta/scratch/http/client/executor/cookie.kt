package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.AsyncStore
import com.koushikdutta.scratch.BufferStore
import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.drain
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.AsyncHttpExecutorBuilder
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI


class CookieExecutor(override val next: AsyncHttpClientExecutor, val store: AsyncStore = BufferStore(), val policy: CookiePolicy? = null) : AsyncHttpClientWrappingExecutor {
    var manager: CookieManager? = null

    suspend fun ensureInitialized() {
        affinity.await()

        if (manager == null)
            return

        val manager = CookieManager(null, policy)
        this.manager = manager
        for (key in store.getKeys()) {
            val entry = store.openRead(key)
            if (entry == null)
                continue
            entry.runCatching {
                val reader = AsyncReader(this)
                val headers = reader.readHeaderBlock()
                for (header in headers) {
                    manager.put(URI(key), headers.toStringMultimap())
                }
            }
            entry.close()
        }
    }

    override suspend fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        ensureInitialized()

        val uri = try {
            val uri = URI(
                    request.uri.toString())
            val cookies = manager!!.get(uri, request.headers.toStringMultimap())
            addCookies(cookies, request.headers)
            uri
        }
        catch (throwable: Throwable) {
            return next(request)
            // ignore or rethrow?
        }

        val ret =  next(request)
        put(uri, ret.headers)
        return ret
    }

    suspend fun put(uri: URI, headers: Headers) {
        ensureInitialized()

        try {
            manager!!.put(uri, headers.toStringMultimap())

            // no cookies to persist.
            if (headers["Set-Cookie"] == null) return
            val cookies: List<HttpCookie> = manager!!.cookieStore[uri]
            val dump = Headers()
            for (cookie in cookies) {
                if (cookie.path != uri.path)
                    continue
                dump.add("Set-Cookie", cookie.getName().toString() + "=" + cookie.getValue() + "; path=" + cookie.getPath())
            }
            val key = uri.toString()
            val entry = store.openWrite(key)

            try {
                entry.drain(dump.toString().createByteBufferList())
                entry.close()
            }
            catch (throwable: Throwable) {
                entry.abort()
            }
        }
        catch (throwable: Throwable) {
        }
    }

    companion object {
        fun addCookies(allCookieHeaders: Map<String, List<String?>?>, headers: Headers) {
            for ((key, value) in allCookieHeaders) {
                if ("Cookie".equals(key, ignoreCase = true) || "Cookie2".equals(key, ignoreCase = true)) {
                    headers.addAll(key, *(value!!.mapNotNull { it }.toTypedArray()))
                }
            }
        }
    }
}

fun AsyncHttpExecutorBuilder.useCookieExecutor(store: AsyncStore = BufferStore(), policy: CookiePolicy? = null) = wrapExecutor {
    CookieExecutor(it, store, policy)
}
