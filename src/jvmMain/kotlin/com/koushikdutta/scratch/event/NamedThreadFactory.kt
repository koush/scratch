package com.koushikdutta.scratch.event

import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class NamedThreadFactory(private val namePrefix: String) : ThreadFactory {
    private val group: ThreadGroup
    private val threadNumber = AtomicInteger(1)

    init {
        val s = System.getSecurityManager()
        group = if (s != null)
            s.threadGroup
        else
            Thread.currentThread().threadGroup
    }

    override fun newThread(r: Runnable): Thread {
        val t = Thread(group, r,
            namePrefix + threadNumber.getAndIncrement(), 0)
        if (t.isDaemon) t.isDaemon = false
        if (t.priority != Thread.NORM_PRIORITY) {
            t.priority = Thread.NORM_PRIORITY
        }
        return t
    }

    companion object {
        fun newSynchronousWorkers(prefix: String): ExecutorService {
            val tf = NamedThreadFactory(prefix)
            return ThreadPoolExecutor(1, 4, 10L,
                    TimeUnit.SECONDS, LinkedBlockingQueue(), tf)
        }
    }
}

suspend fun ExecutorService.await() {
    suspendCoroutine<Unit> {
        submit {
            it.resume(Unit)
        }
    }
}