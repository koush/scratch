package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.startSafeCoroutine
import com.koushikdutta.scratch.atomic.FreezableQueue
import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.ByteBufferList

interface AsyncIterator<out T> {
    suspend operator fun next(): T
    suspend operator fun hasNext(): Boolean
    fun rethrow()
}

class AsyncIteratorMessage<T>(val throwable: Throwable? = null, val value: T? = null, val done: Boolean = false, val hasNext: Boolean = false, val next: Boolean = false, val resuming: Boolean = false)
open class AsyncIteratorScope<T> internal constructor(private val baton: Baton<AsyncIteratorMessage<T>>) {
    private val resumingMessage = AsyncIteratorMessage<T>(resuming = true)

    private fun validateMessage(message: AsyncIteratorMessage<T>): AsyncIteratorMessage<T> {
        if (!message.hasNext && !message.next)
            throw Exception("iterator yield called before completion of prior invocation")
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

class AsyncIteratorConcurrentException(val resumed: Boolean): Exception("iterator hasNext/next called before completion of prior invocation")

fun <T> asyncIterator(block: suspend AsyncIteratorScope<T>.() -> Unit): AsyncIterator<T> {
    val baton = Baton<AsyncIteratorMessage<T>>()
    val scope = AsyncIteratorScope(baton)
    startSafeCoroutine {
        try {
            scope.waitNext()
            block(scope)
            baton.finish(AsyncIteratorMessage(done = true))
        }
        catch (throwable: Throwable) {
            baton.raise(throwable)
            baton.finish(AsyncIteratorMessage(throwable = throwable, done = true))
        }
    }

    return object: AsyncIterator<T> {
        override fun rethrow() {
            baton.rethrow()
        }

        private val lock: BatonLock<AsyncIteratorMessage<T>, AsyncIteratorMessage<T>> = {
            it.rethrow()
            if (it.value!!.hasNext || it.value.next)
                throw AsyncIteratorConcurrentException(it.resumed)
            it.value
        }

        private suspend fun checkResumeNext(message: AsyncIteratorMessage<T>): AsyncIteratorMessage<T> {
            val result = baton.pass(message, lock)
            if (result.resuming)
                return baton.pass(message, lock)
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
    private val queue = FreezableQueue<T>()
    private val baton = Baton<Unit>()

    protected open fun removed(value: T) {
    }

    private val iter = asyncIterator<T> {
        while (true) {
            val item = queue.remove()

            // queue empty
            if (item == null) {
                // ignore exceptions to drain the queue
                baton.pass(Unit) { true }
                continue
            }

            // end of queue, wait until the baton finishes.
            if (item.frozen) {
                // rethrow the finisher, or continue if not finished.
                if (baton.pass(Unit) { it.rethrow(); it.finished })
                    break
            }

            val value = item.value!!
            yield(value)
            removed(value)
        }
    }

    override operator fun iterator(): AsyncIterator<T> {
        return iter
    }

    fun end(): Boolean {
        queue.freeze()
        return baton.finish(Unit)?.finished != true
    }

    fun end(throwable: Throwable): Boolean {
        queue.freeze()
        return baton.raiseFinish(throwable)?.finished != true
    }

    open fun add(value: T): Boolean {
        return baton.toss(Unit) {
            // only add if not finished
            if (it?.finished == true)
                false
            else
                queue.add(value)
        }
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

fun AsyncIterator<ByteBuffer>.createAsyncReadFromByteBuffers(): AsyncRead {
    return read@{
        if (!hasNext())
            return@read false
        it.add(next())
        true
    }
}

fun AsyncIterator<ByteBufferList>.createAsyncReadFromByteBufferLists(): AsyncRead {
    return read@{
        if (!hasNext())
            return@read false
        it.add(next())
        true
    }
}