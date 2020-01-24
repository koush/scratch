package com.koushikdutta.scratch.extensions

class HashExtensions<T>(val value: T)
class EncodeExtensions<T>(val value: T)
class DecodeExtensions<T>(val value: T)

fun ByteArray.hash() = HashExtensions(this)
fun ByteArray.encode() = EncodeExtensions(this)
fun String.decode() = DecodeExtensions(this)
