package com.koushikdutta.scratch.http.client.executor

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.codec.hex
import com.koushikdutta.scratch.collections.getFirst
import com.koushikdutta.scratch.collections.parseStringMultimap
import com.koushikdutta.scratch.event.nanoTime
import com.koushikdutta.scratch.extensions.encode
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.AsyncHttpExecutorBuilder
import com.koushikdutta.scratch.http.server.createSliceableResponse
import kotlin.random.Random

/*
    3.  Storing Responses in Caches

    A cache MUST NOT store a response to any request, unless:

    o  The request method is understood by the cache and defined as being
    cacheable, and

    o  the response status code is understood by the cache, and

    o  the "no-store" cache directive (see Section 5.2) does not appear
    in request or response header fields, and

    o  the "private" response directive (see Section 5.2.2.6) does not
    appear in the response, if the cache is shared, and

    o  the Authorization header field (see Section 4.2 of [RFC7235]) does
    not appear in the request, if the cache is shared, unless the
    response explicitly allows it (see Section 3.2), and

    o  the response either:

    *  contains an Expires header field (see Section 5.3), or

    *  contains a max-age response directive (see Section 5.2.2.8), or

    *  contains a s-maxage response directive (see Section 5.2.2.9)
    and the cache is shared, or

    *  contains a Cache Control Extension (see Section 5.2.3) that
    allows it to be cached, or

    *  has a status code that is defined as cacheable by default (see
    Section 4.2.2), or
*/


private fun AsyncHttpRequest.getCacheType(): CacheType {
    if (method.toUpperCase() != "GET")
        return CacheType.None

    if (body != null)
        return CacheType.None

    for (header in headers.getAll("Cache-Control")) {
        val parsed = parseStringMultimap(header, ",", true) {
            it.toLowerCase()
        }

        // strangely, this is revalidation, not cache bypass
        if (parsed["no-cache"] != null)
            return CacheType.ConditionalCache

        // this is actual cache bypass
        if (parsed["no-store"] != null)
            return CacheType.None
    }

    if (headers["if-modified-since"] != null)
        return CacheType.ConditionalCache

    if (headers["if-none-match"] != null)
        return CacheType.ConditionalCache

    return CacheType.Unspecified
}

private val cacheableStatus = mutableSetOf(200, 301, 302, 308, 410)

fun ResponseLine.isCacheable(): Boolean {
    return cacheableStatus.contains(code)
}

private enum class CacheType {
    // there was an explicit hint that this can be cached
    ExplicitCache,
    // caching is implicit
    Unspecified,
    // caching is conditional
    ConditionalCache,
    // never cache
    None
}

enum class CacheResult {
    Cache,
    ConditionalCache,
}

private class ParsedCacheControl {
    var noCache = false
    var noStore = false
    var public = false
    var maxAge: String? = null
    var sMaxAge: String? = null
    var mustRevalidate = false
    var immutable = false
    var expires: String? = null
    var lastModified: String? = null
    var etag: String? = null
    var vary: String? = null

    var cacheType: CacheType? = null
}

