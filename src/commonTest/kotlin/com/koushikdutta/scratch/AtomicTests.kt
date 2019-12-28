package com.koushikdutta.scratch

import com.koushikdutta.scratch.atomic.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class AtomicTests {
    @Test
    fun testStack() {
        val stack = AtomicStack<Int, Int>(0) { accumulated, value ->
            accumulated + value
        }
        stack.push(0)
        stack.push(1)
        stack.push(2)
        stack.push(3)
        stack.push(4)

        assertEquals(stack.pop()?.accumulate, 10)
        assertEquals(stack.pop()?.size, 3)
        assertEquals(stack.pop()?.value, 2)
        assertEquals(stack.pop()?.value, 1)
        assertEquals(stack.pop()?.value, 0)

        assertEquals(stack.pop(), null)
    }

    @Test
    fun testQueue() {
        val queue = AtomicQueue<Int>()
        queue.add(0)
        queue.add(1)
        queue.add(2)
        queue.add(3)
        queue.add(4)

        assertEquals(queue.remove()?.value, 0)
        assertEquals(queue.remove()?.value, 1)
        assertEquals(queue.remove()?.value, 2)
        assertEquals(queue.remove()?.value, 3)
        assertEquals(queue.remove()?.value, 4)

        assertEquals(queue.remove(), null)
    }

    @Test
    fun testBuffers() {
        val atomicBuffers = AtomicBuffers()
        val buffer = ByteBufferList()
        buffer.putUtf8String("Hello").read(atomicBuffers)
        buffer.putUtf8String("World").read(atomicBuffers)
        val got = atomicBuffers.read()
        assertEquals(got.readUtf8String(), "HelloWorld")
        atomicBuffers.reclaim(got)

        buffer.putUtf8String("Hello").read(atomicBuffers)
        buffer.putUtf8String("World").read(atomicBuffers)
        val got2 = atomicBuffers.read()
        assertEquals(got2.readUtf8String(), "HelloWorld")
        assertEquals(got, got2)

        buffer.putUtf8String("Hello").read(atomicBuffers)
        buffer.putUtf8String("World").read(atomicBuffers)
        val got3 = atomicBuffers.read()
        assertEquals(got3.readUtf8String(), "HelloWorld")
        assertNotEquals(got, got3)

        assertEquals(0, atomicBuffers.remaining)
    }
}