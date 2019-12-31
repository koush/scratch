package com.koushikdutta.scratch.http.http2

import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.buffers.createByteBuffer

internal typealias BufferedSource = ReadableBuffers
internal typealias BufferedSink = WritableBuffers
internal typealias Buffer = ByteBufferList

internal class ByteString {
    val bytes: ByteArray
    val string: String
    constructor(bytes: ByteArray) {
        this.bytes = bytes
        this.string = bytes.decodeToString()
    }
    constructor(string: String) {
        this.bytes = string.encodeToByteArray()
        this.string = string
    }
    val size: Int
        get() = bytes.size

    operator fun get(index: Int): Byte = bytes[index]

    internal fun hex(): String {
        return bytes.joinToString { it.toString(16).padStart(2, '0') }
    }

    fun startsWith(other: ByteString): Boolean {
        return string.startsWith(other.string)
    }

    companion object {
        val EMPTY = ByteString("")
    }

    override fun equals(other: Any?): Boolean {
        return (other as? ByteString)?.string == string
    }

    override fun hashCode(): Int {
        return string.hashCode()
    }
}

internal fun String.encodeUtf8() : ByteString {
    return ByteString(this)
}

internal fun ByteString.utf8(): String {
    return string
}

internal fun ByteString.toAsciiLowercase() : ByteString {
    val lower = string.toLowerCase()
    return ByteString(lower)
}

internal fun BufferedSink.writeByte(byte: Int) {
    put(byte.toByte())
}

internal fun BufferedSink.writeInt(int: Int) {
    putInt(int)
}

internal fun BufferedSink.writeShort(short: Int) {
    putShort(short.toShort())
}

internal fun BufferedSource.readByteString(): ByteString {
    return ByteString(this.readBytes())
}

internal fun BufferedSource.readByteString(length: Long): ByteString {
    return ByteString(this.readBytes(length.toInt()))
}

internal fun BufferedSource.exhausted(): Boolean {
    return isEmpty
}

internal fun BufferedSource.skip(length: Long) {
    this.skip(length.toInt())
}

internal fun BufferedSource.readMedium(): Int {
    return (readByte() and 0xff shl 16
            or (readByte() and 0xff shl 8)
            or (readByte() and 0xff))
}


internal fun BufferedSink.writeMedium(medium: Int) {
    writeByte(medium.ushr(16) and 0xff)
    writeByte(medium.ushr(8) and 0xff)
    writeByte(medium and 0xff)
}

internal fun BufferedSink.write(byteString: ByteString) {
    add(ByteBufferList.deepCopy(createByteBuffer(byteString.bytes)))
}

internal fun BufferedSink.write(bytes: ByteArray) {
    add(ByteBufferList.deepCopy(createByteBuffer(bytes)))
}

internal fun BufferedSink.write(buffer: ReadableBuffers, length: Long) {
    buffer.read(this, length.toInt())
}

internal infix fun Byte.and(mask: Int): Int = toInt() and mask
internal infix fun Short.and(mask: Int): Int = toInt() and mask
internal infix fun Int.and(mask: Long): Long = toLong() and mask

internal fun <T> Array<T>.arraycopy(sourcePos: Int, dest_arr: Array<T>, destPos: Int, len: Int) {
    copyInto(dest_arr, destPos, sourcePos, sourcePos + len)
}
internal fun <T> Array<T>.fill(value: T, startIndex: Int = 0, endIndex: Int = size) {
    for (i in startIndex until endIndex) {
        this[i] = value
    }
}
internal fun IntArray.fill(value: Int, startIndex: Int = 0, endIndex: Int = size) {
    for (i in startIndex until endIndex) {
        this[i] = value
    }
}

internal fun Int.bitCount(): Int {
    var ret = 0
    var i = this
    for (n in 0 until 32) {
        if ((i and 0x1) != 0)
            ret++
        i = i shr 1
    }
    return ret
}