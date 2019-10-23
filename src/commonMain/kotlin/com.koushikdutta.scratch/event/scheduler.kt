package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.synchronized
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

typealias AsyncServerRunnable = () -> Unit

private typealias PriorityQueue = ArrayList<Scheduled>

private fun PriorityQueue.addSorted(scheduled: Scheduled) {
    this.add(scheduled)
    sortWith(Scheduler.INSTANCE)
}

abstract class AsyncScheduler<S : AsyncScheduler<S>> : AsyncAffinity {
    private var postCounter = 0
    internal val mQueue = arrayListOf<Scheduled>()

    protected val isQueueEmpty: Boolean
        get() = mQueue.isEmpty()

    protected fun lockAndRunQueue(): Long {
        var wait = QUEUE_EMPTY

        // find the first item we can actually run
        while (true) {
            var run: Scheduled? = null

            synchronized(this) {
                val now = milliTime()

                if (mQueue.size > 0) {
                    val s = mQueue.removeAt(0)
                    if (s.time <= now) {
                        run = s
                    } else {
                        wait = s.time - now
                        mQueue.add(0, s)
                    }
                }
            }

            if (run == null)
                break

            run!!.runnable()
        }

        postCounter = 0
        return wait
    }

    fun postDelayed(delay: Long, runnable: AsyncServerRunnable): Cancellable {
        return synchronized(this) {
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
                time = min(0, mQueue[0].time - 1)
            else
                time = 0
            val s = Scheduled(this, runnable, time)
            mQueue.addSorted(s)
            wakeup()
            s
        }
    }

    protected fun scheduleShutdown(runnable: AsyncServerRunnable) {
        synchronized(this) {
            mQueue.clear()
            mQueue.add(Scheduled(this, {
                mQueue.clear()
                runnable()
            }, 0))
        }
    }

    suspend fun sleep(milliseconds: Long) {
        require(milliseconds >= 0) { "negative sleep not allowed" }
        suspendCoroutine<Unit> {
            postDelayed(milliseconds) {
                it.resume(Unit)
            }
        }
    }

    override suspend fun post() {
        suspendCoroutine<Unit> {
            post {
                it.resume(Unit)
            }
        }
    }

    override suspend fun await() {
        if (isAffinityThread)
            return
        post()
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
