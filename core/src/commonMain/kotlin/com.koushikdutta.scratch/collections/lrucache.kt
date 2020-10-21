package com.koushikdutta.scratch.collections

import com.koushikdutta.scratch.AsyncRandomAccessInput
import com.koushikdutta.scratch.AsyncStore
import com.koushikdutta.scratch.AsyncStoreItem
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

private class LruStoreItem(val cache: LruCache, val key: String, val initialSize: Long, val storeItem: AsyncStoreItem): AsyncStoreItem by storeItem {
    override suspend fun close() {
        storeItem.close()
        cache.updateSize(key, size() - initialSize)
    }
}

class LruCache(val store: AsyncStore, val maxSize: Long): AsyncStore by store {
    var currentSize = 0L
        private set

    private val sortedKeys = linkedSetOf<String>()
    private val computeInitialSize = GlobalScope.launch(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
        await()
        for (key in getKeys()) {
            currentSize += size(key)
            sortedKeys.add(key)

        }
    }

    internal suspend fun updateSize(key: String, delta: Long) {
        await()
        currentSize += delta
        sortedKeys.remove(key)
        sortedKeys.add(key)

        while (currentSize > maxSize && !sortedKeys.isEmpty()) {
            val removeKey = sortedKeys.first()
            sortedKeys.remove(removeKey)
            val removeSize = size(removeKey)
            remove(removeKey)
            currentSize -= removeSize
        }
    }

    override suspend fun openWrite(key: String): AsyncStoreItem {
        computeInitialSize.join()

        val storage = store.openWrite(key)
        val initialSize = storage.size()
        return LruStoreItem(this, key, initialSize, storage)
    }

    override suspend fun openRead(key: String): AsyncRandomAccessInput? {
        val read = store.openRead(key)
        if (read == null)
            return null
        sortedKeys.remove(key)
        sortedKeys.add(key)
        return read
    }
}
