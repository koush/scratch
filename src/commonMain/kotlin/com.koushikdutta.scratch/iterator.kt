package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBuffer

interface AsyncIterator<out T> {
    suspend operator fun next(): T
    suspend operator fun hasNext(): Boolean
    fun rethrow()
}

class ValueHolder<T>(val value: T)

class AsyncIteratorScope<T> internal constructor(private val yielder: Cooperator) {
    internal var hasValue = false
    internal var currentValue: ValueHolder<T>? = null
    suspend fun yield(value: T) {
        hasValue = true
        currentValue = ValueHolder(value)
        yielder.yield()
    }
}

fun <T> asyncIterator(block: suspend AsyncIteratorScope<T>.() -> Unit): AsyncIterator<T> {
    val yielder = Cooperator()
    var done = false
    val result = Promise<Unit>()
    .setCallback {
        done = true
        yielder.resume()
    }

    val scope = AsyncIteratorScope<T>(yielder)
    startSafeCoroutine {
        try {
            block(scope)
            result.setComplete(null, Unit)
        }
        catch (throwable: Throwable) {
            rethrowUnhandledAsyncException(throwable)
            result.setComplete(throwable, null)
        }
    }

    return object: AsyncIterator<T> {
        override fun rethrow() {
            result.rethrow()
        }

        override suspend fun hasNext(): Boolean {
            if (done)
                return false
            if (!scope.hasValue) {
                yielder.yield()
                result.rethrow()
            }
            return !done
        }

        override suspend fun next(): T {
            if (done)
                throw NoSuchElementException()

            if (!scope.hasValue) {
                yielder.yield()
                result.rethrow()
            }

            if (done)
                throw NoSuchElementException()

            val ret = scope.currentValue
            scope.currentValue = null
            scope.hasValue = false
            return ret!!.value
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
open class AsyncDequeueIterator<T> : AsyncIterable<T> {
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