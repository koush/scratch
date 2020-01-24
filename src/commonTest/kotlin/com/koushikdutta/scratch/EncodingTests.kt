package com.koushikdutta.scratch

import com.koushikdutta.scratch.codec.base64
import com.koushikdutta.scratch.codec.hex
import com.koushikdutta.scratch.crypto.md5
import com.koushikdutta.scratch.crypto.sha1
import com.koushikdutta.scratch.crypto.sha256
import com.koushikdutta.scratch.extensions.decode
import com.koushikdutta.scratch.extensions.encode
import com.koushikdutta.scratch.extensions.hash
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class EncodingTests{
    @Test
    fun testStuff() {
        assertEquals("hello", "hello".encodeToByteArray().encode().base64().decode().base64().decodeToString())
        assertEquals("hello", "hello".encodeToByteArray().encode().hex().decode().hex().decodeToString())

        val data = Random.nextBytes(100000)
        val data2 = data.encode().base64().decode().base64()

        assertEquals(data.hash().sha1().encode().hex(), data2.hash().sha1().encode().hex())
        assertEquals(data.hash().sha256().encode().hex(), data2.hash().sha256().encode().hex())
        assertEquals(data.hash().md5().encode().hex(), data2.hash().md5().encode().hex())
    }
}
