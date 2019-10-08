package com.koushikdutta.scratch.http.http2

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.Charset

typealias BufferedSource = ReadableBuffers
typealias BufferedSink = WritableBuffers
typealias Buffer = ByteBufferList

class ByteString {
    val bytes: ByteArray
    val string: String
    constructor(bytes: ByteArray, charset: Charset) {
        this.bytes = bytes
        this.string = String(bytes, charset)
    }
    constructor(string: String, charset: Charset) {
        this.bytes = string.toByteArray(charset)
        this.string = string
    }
    val size: Int
        get() = bytes.size

    operator fun get(index: Int): Byte = bytes[index]

    internal fun hex(): String {
        return bytes.joinToString { String.format("%02X", it) }
    }

    fun startsWith(other: ByteString): Boolean {
        return string.startsWith(other.string)
    }

    companion object {
        val EMPTY = ByteString("", Charsets.US_ASCII)
    }

    override fun equals(other: Any?): Boolean {
        return (other as? ByteString)?.string == string
    }

    override fun hashCode(): Int {
        return string.hashCode()
    }
}

fun String.encodeUtf8() : ByteString {
    return ByteString(this, Charsets.UTF_8)
}

fun ByteString.utf8(): String {
    return string
}

fun ByteString.toAsciiLowercase() : ByteString {
    val lower = string.toLowerCase()
    return ByteString(lower, Charsets.US_ASCII)
}

fun BufferedSink.writeByte(byte: Int) {
    put(byte.toByte())
}

fun BufferedSink.writeInt(int: Int) {
    putInt(int)
}

fun BufferedSink.writeShort(short: Int) {
    putShort(short.toShort())
}

fun BufferedSource.readByte(): Byte {
    return get()
}

fun BufferedSource.readInt(): Int {
    return getInt()
}

fun BufferedSource.readShort(): Short {
    return getShort()
}

fun BufferedSource.readByteString(): ByteString {
    return ByteString(this.bytes, Charsets.US_ASCII)
}

fun BufferedSource.readByteString(length: Long): ByteString {
    return ByteString(this.getBytes(length.toInt()), Charsets.US_ASCII)
}

fun BufferedSource.exhausted(): Boolean {
    return isEmpty
}

fun BufferedSource.skip(length: Long) {
    this.skip(length.toInt())
}

@Throws(IOException::class)
fun BufferedSource.readMedium(): Int {
    return (readByte() and 0xff shl 16
            or (readByte() and 0xff shl 8)
            or (readByte() and 0xff))
}


fun BufferedSink.writeMedium(medium: Int) {
    writeByte(medium.ushr(16) and 0xff)
    writeByte(medium.ushr(8) and 0xff)
    writeByte(medium and 0xff)
}

fun BufferedSink.write(byteString: ByteString) {
    add(ByteBufferList.deepCopy(ByteBuffer.wrap(byteString.bytes)))
}

fun BufferedSink.write(bytes: ByteArray) {
    add(ByteBufferList.deepCopy(ByteBuffer.wrap(bytes)))
}

fun BufferedSink.write(buffer: Buffer, length: Long) {
    buffer.get(this, length.toInt())
}

infix fun Byte.and(mask: Int): Int = toInt() and mask
infix fun Short.and(mask: Int): Int = toInt() and mask
infix fun Int.and(mask: Long): Long = toLong() and mask
