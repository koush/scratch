package com.koushikdutta.scratch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class IteratorTests {
    @Test
    fun testIterator() {
        val iter = createAsyncIterable<Int> {
            yield(3)
            yield(4)
            yield(5)
        }

        var sum = 0
        async {
            for (i in iter) {
                sum += i
            }
        }
        assertEquals(sum, 12)
    }

    @Test
    fun testIteratorException() {
        val iter = createAsyncIterable<Int> {
            yield(3)
            yield(4)
            yield(5)
            throw Exception()
        }

        var sum = 0
        try {
            async {
                for (i in iter) {
                    sum += i
                }
            }
        }
        catch (exception: Exception) {
            assertEquals(sum, 12)
            return
        }
        fail("exception expected")
    }

    @Test
    fun testArrayDequeue() {
        val queue = AsyncDequeueIterator<Int>()
        queue.add(3)
        queue.add(4)
        queue.add(5)

        var sum = 0
        async {
            for (i in queue) {
                sum += i
            }
        }
        assertEquals(sum, 12)
    }


    @Test
    fun testArrayDequeueException() {
        val queue = AsyncDequeueIterator<Int>()
        queue.add(3)
        queue.add(4)
        queue.add(5)
        queue.end(Exception())

        var sum = 0
        try {
            async {
                for (i in queue) {
                    sum += i
                }
            }
        }
        catch (exception: Exception) {
            assertEquals(sum, 12)
            return
        }
        fail("exception expected")
    }

}