package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.Cancellable
import com.koushikdutta.scratch.synchronized
import kotlinx.coroutines.CompletableDeferred
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

typealias AsyncServerRunnable = () -> Unit

internal class PriorityQueue {
    private var sorted = true
    private fun sortIfNeeded() {
        if (sorted)
            return
        queue.sortWith(Scheduler.INSTANCE)
        sorted = true
    }

    private val queue = arrayListOf<Scheduled>()
    fun add(scheduled: Scheduled) {
        queue.add(scheduled)
        sorted = false
    }
    fun addFirst(scheduled: Scheduled) {
        queue.add(0, scheduled)
        sorted = false
    }
    fun removeFirst(): Scheduled {
        sortIfNeeded()
        return queue.removeAt(0)
    }
    fun peek(): Scheduled {
        sortIfNeeded()
        return queue.first()
    }
    fun clear() {
        sorted = true
        queue.clear()
    }
    fun contains(scheduled: Scheduled) = queue.contains(scheduled)
    fun remove(scheduled: Scheduled) = queue.remove(scheduled)
    fun isEmpty() = queue.isEmpty()
    val size: Int
        get() = queue.size
}

interface ScratchRunnable {
    operator fun invoke();
}

abstract class AsyncScheduler<S : AsyncScheduler<S>> : AsyncAffinity {
    private var postCounter = 0
    internal val mQueue = PriorityQueue()

    protected val isQueueEmpty: Boolean
        get() = mQueue.isEmpty()

    private val lockQueue = arrayListOf<Scheduled>()
    protected fun lockAndRunQueue(): Long {
        val now = milliTime()
        synchronized (this) {
            while (mQueue.size > 0) {
                val s = mQueue.removeFirst()
                if (s.time > now) {
                    mQueue.addFirst(s)
                    break
                }
                lockQueue.add(s)
            }
        }
        while (!lockQueue.isEmpty()) {
            val run = lockQueue.removeAt(0)
            run.runnable()
        }
        lockQueue.clear()

        postCounter = 0
        synchronized (this) {
            if (mQueue.size > 0) {
                val s = mQueue.removeFirst()
                mQueue.addFirst(s)
                return max(QUEUE_NEXT_LOOP, s.time - now)
            }
            return QUEUE_EMPTY
        }
    }

    fun postDelayed(delay: Long, runnable: ScratchRunnable): Cancellable {
        return postDelayed(delay) {
            runnable()
        }
    }

    fun postDelayed(delay: Long, runnable: AsyncServerRunnable): Cancellable {
        return synchronized(this) {
            if (stopping)
                return Cancellable.CANCELLED

            // Calculate when to run this queue item:
            // If there is a delay (non-zero), add it to the current time
            // When delay is zero, ensure that this follows all other
            // zero-delay queue items. This is done by setting the
            // "time" to the queue size. This will make sure it is before
            // all time-delayed queue items (for all real world scenarios)
            // as it will always be less than the current time and also remain
            // behind all other immediately run queue items.
            val time: Long
            if (delay > 0)
                time = milliTime() + delay
            else if (delay == 0L)
                time = postCounter++.toLong()
            else if (mQueue.size > 0)
                time = min(0, mQueue.peek().time - 1)
            else
                time = 0
            val s = Scheduled(this, runnable, time)
            mQueue.add(s)
            wakeup()
            s
        }
    }

    private var stopping = false
    protected fun scheduleShutdown(runnable: AsyncServerRunnable) {
        synchronized(this) {
            stopping = true
            mQueue.clear()
            lockQueue.clear()
            mQueue.add(Scheduled(this, {
                try {
                    runnable()
                }
                finally {
                    mQueue.clear()
                    lockQueue.clear()
                    stopping = false
                }
            }, 0))
        }
    }

    suspend fun sleep(milliseconds: Long) {
        require(milliseconds >= 0) { "negative sleep not allowed" }
        val deferred = CompletableDeferred<Unit>()
        val cancel = postDelayed(milliseconds) {
            deferred.complete(Unit)
        }
        try {
            deferred.await()
        }
        catch (throwable: CancellationException) {
            cancel.cancel()
            throw throwable
        }
    }

    override suspend fun post() {
        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { it: Continuation<Unit> ->
            post {
                it.resume(Unit)
            }
            kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
        }
    }

    override suspend fun await() {
        if (isAffinityThread)
            return
        post()
        if (!isAffinityThread) {
            val err = "Failed to switch to affinity thread, how did this happen? Use Dispatchers.Unconfirmed when creating your coroutine. Or use AsyncEventLoop.async or AsyncEventLoop.launch."
            println(err)
            throw IllegalStateException(err)
        }
    }

    abstract fun wakeup()
    abstract val isAffinityThread: Boolean

    fun postImmediate(runnable: AsyncServerRunnable): Cancellable? {
        if (isAffinityThread) {
            runnable()
            return null
        }
        return postDelayed(-1, runnable)
    }

    fun post(runnable: AsyncServerRunnable): Cancellable {
        return postDelayed(0, runnable)
    }

    companion object {
        const val QUEUE_EMPTY = Long.MAX_VALUE
        const val QUEUE_NEXT_LOOP = 0L
    }
}

internal class Scheduler private constructor() : Comparator<Scheduled> {
    override fun compare(s1: Scheduled, s2: Scheduled): Int {
        // keep the smaller ones at the head, so they get tossed out quicker
        if (s1.time == s2.time)
            return 0
        return if (s1.time > s2.time) 1 else -1
    }

    companion object {
        var INSTANCE = Scheduler()
    }
}

internal class Scheduled(val server: AsyncScheduler<*>, val runnable: AsyncServerRunnable, val time: Long) :
        Cancellable {
    private var cancelled: Boolean = false

    override val isDone: Boolean
        get() {
            return synchronized(server) {
                !cancelled && !server.mQueue.contains(this)
            }
        }

    override val isCancelled: Boolean
        get() {
            return cancelled
        }

    override fun cancel(): Boolean {
        return synchronized(server) {
            cancelled = server.mQueue.remove(this)
            cancelled
        }
    }
}
