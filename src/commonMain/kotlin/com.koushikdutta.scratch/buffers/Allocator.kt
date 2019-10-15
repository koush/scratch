package com.koushikdutta.scratch.buffers

import kotlin.math.max
import kotlin.math.min


/**
 * Created by koush on 6/28/14.
 */
class Allocator {
    val maxAlloc: Int
    internal var currentAlloc = 0
    internal var minAlloc = 2 shl 11

    constructor(maxAlloc: Int) {
        this.maxAlloc = maxAlloc
    }

    constructor() {
        maxAlloc = ByteBufferList.MAX_ITEM_SIZE
    }

    fun allocate(alloc: Int = currentAlloc): ByteBuffer {
        return ByteBufferList.obtain(min(max(alloc, minAlloc), maxAlloc))
    }

    fun track(read: Long) {
        currentAlloc = read.toInt() * 2
    }

    fun setCurrentAlloc(currentAlloc: Int) {
        this.currentAlloc = currentAlloc
    }

    fun getMinAlloc(): Int {
        return minAlloc
    }

    fun setMinAlloc(minAlloc: Int): Allocator {
        this.minAlloc = max(0, minAlloc)
        return this
    }
}

