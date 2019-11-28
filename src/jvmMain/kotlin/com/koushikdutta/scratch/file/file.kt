package com.koushikdutta.scratch.file

import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.WritableBuffers
import java.io.File
import java.io.FileInputStream

interface AsyncFileRead {
    suspend fun read(buffer: WritableBuffers): Boolean
    suspend fun close()
}

suspend fun File.openAsyncRead(readSize: Int = 65536): AsyncFileRead {
    val fin = FileInputStream(this.path)
    return object : AsyncFileRead {
        override suspend fun read(buffer: WritableBuffers): Boolean {
            val readBuffer = buffer.obtain(readSize)
            val read = fin.channel.read(readBuffer)
            readBuffer.flip()
            buffer.add(readBuffer)
            return read >= 0
        }

        override suspend fun close() {
            // should this exception be eaten?
            fin.close()
        }
    }
}
