package com.koushikdutta.scratch

import android.os.Handler
import android.os.Looper
import com.koushikdutta.scratch.event.AsyncServerRunnable
import com.koushikdutta.scratch.event.Scheduler
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

fun Looper.createScheduler(): Scheduler =  Handler(this).createScheduler()

private const val WAITING = 0
private const val RUNNING = 1
private const val CANCELLED = 2
private const val FINISHED = 3


private fun Handler.postInternal(runnable: AsyncServerRunnable, post: Handler.() -> Unit): Cancellable {
    val started = AtomicInteger()
    var cb: Runnable? = Runnable {
        if (started.getAndSet(RUNNING) != WAITING)
            return@Runnable
        try {

        }
        finally {
            started.set(FINISHED)
        }
        runnable()
    }

    post(this)

    return object : Cancellable {
        override val isDone: Boolean
            get() = started.get() == CANCELLED || started.get() == FINISHED
        override val isCancelled: Boolean
            get() = started.get() == CANCELLED

        override fun cancel(): Boolean {
            val state = started.getAndSet(CANCELLED)
            if (state == WAITING || state == CANCELLED) {
                val copy = cb
                cb = null
                if (copy != null)
                    removeCallbacks(copy)
                return true
            }
            return false
        }
    }
}

fun Handler.createScheduler(): Scheduler {
    val handler = this

    return object : Scheduler {
        override val isAffinityThread: Boolean
            get() = handler.looper.thread == Thread.currentThread()


        override fun post(runnable: AsyncServerRunnable) = postInternal(runnable) {
            post {
                runnable()
            }
        }

        override fun postDelayed(delayMillis: Long, runnable: AsyncServerRunnable) = postInternal(runnable) {
            postDelayed({
                runnable()
            }, delayMillis)
        }
    }
}