package com.koushikdutta.scratch.http.client.executor


import com.koushikdutta.scratch.AsyncAffinity
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

class FileStore(val eventLoop: AsyncEventLoop, val cacheDirectory: File): AsyncStore, AsyncAffinity by eventLoop {
    init {
        cacheDirectory.mkdirs()
    }

    override suspend fun openRead(key: String): AsyncRandomAccessInput? {
        val filename = toSafeFilename(key)
        return eventLoop.openFile(File(cacheDirectory, filename))
    }

    override suspend fun openWrite(key: String): AsyncStorage {
        val filename = toSafeFilename(key)
        val tmpFile = File(cacheDirectory, "$filename.tmp")
        val storage = eventLoop.openFile(tmpFile, true)
        return object : AsyncStorage, AsyncRandomAccessStorage by storage {
            override suspend fun commit() {
                close()
                tmpFile.runCatching {
                    renameTo(File(cacheDirectory, filename))
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
        val filename = toSafeFilename(key)
        File(cacheDirectory, filename).runCatching {
            delete()
        }
    }

    override fun exists(key: String): Boolean {
        val filename = toSafeFilename(key)
        return File(cacheDirectory, filename).exists()
    }

    companion object {
        @JvmStatic
        fun toSafeFilename(key: String): String {
            return key.encodeToByteArray().hash().sha256().encode().hex()
        }
    }
}

fun AsyncHttpExecutorBuilder.useFileCache(eventLoop: AsyncEventLoop = AsyncEventLoop.default, cacheDirectory: File = File(tmpdir, "scratch-http-cache-" + randomHex())): AsyncHttpExecutorBuilder {
    wrapExecutor {
        CacheExecutor(it, asyncStore = FileStore(eventLoop, cacheDirectory))
    }
    return this
}
