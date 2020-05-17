package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.AsyncRandomAccessStorage
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.AsynchronousFileChannel
import java.nio.channels.CompletionHandler
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

interface NIOFileFactory {
    fun open(loop: AsyncEventLoop, file: File, defaultReadLength: Int = 16384, write: Boolean = false): AsyncRandomAccessStorage

    companion object {
        val instance: NIOFileFactory

        init {
            var clazz: Class<*>?
            try {
                clazz = AsynchronousFileChannel::class.java
            }
            catch (throwable: Throwable) {
                clazz = null
            }

            if (clazz != null) {
                instance = object : NIOFileFactory {
                    override fun open(loop: AsyncEventLoop, file: File, defaultReadLength: Int, write: Boolean): AsyncRandomAccessStorage {
                        return NIOFile7(loop, file, defaultReadLength, write)
                    }
                }
            }
            else {
                instance = object : NIOFileFactory {
                    override fun open(loop: AsyncEventLoop, file: File, defaultReadLength: Int, write: Boolean): AsyncRandomAccessStorage {
                        return NIOFile6(loop, file, defaultReadLength, write)
                    }
                }
            }
        }
    }
}

private fun <T> completionHandler(continuation: Continuation<T>): CompletionHandler<T, Unit?> {
    return object : CompletionHandler<T, Unit?> {
        override fun completed(result: T, attachment: Unit?) {
            continuation.resume(result)
        }

        override fun failed(exc: Throwable?, attachment: Unit?) {
            continuation.resumeWithException(exc!!)
        }
    }
}

class NIOFile7(val server: AsyncEventLoop, file: File, var defaultReadLength: Int, write: Boolean) : AsyncRandomAccessStorage, AsyncAffinity by server {
    val fileChannel: AsynchronousFileChannel
    init {
        if (write)
            fileChannel = AsynchronousFileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
        else
            fileChannel = AsynchronousFileChannel.open(file.toPath(), StandardOpenOption.READ)
    }
    var position: Long = 0
    override suspend fun truncate(size: Long) {
        fileChannel.truncate(size)
    }

    override suspend fun write(buffer: ReadableBuffers) {
        while (buffer.hasRemaining()) {
            val b = buffer.readFirst()
            while (b.hasRemaining()) {
                val written = suspendCoroutine<Int> {
                    fileChannel.write(b, position, null, completionHandler(it))
                }

                if (written < 0)
                    throw IOException("write returned $written")

                position += written
            }
            buffer.reclaim(b)
        }
    }

    override suspend fun close() {
        fileChannel.close()
    }

    override suspend fun size(): Long {
        return fileChannel.size()
    }

    override suspend fun getPosition(): Long {
        return position
    }

    override suspend fun seekPosition(position: Long) {
        this.position = position
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        return readPosition(position, defaultReadLength.toLong(), buffer)
    }

    override suspend fun readPosition(position: Long, length: Long, buffer: WritableBuffers): Boolean {
        await()

        val toRead = min(defaultReadLength, length.toInt())
        val singleBuffer = buffer.obtain(toRead)
        singleBuffer.limit(toRead)

        val read = suspendCoroutine<Int> {
            fileChannel.read(singleBuffer, position, null, completionHandler(it))
        }

        await()

        singleBuffer.flip()
        buffer.add(singleBuffer)

        if (read < 0)
            return false

        this.position += read
        return true
    }
}

class NIOFile6(val server: AsyncEventLoop, file: File, var defaultReadLength: Int, write: Boolean) : AsyncRandomAccessStorage, AsyncAffinity by server{
    val fileChannel: FileChannel
    init {
        if (write)
            fileChannel = FileOutputStream(file).channel
        else
            fileChannel = FileInputStream(file).channel
    }
    var position: Long = 0
    override suspend fun truncate(size: Long) {
        fileChannel.truncate(size)
    }

    override suspend fun write(buffer: ReadableBuffers) {
        await()

        while (buffer.hasRemaining()) {
            val b = buffer.readFirst()
            while (b.hasRemaining()) {
                val written = fileChannel.write(b, position)

                if (written < 0)
                    throw IOException("write returned $written")

                position += written
            }
            buffer.reclaim(b)
        }
    }

    override suspend fun close() {
        fileChannel.close()
    }

    override suspend fun size(): Long {
        return fileChannel.size()
    }

    override suspend fun getPosition(): Long {
        return position
    }

    override suspend fun seekPosition(position: Long) {
        this.position = position
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        return readPosition(position, defaultReadLength.toLong(), buffer)
    }

    override suspend fun readPosition(position: Long, length: Long, buffer: WritableBuffers): Boolean {
        await()

        val read = buffer.putAllocatedBuffer(length.toInt()) { singleBuffer ->
            fileChannel.read(singleBuffer, position)
        }

        if (read < 0)
            return false

        this.position += read
        return true
    }
}