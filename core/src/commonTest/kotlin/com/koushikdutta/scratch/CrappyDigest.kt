package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.crypto.SHA1

class CrappyDigest {
    val sha1 = SHA1()
    fun update(byteArray: ByteArray, startIndex: Int = 0, endIndex: Int = byteArray.size): CrappyDigest {
        sha1.update(byteArray, startIndex, endIndex - startIndex)
        return this
    }

    fun digest(): ByteArray {
        val b = ByteBufferList()
        b.add(sha1.final())
        return b.readBytes()
    }

    companion object {
        fun getInstance(): CrappyDigest{
            return CrappyDigest()
        }
    }
}