private fun Headers.getResponseCacheControl(request: AsyncHttpRequest): ParsedCacheControl {
    val cacheControl = ParsedCacheControl()

    for (header in getAll("Cache-Control")) {
        val parsed = parseStringMultimap(header, ",", true) {
            it.toLowerCase()
        }

        if (parsed["no-cache"] != null)
            cacheControl.noCache = true

        if (parsed["no-store"] != null)
            cacheControl.noStore = true

        if (parsed["public"] != null)
            cacheControl.public = true

        if (parsed["max-age"] != null)
            cacheControl.maxAge = parsed.getFirst("max-age")

        if (parsed["s-maxage"] != null)
            cacheControl.sMaxAge = parsed.getFirst("s-maxage")

        if (parsed["mustRevalidate"] != null)
            cacheControl.mustRevalidate = true

        if (parsed["immutable"] != null)
            cacheControl.immutable = true
    }

    cacheControl.expires = this["Expires"]
    cacheControl.lastModified = this["Last-Modified"]
    cacheControl.etag = this["ETag"]
    cacheControl.vary = this["Vary"]

//    o  the Authorization header field (see Section 4.2 of [RFC7235]) does
//            not appear in the request, if the cache is shared, unless the
//    response explicitly allows it (see Section 3.2)
//    In this specification, the following Cache-Control response
//    directives (Section 5.2.2) have such an effect: must-revalidate,
//    public, and s-maxage.
    if (request.headers["Authorization"] != null && !cacheControl.public && cacheControl.maxAge != null && cacheControl.sMaxAge != null) {
        cacheControl.cacheType = CacheType.None
        return cacheControl
    }

    // if there's a specific duration on this response, it can explicitly be cached
    val explicit = cacheControl.immutable || cacheControl.maxAge != null || cacheControl.sMaxAge != null || cacheControl.expires != null
    if (explicit) {
        cacheControl.cacheType = CacheType.ExplicitCache
        return cacheControl
    }

    if (cacheControl.mustRevalidate || cacheControl.noCache || cacheControl.public || cacheControl.etag != null || cacheControl.lastModified != null) {
        cacheControl.cacheType = CacheType.ConditionalCache
        return cacheControl
    }

    cacheControl.cacheType = CacheType.Unspecified
    return cacheControl
}

fun randomHex(): String {
    return Random.Default.nextBytes(32).encode().hex()
}

private val XScratchCacheSession = "X-Scratch-Cache-Session"
private val XScratchCacheExpiration = "X-Scratch-Cache-Expiration"

private fun removeTransportHeaders(headers: Headers) {
    headers.remove("Transfer-Encoding")
    headers.remove("Content-Encoding")
}

private class CachedResponse(val conditional: Boolean, responseLine: ResponseLine, headers: Headers, body: AsyncRead, sent: AsyncHttpMessageCompletion) : AsyncHttpResponse(responseLine, headers, body, sent)

interface AsyncStorage: AsyncRandomAccessStorage {
    suspend fun commit()
    suspend fun abort()
}

interface AsyncStore {
    suspend fun openRead(key: String): AsyncRandomAccessInput?
    suspend fun openWrite(key: String): AsyncStorage
    suspend fun remove(key: String)
}

class CacheExecutor(override val next: AsyncHttpClientExecutor, val asyncStore: AsyncStore) : AsyncHttpClientWrappingExecutor {
    val sessionKey = randomHex()

    private suspend fun AsyncHttpRequest.createCachedResponse(headers: Headers, cacheData: AsyncRandomAccessInput): AsyncHttpResponse {
        val start = cacheData.getPosition()
        val size = cacheData.size() - start
        return createSliceableResponse(size, headers) { position, length ->
            cacheData.seekInput(start + position, length)
        }
    }

    private suspend fun prepareExecute(request: AsyncHttpRequest): AsyncHttpResponse? {
        val requestCacheType = request.getCacheType()
        if (requestCacheType == CacheType.None)
            return null

        val key = request.uri.toString()
        val entry = asyncStore.openRead(key)
        if (entry == null)
            return null

        val reader = AsyncReader(entry::read)
        val responseLine: ResponseLine
        val headers: Headers
        try {
            responseLine = ResponseLine(reader.readHttpHeaderLine())
            headers = reader.readHeaderBlock()
        }
        catch (throwable: Throwable) {
            entry.close()
            throw throwable
        }

        val cacheControl = headers.getResponseCacheControl(request)
        // todo: vary unhandled, implement this.
        if (cacheControl.vary != null)
            return null

        // revalidate in case erroneously stored
        if (!responseLine.isCacheable())
            return null

        // grab the cached headers and prepare to serve them if necessary
        removeTransportHeaders(headers)
        val headerLength = entry.getPosition() - reader.buffered
        entry.seekPosition(headerLength)
        val dataLength = entry.size() - headerLength
        headers["Content-Length"] = "${dataLength}"

        if (cacheControl.cacheType == CacheType.ConditionalCache) {
            if (cacheControl.etag != null)
                request.headers["If-None-Match"] = cacheControl.etag!!
            if (cacheControl.lastModified != null)
                request.headers["If-Modified-Since"] = cacheControl.lastModified!!
            headers["X-Scratch-Cache"] = CacheResult.ConditionalCache.toString()
            return request.createCachedResponse(headers, entry)
        }

        headers["X-Scratch-Cache"] = CacheResult.Cache.toString()

        // only responses with explicit cache directives will be served
        if (cacheControl.cacheType != CacheType.ExplicitCache) {
            entry.close()
            return null
        }

        if (!cacheControl.immutable) {
            val cacheSession = headers[XScratchCacheSession]
            val cacheExpiration = headers[XScratchCacheExpiration]?.toLong()
            if (cacheSession != sessionKey || cacheExpiration == null || cacheExpiration < nanoTime()) {
                // send expired back somehow?
                return null
            }
        }

        return request.createCachedResponse(headers, entry)
    }

