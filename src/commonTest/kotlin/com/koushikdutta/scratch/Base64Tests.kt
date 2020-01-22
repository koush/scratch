package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.createByteBufferList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class Base64Tests{
    @Test
    fun testBase64() {
        assertEquals("hello", "hello".encodeBase64ToByteArray().decodeBase64ToString())

        val data = Random.nextBytes(100000)
        val data2 = data.encodeBase64ToString().decodeBase64ToByteArray()

        assertEquals(CrappyDigest.getInstance().update(data).digest().createByteBufferList().readLong(), CrappyDigest.getInstance().update(data2).digest().createByteBufferList().readLong())
    }
}