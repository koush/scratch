package com.koushikdutta.scratch.codec

import com.koushikdutta.scratch.extensions.DecodeExtensions
import com.koushikdutta.scratch.extensions.EncodeExtensions

fun EncodeExtensions<ByteArray>.hex(): String {
    return value.joinToString("") { it.toString(16).toLowerCase() }
}

fun DecodeExtensions<String>.hex(): ByteArray {
    if (value.length % 2 != 0)
        throw IllegalArgumentException("hex string must be an even number of characters")
    val cleanValue = if (value.startsWith("0x", ignoreCase = true))
        value.substring(2)
    else
        value
    return ByteArray(cleanValue.length / 2) { cleanValue.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