    override suspend operator fun invoke(request: AsyncHttpRequest): AsyncHttpResponse {
        val conditionalResponse = try {
            // attempt to retrieve directly from cache, or prepare any conditional cache headers
            val cacheResponse = prepareExecute(request)
            // response is cached for an explicit duration, can be served without sending the request
            if (cacheResponse != null && cacheResponse.headers["X-Scratch-Cache"] != CacheType.ConditionalCache.toString())
                return cacheResponse
            cacheResponse
        }
        catch (throwable: Throwable) {
            // any errors can be ignored, just make the request instead.
            null
        }

        val response = try {
            val response = next(request)
            // if the conditional response was successful, clean up the validated response and return
            // the cached response
            if (conditionalResponse != null && response.code == StatusCode.NOT_MODIFIED.code) {
                // drain the body to allow socket reuse.
                response.body?.drain()
                return conditionalResponse
            }

            response
        }
        catch (throwable: Throwable) {
            // if the response fails, clean up and report the error
            conditionalResponse?.close(throwable)
            throw throwable
        }

        if (!response.responseLine.isCacheable())
            return response

        val cacheControl = response.headers.getResponseCacheControl(request)
        if (cacheControl.cacheType == CacheType.None)
            return response

        // attempt to cache
        val key = request.uri.toString()
        val entry = asyncStore.openWrite(key)

        try {
            if (cacheControl.cacheType == CacheType.ExplicitCache && !cacheControl.immutable) {
                // the expires header is problematic since it references server time.
                // so are the max age directives, actually.
                // time can be incorrect. attach the session key and only use the system clock.
                val maxAge = (cacheControl.sMaxAge ?: cacheControl.maxAge)!!.toLong()
                val expiration = nanoTime() + maxAge * 1000000000L
                response.headers[XScratchCacheSession] = sessionKey
                response.headers[XScratchCacheExpiration] = expiration.toString()
            }

            val buffer = ByteBufferList()
            buffer.putUtf8String(response.toMessageString())
            entry::write.drain(buffer)
        }
        catch (throwable: Throwable) {
            entry.close()
            return response
        }

        val body = response.body!!
        var error: Throwable? = null
        val newBody = body.tee(entry::write) {
            if (it != null) {
                // ignore any errors, but bail on the caching.
                error = it
                entry.abort()
            }
            else if (error == null) {
                // cached successfully, move the file into place
                entry.commit()
            }
        }

        return AsyncHttpResponse(response.responseLine, response.headers, newBody, response::close)
    }
}

fun AsyncHttpExecutorBuilder.useMemoryCache(): AsyncHttpExecutorBuilder {
    wrapExecutor {
        CacheExecutor(it, asyncStore = BufferStore())
    }
    return this
}

class BufferStore : AsyncStore {
    private val buffers = mutableMapOf<String, ByteBuffer>()

    override suspend fun openRead(key: String): AsyncRandomAccessInput? {
        val buffer = buffers[key]?.duplicate()
        if (buffer == null)
            return null
        return BufferStorage(ByteBufferList(ByteBufferList.deepCopyExactSize(buffer)))
    }

    override suspend fun openWrite(key: String): AsyncStorage {
        val storage = BufferStorage(ByteBufferList())
        return object : AsyncStorage, AsyncRandomAccessStorage by storage {
            override suspend fun commit() {
                buffers[key] = storage.deepCopyByteBuffer()
            }

            override suspend fun abort() {
            }
        }
    }

    override suspend fun remove(key: String) {
        buffers.remove(key)
    }

}
