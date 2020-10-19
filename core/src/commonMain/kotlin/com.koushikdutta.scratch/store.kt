package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.ByteBufferList


interface AsyncStoreItem: AsyncRandomAccessStorage {
    suspend fun abort()
}

interface AsyncStore: AsyncAffinity {
    suspend fun getKeys(): Set<String>
    suspend fun openRead(key: String): AsyncRandomAccessInput?
    suspend fun openWrite(key: String): AsyncStoreItem
    suspend fun remove(key: String)
    fun exists(key: String): Boolean
    fun size(key: String): Long
    fun removeAsync(key: String) = async {
        remove(key)
    }
}

class BufferStore : AsyncStore, AsyncAffinity by AsyncAffinity.NO_AFFINITY {
    private val buffers = mutableMapOf<String, ByteBuffer>()

    override suspend fun getKeys(): Set<String> {
        return buffers.keys
    }

    override suspend fun openRead(key: String): AsyncRandomAccessInput? {
        val buffer = buffers[key]?.duplicate()
        if (buffer == null)
            return null
        return BufferStorage(ByteBufferList(ByteBufferList.deepCopyExactSize(buffer)))
    }

    override suspend fun openWrite(key: String): AsyncStoreItem {
        val storage = BufferStorage(ByteBufferList())
        return object : AsyncStoreItem, AsyncRandomAccessStorage by storage {
            override suspend fun close() {
                storage.close()
                buffers[key] = storage.deepCopyByteBuffer()
            }

            override suspend fun abort() {
            }
        }
    }

    override suspend fun remove(key: String) {
        buffers.remove(key)
    }

    override fun exists(key: String): Boolean {
        return buffers.containsKey(key)
    }

    override fun size(key: String): Long {
        return buffers[key]!!.remaining().toLong()
    }
}
