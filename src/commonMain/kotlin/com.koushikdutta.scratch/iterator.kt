package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBuffer

interface AsyncIterator<out T> {
    suspend operator fun next(): T
    suspend operator fun hasNext(): Boolean
    fun rethrow()
}

internal class AsyncIteratorMessage<T>(val throwable: Throwable? = null, val value: T? = null, val done: Boolean = false, val hasNext: Boolean = false, val next: Boolean = false, val resuming: Boolean = false)
class AsyncIteratorScope<T> internal constructor(private val baton: Baton<AsyncIteratorMessage<T>>) {
    private val resumingMessage = AsyncIteratorMessage<T>(resuming = true)

    private fun validateMessage(message: AsyncIteratorMessage<T>): AsyncIteratorMessage<T> {
        if (!message.hasNext && !message.next)
            throw Exception("iterator hasNext/next called before completion of prior invocation")
        return message
    }

    suspend fun waitNext() {
        // wait for iterator to start/resume, other side will need to request again.
        validateMessage(baton.pass(resumingMessage))
    }

    suspend fun yield(value: T) {
        val state = AsyncIteratorMessage(value = value)
        // service hasNext requests until next is called
        while (!validateMessage(baton.pass(state)).next) {
        }
        waitNext()
    }
}

fun <T> asyncIterator(block: suspend AsyncIteratorScope<T>.() -> Unit): AsyncIterator<T> {
    val baton = Baton<AsyncIteratorMessage<T>>()
    val scope = AsyncIteratorScope<T>(baton)
    startSafeCoroutine {
        try {
            scope.waitNext()
            block(scope)
            baton.finish(AsyncIteratorMessage(done = true))
        }
        catch (throwable: Throwable) {
            rethrowUnhandledAsyncException(throwable)
            baton.raise(throwable)
            baton.finish(AsyncIteratorMessage(throwable = throwable, done = true))
        }
    }

    return object: AsyncIterator<T> {
        override fun rethrow() {
            baton.rethrow()
        }

        private fun validateMessage(message: AsyncIteratorMessage<T>): AsyncIteratorMessage<T> {
            if (message.hasNext || message.next)
                throw Exception("iterator hasNext/next called before completion of prior invocation")
            return message
        }

        private suspend fun checkResumeNext(message: AsyncIteratorMessage<T>): AsyncIteratorMessage<T> {
            val result = validateMessage(baton.pass(message))
            if (result.resuming)
                return validateMessage(baton.pass(message))
            return result
        }

        override suspend fun hasNext(): Boolean {
            return !checkResumeNext(AsyncIteratorMessage(hasNext = true)).done
        }

        override suspend fun next(): T {
            val next = checkResumeNext(AsyncIteratorMessage(next = true))
            if (next.done)
                throw NoSuchElementException()
            return next.value!!
        }
    }
}

interface AsyncIterable<T> {
    operator fun iterator(): AsyncIterator<T>
}


fun <T> createAsyncIterable(iterable: Iterable<T>): AsyncIterable<T> {
    return object : AsyncIterable<T> {
        override fun iterator(): AsyncIterator<T> {
            val iterator = iterable.iterator()
            return asyncIterator {
                while (iterator.hasNext()) {
                    yield(iterator.next())
                }
            }
        }
    }
}

fun <T> createAsyncIterable(block: suspend AsyncIteratorScope<T>.() -> Unit): AsyncIterable<T> {
    return object : AsyncIterable<T> {
        override fun iterator(): AsyncIterator<T> {
            return asyncIterator(block)
        }
    }
}

/**
 * Feed an async iterator by queuing items into it.
 * TBD: for .. in loops will pop items from the queue. Remove iterator operator keyword?
 */
open class AsyncQueue<T> : AsyncIterable<T> {
    private val yielder = Cooperator()
    private val deque = mutableListOf<T>()
    private var exception: Throwable? = null

    protected open fun popped(value: T) {
    }

    private val iter = asyncIterator<T> {
        while (deque.isNotEmpty() || !hasEnded) {
            if (deque.isEmpty())
                yielder.yield()
            if (deque.isEmpty())
                break
            val value = deque.removeAt(0)
            yield(value)
            popped(value)
        }
        if (exception != null)
            throw exception!!
    }

    override operator fun iterator(): AsyncIterator<T> {
        return iter
    }

    val size: Int
        get() = deque.size

    private fun checkEnd() {
        if (hasEnded)
            throw IllegalStateException("end already called")
    }

    private fun endInternal() {
        checkEnd()
        hasEnded = true
        yielder.resume()
    }

    private var hasEnded = false
    fun end() {
        endInternal()
    }
    fun end(exception: Throwable) {
        this.exception = exception
        endInternal()
    }

    open fun add(value: T) {
        checkEnd()
        iter.rethrow()
        deque.add(value)
        yielder.resume()
    }
}

fun AsyncIterator<AsyncRead>.join(): AsyncRead {
    var read: AsyncRead? = null
    return read@{
        if (read == null) {
            if (!hasNext())
                return@read false
            read = next()
        }

        if (!read!!(it))
            read = null

        true
    }
}

fun AsyncIterator<ByteBuffer>.createAsyncRead(): AsyncRead {
    return read@{
        if (!hasNext())
            return@read false
        it.add(next())
        true
    }
}