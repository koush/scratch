package com.koushikdutta.scratch.http.client.middleware


import com.koushikdutta.scratch.AsyncRandomAccessInput
import com.koushikdutta.scratch.AsyncRandomAccessStorage
import com.koushikdutta.scratch.codec.hex
import com.koushikdutta.scratch.crypto.sha256
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.extensions.encode
import com.koushikdutta.scratch.extensions.hash
import com.koushikdutta.scratch.http.client.AsyncHttpExecutorBuilder
import java.io.File

private val tmpdir = System.getProperty("java.io.tmpdir")

class FileCache(val eventLoop: AsyncEventLoop, val cacheDirectory: File): Cache {
    init {
        cacheDirectory.mkdirs()
    }

    override suspend fun openRead(key: String): AsyncRandomAccessInput? {
        val entryKey = key.encodeToByteArray().hash().sha256().encode().hex()
        return eventLoop.openFile(File(cacheDirectory, entryKey))
    }

    override suspend fun openWrite(key: String): CacheStorage {
        val entryKey = key.encodeToByteArray().hash().sha256().encode().hex()
        val tmpFile = File(cacheDirectory, "$entryKey.tmp")
        val storage = eventLoop.openFile(tmpFile, true)
        return object : CacheStorage, AsyncRandomAccessStorage by storage {
            override suspend fun commit() {
                close()
                tmpFile.runCatching {
                    renameTo(File(cacheDirectory, entryKey))
                }
            }

            override suspend fun abort() {
                close()
                tmpFile.runCatching {
                    delete()
                }
            }
        }
    }

    override suspend fun remove(key: String) {
        val entryKey = key.encodeToByteArray().hash().sha256().encode().hex()
        File(cacheDirectory, entryKey).runCatching {
            delete()
        }
    }
}

fun AsyncHttpExecutorBuilder.useFileCache(cacheDirectory: File = File(tmpdir, "scratch-http-cache-" + randomHex())): AsyncHttpExecutorBuilder {
    wrapExecutor {
        CacheExecutor(it, cache = FileCache(it.eventLoop, cacheDirectory))
    }
    return this
}
