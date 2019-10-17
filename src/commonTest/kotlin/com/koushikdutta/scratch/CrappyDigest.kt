package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList

class CrappyDigest {
    var digest: Long = 0
    var count = 0
    fun update(byteArray: ByteArray) {
        for (b in byteArray) {
            digest = digest xor (b.toLong() shl (count % (64 - 8)))
            count++
        }
    }
    fun digest(): ByteArray {
        val b = ByteBufferList()
        b.putLong(digest)
        return b.readBytes()
    }

    companion object {
        fun getInstance(): CrappyDigest{
            return CrappyDigest()
        }
    }
}