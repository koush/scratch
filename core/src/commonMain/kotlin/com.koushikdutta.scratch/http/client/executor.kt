import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.atomic.FreezableReference
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.client.*
import com.koushikdutta.scratch.http.server.AsyncHttpResponseScope

interface AsyncHttpExecutor {
    suspend fun execute(request: AsyncHttpRequest): AsyncHttpResponse
    val client: AsyncHttpClient
    val eventLoop: AsyncEventLoop
        get() = client.eventLoop
}

suspend fun AsyncHttpExecutor.execute(responseScope: AsyncHttpResponseScope) = execute(responseScope.request)

suspend fun <R> AsyncHttpExecutor.execute(request: AsyncHttpRequest, handler: AsyncHttpResponseHandler<R>): R {
    return execute(request).handle(handler)
}

internal suspend fun <R> AsyncHttpResponse.handle(handler: AsyncHttpResponseHandler<R>): R {
    try {
        return handler(this)
    }
    finally {
        close()
    }
}

class AsyncHttpExecutorBuilder(private var executor: AsyncHttpExecutor) {
    fun build(): AsyncHttpExecutor {
        return executor
    }

    fun wrapExecutor(previous: (executor: AsyncHttpExecutor) -> AsyncHttpExecutor): AsyncHttpExecutorBuilder {
        executor = previous(executor)
        return this
    }
}

fun AsyncHttpExecutor.buildUpon(): AsyncHttpExecutorBuilder {
    return AsyncHttpExecutorBuilder(this)
}

suspend fun <R> AsyncHttpExecutor.get(uri: String, handler: AsyncHttpResponseHandler<R>): R {
    return execute(Methods.GET(uri), handler)
}

suspend fun <R> AsyncHttpExecutor.head(uri: String, handler: AsyncHttpResponseHandler<R>): R {
    return execute(Methods.HEAD(uri), handler)
}

suspend fun <R> AsyncHttpExecutor.post(uri: String, handler: AsyncHttpResponseHandler<R>): R {
    return execute(Methods.POST(uri), handler)
}

suspend fun AsyncHttpExecutor.randomAccess(uri: String): AsyncRandomAccessInput {
    val contentLength = head(uri) {
        if (it.headers["Accept-Ranges"] != "bytes")
            throw IOException("$uri can not fulfill range requests")
        it.headers.contentLength!!
    }

    val currentReader = FreezableReference<AsyncHttpResponse?>()
    var currentPosition: Long = 0
    var currentRemaining: Long = 0
    val temp = ByteBufferList()

    return object : AsyncRandomAccessInput, AsyncAffinity by eventLoop {
        override suspend fun size(): Long {
            return contentLength
        }

        override suspend fun getPosition(): Long {
            return currentPosition
        }

        override suspend fun setPosition(position: Long) {
            currentReader.swap(null)?.value?.close()
            currentPosition = position
            currentRemaining = 0
        }

        override suspend fun readPosition(position: Long, length: Long, buffer: WritableBuffers): Boolean {
            if (currentReader.isFrozen)
                throw IOException("closed")

            if (position == contentLength)
                return false

            if (position + length > contentLength)
                throw IOException("invalid range")

            // check if the existing read can fulfill this request, and not go over the
            // requested length
            if (currentPosition != position || currentRemaining <= 0L || currentRemaining > length) {
                val headers = Headers()
                headers["Range"] = "bytes=$position-${position + length - 1}"
                val newRequest = Methods.GET(uri, headers)
                val newResponse = execute(newRequest)
                val existing = currentReader.swap(newResponse)
                if (existing?.frozen == true)
                    newResponse.close()

                currentPosition = position
                currentRemaining = length
            }

            temp.takeReclaimedBuffers(buffer)
            if (!currentReader.get()!!.value!!.body!!(temp))
                return false

            currentPosition += temp.remaining()
            currentRemaining -= temp.remaining()
            temp.read(buffer)

            return true
        }

        override suspend fun read(buffer: WritableBuffers): Boolean {
            return readPosition(currentPosition, contentLength - currentPosition, buffer)
        }

        override suspend fun close() {
            currentReader.freeze(null)?.value?.close()
        }
    }
}
