package com.koushikdutta.scratch

import com.koushikdutta.scratch.atomic.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import kotlin.test.*

class FreezableTests {
    @Test
    fun testFreezable() {
        val reference = FreezableReference<Any>()
        assertNull(reference.get())

        val value = Any()
        reference.set(value)
        assertEquals(value, reference.get()!!.value)

        val other = Any()
        reference.set(other)
        assertEquals(other, reference.get()!!.value)

        assertEquals(other, reference.swap(value)!!.value)

        assertEquals(value, reference.nullSwap()!!.value)

        assertEquals(null, reference.swapIfNullElseNull(value))
        assertEquals(value, reference.swapIfNullElseNull(other)!!.value)
        assertNull(reference.get())

        assertFalse(reference.isImmutable)
        reference.freeze(value)
        assertTrue(reference.isImmutable)
        assertEquals(value, reference.get()!!.value)

        assertEquals(value, reference.freeze(other)!!.value)
        assertEquals(value, reference.get()!!.value)
    }

    @Test
    fun testStack() {
        val stack = FreezableStack<Int, Int>(0) { accumulated, value ->
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
    fun testStackFreeze() {
        val stack = FreezableStack<Int, Int>(0) { accumulated, value ->
            accumulated + value
        }
        stack.push(0)
        stack.push(1)
        stack.push(2)
        stack.push(3)
        stack.push(4)
        assertFalse(stack.isFrozen)
        assertFalse(stack.isImmutable)
        stack.freeze()
        assertTrue(stack.isFrozen)
        assertFalse(stack.isImmutable)
        assertTrue(stack.push(5).frozen)

        assertEquals(stack.pop()?.accumulate, 10)
        assertEquals(stack.pop()?.size, 3)
        assertEquals(stack.pop()?.value, 2)
        assertEquals(stack.pop()?.value, 1)
        assertTrue(stack.isFrozen)
        assertFalse(stack.isImmutable)
        assertEquals(stack.pop()?.value, 0)
        assertTrue(stack.isFrozen)
        assertTrue(stack.isImmutable)

        assertTrue(stack.pop()!!.frozen)
    }

    @Test
    fun testQueue() {
        val queue = FreezableQueue<Int>()
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
    fun testQueueFreeze() {
        val queue = FreezableQueue<Int>()
        queue.add(0)
        queue.add(1)
        queue.add(2)
        queue.add(3)
        queue.add(4)
        assertFalse(queue.isFrozen)
        assertFalse(queue.isImmutable)
        queue.freeze()
        assertTrue(queue.isFrozen)
        assertFalse(queue.isImmutable)
        assertFalse(queue.add(5))

        assertEquals(queue.remove()?.value, 0)
        assertEquals(queue.remove()?.value, 1)
        assertEquals(queue.remove()?.value, 2)
        assertEquals(queue.remove()?.value, 3)

        assertTrue(queue.isFrozen)
        assertFalse(queue.isImmutable)
        assertEquals(queue.remove()?.value, 4)
        assertTrue(queue.isFrozen)
        assertTrue(queue.isImmutable)

        assertTrue(queue.remove()!!.frozen)
    }

    @Test
    fun testBuffers() {
        val atomicBuffers = FreezableBuffers()
        val buffer = ByteBufferList()
        assertEquals(5, buffer.putUtf8String("Hello").read(atomicBuffers))
        assertEquals(10, buffer.putUtf8String("World").read(atomicBuffers))
        val got = ByteBufferList()
        atomicBuffers.read(got)
        assertEquals(got.readUtf8String(), "HelloWorld")

        buffer.putUtf8String("Hello").read(atomicBuffers)
        buffer.putUtf8String("World").read(atomicBuffers)
        atomicBuffers.read(got)
        assertEquals(got.readUtf8String(), "HelloWorld")

        buffer.putUtf8String("Hello").read(atomicBuffers)
        buffer.putUtf8String("World").read(atomicBuffers)
        atomicBuffers.read(got)
        assertEquals(got.readUtf8String(), "HelloWorld")

        buffer.putUtf8String("Hello").read(atomicBuffers)
        buffer.putUtf8String("World").read(atomicBuffers)
        assertFalse(atomicBuffers.isFrozen)
        assertFalse(atomicBuffers.isImmutable)
        atomicBuffers.freeze()
        assertTrue(atomicBuffers.isFrozen)
        assertFalse(atomicBuffers.isImmutable)

        atomicBuffers.read(got)
        assertTrue(atomicBuffers.isFrozen)
        assertTrue(atomicBuffers.isImmutable)

        assertEquals(got.readUtf8String(), "HelloWorld")

        buffer.putUtf8String("Hello").read(atomicBuffers)
        buffer.putUtf8String("World").read(atomicBuffers)
        assertTrue(atomicBuffers.isFrozen)
        assertTrue(atomicBuffers.isImmutable)
        atomicBuffers.read(got)
        assertTrue(atomicBuffers.isFrozen)
        assertTrue(atomicBuffers.isImmutable)
        assertEquals(got.readUtf8String(), "")

        assertEquals(0, atomicBuffers.remaining)
    }
}