package com.koushikdutta.scratch.http.client.executor


import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.FileStore
import com.koushikdutta.scratch.http.client.AsyncHttpExecutorBuilder
import java.io.File

private val tmpdir = System.getProperty("java.io.tmpdir")

fun AsyncHttpExecutorBuilder.useFileCache(eventLoop: AsyncEventLoop = AsyncEventLoop.default, cacheDirectory: File = File(tmpdir, "scratch-http-cache-" + randomHex()), maxSize: Long? = null) =
        useCache(FileStore(eventLoop, true, cacheDirectory), maxSize)
