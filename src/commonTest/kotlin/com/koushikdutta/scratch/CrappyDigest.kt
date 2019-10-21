package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList

class CrappyDigest {
    var digest: Long = 0
    var count = 0
    fun update(byteArray: ByteArray, startIndex: Int = 0, endIndex: Int = byteArray.size) {
        for (b in byteArray.sliceArray(IntRange(startIndex, endIndex - 1))) {
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