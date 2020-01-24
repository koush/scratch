package com.koushikdutta.scratch.crypto

import com.koushikdutta.scratch.extensions.HashExtensions

interface Hash {
    fun update(byteArray: ByteArray)
    fun final(): ByteArray
}

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
