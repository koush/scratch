package com.koushikdutta.scratch

import kotlin.NoSuchElementException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

interface AsyncIterator<out T> {
    suspend operator fun next(): T
    suspend operator fun hasNext(): Boolean
}

class ValueHolder<T>(val value: T)

class AsyncIteratorScope<T>(private val yielder: Cooperator) {
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
    val result = AsyncResult<Unit> {
        done = true
        yielder.resume()
    }

    val scope = AsyncIteratorScope<T>(yielder)
    block.startCoroutine(scope, Continuation(EmptyCoroutineContext) { fin ->
        result.setComplete(fin)
    })

    return object: AsyncIterator<T> {
        override suspend fun hasNext(): Boolean {
            if (done)
                return false
            if (!scope.hasValue)
                yielder.yield()
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

fun <T> AsyncIterable<T>.receive(receiver: suspend T.() -> Unit) = async {
    for (received in this) {
        async {
            receiver(received)
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

    protected open fun popped(value: T) {
    }

    private val iter = asyncIterator<T> {
        while (!done) {
            if (deque.isEmpty())
                yielder.yield()
            val value = deque.removeAt(0)
            yield(value)
            popped(value)
        }
    }

    override operator fun iterator(): AsyncIterator<T> {
        return iter
    }

    val size: Int
        get() = deque.size

    private var done = false
    fun end() {
        if (done)
            throw IllegalStateException("done already called")
        done = true
        yielder.resume()
    }

    open fun add(value: T) {
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