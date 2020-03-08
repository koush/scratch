package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.ByteBufferList

class CrappyDigest {
    private var digest: Long = 0
    private var count = 0
    fun update(byteArray: ByteArray, startIndex: Int = 0, endIndex: Int = byteArray.size): CrappyDigest {
        for (b in byteArray.sliceArray(IntRange(startIndex, endIndex - 1))) {
            digest = digest xor (b.toLong() shl (count % (64 - 8)))
            count++
        }
        return this
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