package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.Cancellable

typealias ValuesCallback<T> = (values: Collection<T>) -> Unit

class Throttle<T>(val loop: Scheduler, val milliseconds: Int, private val callback: ValuesCallback<T>) {
    val values = mutableListOf<T>()
    var cancellable: Cancellable? = null
    private fun runQueue() {
        cancellable = null
        val copy = mutableListOf<T>()
        copy.addAll(values)
        values.clear()
        callback(copy)
    }

    fun post(value: T) {
        loop.post {
            values.add(value)
            if (cancellable == null)
                cancellable = loop.postDelayed(milliseconds.toLong(), this::runQueue)
        }
    }
}
