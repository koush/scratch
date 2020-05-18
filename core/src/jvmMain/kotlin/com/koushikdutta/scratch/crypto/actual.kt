package com.koushikdutta.scratch.crypto

import java.security.MessageDigest

class MessageDigestHash(private val digest: MessageDigest): Hash {
    override fun update(byteArray: ByteArray, offset: Int, len: Int) {
        digest.update(byteArray, offset, len)
    }

    override fun final(): ByteArray {
        return digest.digest()
    }
}

internal actual fun createSha1(): Hash = MessageDigestHash(MessageDigest.getInstance("sha1"))
internal actual fun createSha256(): Hash = MessageDigestHash(MessageDigest.getInstance("sha-256"))
internal actual fun createMd5(): Hash = MessageDigestHash(MessageDigest.getInstance("md5"))
