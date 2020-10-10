package com.koushikdutta.scratch.crypto

import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.extensions.HashExtensions

interface Hash {
    fun update(byteArray: ByteArray, offset: Int = 0, len: Int = byteArray.size)
    fun update(buffer: ByteBuffer)
    fun final(): ByteArray
}

class SHA1: Hash by createSha1()
class SHA256: Hash by createSha256()
class MD5: Hash by createMd5()

internal expect fun createSha1(): Hash
internal expect fun createSha256(): Hash
internal expect fun createMd5(): Hash

private fun HashExtensions<ByteArray>.extensionify(hash: Hash): ByteArray {
    hash.update(this.value)
    return hash.final()
}

fun HashExtensions<ByteArray>.sha1(): ByteArray = this.extensionify(createSha1())
fun HashExtensions<ByteArray>.sha256(): ByteArray = this.extensionify(createSha256())
fun HashExtensions<ByteArray>.md5(): ByteArray = this.extensionify(createMd5())
