package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.event.AsyncEventLoop
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertEquals

class FileTests {
    @Test
    fun testFileRead() {
        val loop = AsyncEventLoop()
        val temp = File.createTempFile("FileTests", ".tmp")
        temp.deleteOnExit()
        val sr = Random(55555)
        val fout = FileOutputStream(temp)
        val br = ByteArray(10000000)
        sr.nextBytes(br)
        fout.write(br)
        fout.close()

        var hash = ""
        val digest = MessageDigest.getInstance("MD5")
        loop.async {
            val fread = openFile(temp, StandardOpenOption.READ)
            val buffer = ByteBufferList()
            while (fread.read(buffer)) {
                digest.update(buffer.readBytes())
            }
            hash = digest.digest().joinToString("") { "%02X".format(it) }
            fread.close()

            loop.stop()
        }

        loop.run()

        assertEquals(hash, "2B4E371D0133CCA2479EC859AC10E741")
    }

    @Test
    fun testFileWrite() {
        val loop = AsyncEventLoop()
        val temp = File.createTempFile("FileTests", ".tmp")
        temp.deleteOnExit()
        val sr = Random(55555)

        var hash = ""
        val digest = MessageDigest.getInstance("MD5")
        loop.async {
            val fwrite = openFile(temp, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            val br = ByteArray(10000000)
            sr.nextBytes(br)
            fwrite.write(ByteBufferList(br))
            fwrite.close()

            val fread = openFile(temp, StandardOpenOption.READ)
            val buffer = ByteBufferList()
            while (fread.read(buffer)) {
                digest.update(buffer.readBytes())
            }
            hash = digest.digest().joinToString("") { "%02X".format(it) }
            fread.close()

            loop.stop()
        }

        loop.run()

        assertEquals(hash, "2B4E371D0133CCA2479EC859AC10E741")
    }


    @Test
    fun testFileWritePositions() {
        val loop = AsyncEventLoop()
        val temp = File.createTempFile("FileTests", ".tmp")
        temp.deleteOnExit()
        val sr = Random(55555)

        var hash = ""
        val digest = MessageDigest.getInstance("MD5")
        loop.async {
            val fwrite = openFile(temp, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
            val br = ByteArray(10000000)
            sr.nextBytes(br)
            fwrite.writePosition(0, ByteBufferList(br))
            fwrite.close()

            val fread = openFile(temp, StandardOpenOption.READ)
            val buffer = ByteBufferList()
            while (fread.read(buffer)) {
                digest.update(buffer.readBytes())
            }
            hash = digest.digest().joinToString("") { "%02X".format(it) }
            fread.close()

            loop.stop()
        }

        loop.run()

        assertEquals(hash, "2B4E371D0133CCA2479EC859AC10E741")
    }
}