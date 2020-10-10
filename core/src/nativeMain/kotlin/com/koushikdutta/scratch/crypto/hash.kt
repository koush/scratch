package com.koushikdutta.scratch.crypto

import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.extensions.HashExtensions
import kotlinx.cinterop.*

private typealias hash_init<HashCtx> = () -> HashCtx
private typealias hash_update<HashCtx> = (CValuesRef<HashCtx>, data: CValuesRef<*>?, len: platform.posix.size_t) -> Int
private typealias hash_final<HashCtx> = (md: CValuesRef<UByteVar>, c: CValuesRef<HashCtx>) -> Int

private class OpenSSLHash<HashCtx: CVariable>(private val hash_update: hash_update<HashCtx>, private val hash_final: hash_final<HashCtx>, hash_init: hash_init<HashCtx>): Hash {
    private var ctx = hash_init()

    private fun ensureNotFinal() {
        if (finished)
            throw IllegalStateException("Hash.final has already been called")
    }

    override fun update(byteArray: ByteArray, offset: Int, len: Int) {
        ensureNotFinal()
        byteArray.usePinned {
            hash_update(ctx.ptr, it.addressOf(offset), len.toULong())
        }
    }

    override fun update(buffer: ByteBuffer) {
        update(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining())
    }

    private var finished = false
    override fun final(): ByteArray {
        try {
            ensureNotFinal()
            finished = true
            val ret = ByteArray(20)
            ret.usePinned {
                hash_final(it.addressOf(0).reinterpret(), ctx.ptr)
            }
            return ret
        }
        finally {
            nativeHeap.free(ctx)
        }
    }
}

internal actual fun createSha1(): Hash {
    return OpenSSLHash(::SHA1_Update, ::SHA1_Final) {
        nativeHeap.alloc()
    }
}

internal actual fun createSha256(): Hash {
    return OpenSSLHash(::SHA256_Update, ::SHA256_Final) {
        nativeHeap.alloc()
    }
}

internal actual fun createMd5(): Hash {
    return OpenSSLHash(::MD5_Update, ::MD5_Final) {
        nativeHeap.alloc()
    }
}
