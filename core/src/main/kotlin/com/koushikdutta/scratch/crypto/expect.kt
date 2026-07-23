package com.koushikdutta.scratch.crypto

import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.extensions.HashExtensions
import java.security.MessageDigest

interface Hash {
    fun update(byteArray: ByteArray, offset: Int = 0, len: Int = byteArray.size)
    fun update(buffer: ByteBuffer)
    fun final(): ByteArray
}

fun Hash.update(buffer: ReadableBuffers) {
    val buffers = buffer.readAll()
    for (b in buffers) {
        update(b)
    }
    buffer.reclaim(*buffers)
}

class SHA1: Hash by createSha1()
class SHA256: Hash by createSha256()
class MD5: Hash by createMd5()

class MessageDigestHash(private val digest: MessageDigest): Hash {
    override fun update(byteArray: ByteArray, offset: Int, len: Int) {
        digest.update(byteArray, offset, len)
    }

    override fun update(buffer: ByteBuffer) {
        digest.update(buffer)
    }

    override fun final(): ByteArray {
        return digest.digest()
    }
}

internal fun createSha1(): Hash = MessageDigestHash(MessageDigest.getInstance("sha1"))
internal fun createSha256(): Hash = MessageDigestHash(MessageDigest.getInstance("sha-256"))
internal fun createMd5(): Hash = MessageDigestHash(MessageDigest.getInstance("md5"))

private fun HashExtensions<ByteArray>.extensionify(hash: Hash): ByteArray {
    hash.update(this.value)
    return hash.final()
}

fun HashExtensions<ByteArray>.sha1(): ByteArray = this.extensionify(createSha1())
fun HashExtensions<ByteArray>.sha256(): ByteArray = this.extensionify(createSha256())
fun HashExtensions<ByteArray>.md5(): ByteArray = this.extensionify(createMd5())
