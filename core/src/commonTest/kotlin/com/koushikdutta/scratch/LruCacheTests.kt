package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.collections.LruCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LruCacheTests {
    @Test
    fun testCache() = asyncTest(11) {
        val lru = LruCache(BufferStore(), 35)

        val entry = lru.openWrite("test")
        entry::write.drain("hello world".createByteBufferList())
        entry.close()

        lru.currentSize
    }

    @Test
    fun testCacheMaxSize() = asyncTest(22) {
        val lru = LruCache(BufferStore(), 30)

        val entry = lru.openWrite("test")
        entry::write.drain("hello world".createByteBufferList())
        entry.close()

        val entry2 = lru.openWrite("test2")
        entry2::write.drain("hello world".createByteBufferList())
        entry2.close()

        val entry3 = lru.openWrite("test3")
        entry3::write.drain("hello world".createByteBufferList())
        entry3.close()

        val keys = lru.getKeys()
        assertEquals(keys.size, 2)
        assertTrue(keys.contains("test2"))
        assertTrue(keys.contains("test3"))

        lru.currentSize
    }


    @Test
    fun testCacheReadRecent() = asyncTest(22) {
        val lru = LruCache(BufferStore(), 30)

        val entry = lru.openWrite("test")
        entry::write.drain("hello world".createByteBufferList())
        entry.close()

        val entry2 = lru.openWrite("test2")
        entry2::write.drain("hello world".createByteBufferList())
        entry2.close()

        lru.openRead("test")!!.close()

        val entry3 = lru.openWrite("test3")
        entry3::write.drain("hello world".createByteBufferList())
        entry3.close()

        val keys = lru.getKeys()
        assertEquals(keys.size, 2)
        assertTrue(keys.contains("test"))
        assertTrue(keys.contains("test3"))

        lru.currentSize
    }
}
