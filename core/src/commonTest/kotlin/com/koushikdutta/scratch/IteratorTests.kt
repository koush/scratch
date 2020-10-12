package com.koushikdutta.scratch

import kotlin.test.*

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
    fun testIterator2() {
        val iter = asyncIterator<Int> {
            yield(3)
            yield(4)
            yield(5)
        }

        var sum = 0
        async {
            sum += iter.next()
            sum += iter.next()
            sum += iter.next()
            assertFalse(iter.hasNext())
            assertFalse(iter.hasNext())
        }
        assertEquals(sum, 12)
    }


    @Test
    fun testIterator3() {
        var setValue = 4
        val iter = asyncIterator<Int> {
            yield(3)
            yield(4)
            yield(5)
            setValue = 6
        }

        var sum = 0
        async {
            sum += iter.next()
            sum += iter.next()
            sum += iter.next()
            assertFalse(iter.hasNext())
            assertFalse(iter.hasNext())
        }
        assertEquals(sum, 12)
        assertEquals(setValue, 6)
    }

    @Test
    fun testIteratorEnd() {
        val iter = asyncIterator<Int> {
            yield(3)
            yield(4)
            yield(5)
        }

        var sum = 0
        async {
            sum += iter.next()
            sum += iter.next()
            sum += iter.next()
            try {
                sum += iter.next()
            }
            catch (no: NoSuchElementException) {
                return@async
            }
            fail("Exception expected")
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
            .getCompleted()
        }
        catch (exception: Exception) {
            assertEquals(sum, 12)
            return
        }
        fail("exception expected")
    }

    @Test
    fun testArrayDequeue() {
        val queue = AsyncQueue<Int>()
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
        val queue = AsyncQueue<Int>()
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
            .getCompleted()
        }
        catch (exception: Exception) {
            assertEquals(sum, 12)
            return
        }
        fail("exception expected")
    }

    @Test
    fun testIteratorFinish() {
        val queue = AsyncQueue<Int>()
        queue.end(IOException("end should not throw"))
        queue.end()
        queue.end(IOException("end(Throwable) should not throw"))
    }
}