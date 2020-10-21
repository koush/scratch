package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.async.launch
import com.koushikdutta.scratch.codec.hex
import com.koushikdutta.scratch.crypto.sha256
import com.koushikdutta.scratch.extensions.encode
import com.koushikdutta.scratch.extensions.hash
import java.io.File
import kotlin.random.Random

fun interface FileStoreGetDirectory {
    operator fun invoke(): File
}

class FileStore(val eventLoop: AsyncEventLoop, hashKeys: Boolean, val getDirectory: FileStoreGetDirectory): AsyncStore, AsyncAffinity by eventLoop {
    val keyHash: (String) -> String
    lateinit var cacheDirectory: File
    init {
        keyHash = if (hashKeys) {
            {
                toSafeFilename(it)
            }
        }
        else {
            {
                it
            }
        }

        eventLoop.launch {
            cacheDirectory = getDirectory()
            cacheDirectory.mkdirs()
        }
    }

    constructor(eventLoop: AsyncEventLoop, hashKeys: Boolean, cacheDirectory: File): this(eventLoop, hashKeys, { cacheDirectory } )

    override suspend fun openRead(key: String): AsyncRandomAccessInput? {
        val filename = keyHash(key)
        return eventLoop.openFile(File(cacheDirectory, filename))
    }

    override suspend fun openWrite(key: String): AsyncStoreItem {
        val filename = keyHash(key)
        val tmpFile = File(cacheDirectory, "$filename.tmp")
        val storage = eventLoop.openFile(tmpFile, true)
        return object : AsyncStoreItem, AsyncRandomAccessStorage by storage {
            override suspend fun close() {
                storage.close()
                tmpFile.runCatching {
                    renameTo(File(cacheDirectory, filename))
                }
            }

            override suspend fun abort() {
                storage.close()
                tmpFile.runCatching {
                    delete()
                }
            }
        }
    }

    override suspend fun remove(key: String) {
        val filename = keyHash(key)
        File(cacheDirectory, filename).runCatching {
            delete()
        }
    }

    override fun exists(key: String): Boolean {
        val filename = keyHash(key)
        return File(cacheDirectory, filename).exists()
    }

    fun getTempFile(): File {
        while (true) {
            val file = File(cacheDirectory, Random.nextBytes(128 / 8).encode().hex())
            if (!file.exists())
                return file;
        }
    }

    fun getFile(key: String): File {
        val filename = keyHash(key)
        return File(cacheDirectory, filename)
    }

    override suspend fun getKeys(): Set<String> {
        return eventLoop.listFiles(cacheDirectory)
                .filter {
                    it.isFile
                }
                .sortedBy {
                    it.lastModified()
                }
                .map {
                    it.name
                }
                .toSet()
    }

    fun commitTempFile(key: String, tempFile: File) {
        tempFile.runCatching {
            renameTo(getFile(key))
        }
    }

    override fun size(key: String): Long {
        val filename = keyHash(key)
        val file = File(cacheDirectory, filename)
        try {
            return file.length()
        }
        catch (throwable: Throwable) {
            return 0L
        }
    }

    companion object {
        @JvmStatic
        fun toSafeFilename(vararg keys: Any): String {
            return keys.joinToString(":").encodeToByteArray().hash().sha256().encode().hex()
        }
    }
}