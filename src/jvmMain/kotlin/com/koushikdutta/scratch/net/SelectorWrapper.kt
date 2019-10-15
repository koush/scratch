package com.koushikdutta.scratch.net;

import java.io.Closeable
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Wrap a selector so that to ensure that a wakeup call triggers
 * a wakeup even when the selector may not be selecting.
 * This fixes race conditions around wakeup calls being invoked
 * right before the selector starts selecting, resulting in
 * the wakeup reason going unhandled: ie, a task that needs to run
 * on the selector thread.
 */
internal class SelectorWrapper(val selector: Selector) : Closeable {
    var isWaking: Boolean = false
    var semaphore = Semaphore(0)

    val isOpen: Boolean
        get() = selector.isOpen

    fun selectNow(): Int {
        return selector.selectNow()
    }

    fun select(timeout: Long = 0) {
        try {
            semaphore.drainPermits()
            selector.select(timeout)
        } finally {
            semaphore.release(Integer.MAX_VALUE)
        }
    }

    fun keys(): Set<SelectionKey> {
        return selector.keys()
    }

    fun selectedKeys(): Set<SelectionKey> {
        return selector.selectedKeys()
    }

    override fun close() {
        selector.close()
    }

    fun wakeupOnce() {
        // see if it is selecting, ie, can't acquire a permit
        val selecting = !semaphore.tryAcquire()
        selector.wakeup()
        // if it was selecting, then the wakeup definitely worked.
        if (selecting)
            return

        // now, we NEED to wait for the select to start to forcibly wake it.
        synchronized(this) {
            // check if another thread is already waiting
            if (isWaking) {
                return
            }
            isWaking = true
        }

        try {
            waitForSelect()
            selector.wakeup()
        } finally {
            isWaking = false
        }
    }

    private fun waitForSelect(): Boolean {
        // try to wake up 10 times
        for (i in 0..99) {
            try {
                if (semaphore.tryAcquire(10, TimeUnit.MILLISECONDS)) {
                    // successfully acquiring means the selector is NOT selecting, since select
                    // will drain all permits.
                    continue
                }
            } catch (e: InterruptedException) {
                // an InterruptedException means the acquire failed a select is in progress,
                // since it holds all permits
                return true
            }
        }
        return false
    }
}
