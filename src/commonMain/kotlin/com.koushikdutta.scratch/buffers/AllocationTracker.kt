package com.koushikdutta.scratch.buffers

import kotlin.math.max
import kotlin.math.min


/**
 * Track allocations from inconsistent sources, like the network
 * to choose somewhat optimal buffer sizes based on throughput.
 */
class AllocationTracker {
    var lastAlloc = 0
    var minAlloc = 1024
        get() = max(field, 1024)
    var currentAlloc = 0
    var maxAlloc = ByteBufferList.MAX_ITEM_SIZE
    var overflowDivisor = 4

    /**
     * Get the current recommended allocation size.
     * Calling trackAllocationUsed between calls of requestNextAllocation is recommended.
     * Call finishTracking when the the current cycle of allocations is complete.
     */
    fun requestNextAllocation(): Int {
        // get the current recommended allocation based on the total allocations, which is
        // the greater of the allocations used last cycle versus how much has been used this cycle.
        // trackDataNeeded
        val recommendedAllocation = max(lastAlloc + lastAlloc / overflowDivisor, currentAlloc)
        // don't allow the current allocation size to grow out of control
        val cappedAllocation = min(maxAlloc, recommendedAllocation)
        // respect the minimum allocation
        val ret = max(cappedAllocation, minAlloc)
        return ret
    }

    /**
     * Immediately after receiving data, track the amount received.
     */
    fun trackDataUsed(length: Int) {
        currentAlloc += length
    }

    /**
     * Signal that the data source has been exhausted this cycle, so that subsequent
     * calls to requestNextAllocation can return an optimal amount.
     */
    fun finishTracking() {
        // when finish tracking is called, save the current cycle's total allocations, so that
        // total amount can be allocated on the first go next time.
        lastAlloc = currentAlloc
        currentAlloc = 0
        // also reset the minAlloc
        minAlloc = 1024
    }
}

