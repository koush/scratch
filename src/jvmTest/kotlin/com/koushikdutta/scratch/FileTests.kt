package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.file.openAsyncRead
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertEquals

class FileTests {
    @Test
    fun testFile() {
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
        async {
            val fread = temp.openAsyncRead()
            val buffer = ByteBufferList()
            while (fread.read(buffer)) {
                digest.update(buffer.readBytes())
            }
            hash = digest.digest().joinToString("") { "%02X".format(it) }
            fread.close()
        }

        assertEquals(hash, "2B4E371D0133CCA2479EC859AC10E741")
    }
}