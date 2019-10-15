@file:Suppress("NAME_SHADOWING")

package com.koushikdutta.scratch.buffers

import kotlin.math.max
import kotlin.math.min

fun MutableList<ByteBuffer>.peekFirst(): ByteBuffer {
    return get(0)
}
fun MutableList<ByteBuffer>.peekLast(): ByteBuffer {
    return get(size - 1)
}
fun MutableList<ByteBuffer>.removeFirst(): ByteBuffer {
    return removeAt(0)
}

fun MutableList<ByteBuffer>.addFirst(byteBuffer: ByteBuffer) {
    add(0, byteBuffer)
}


class ByteBufferList : Buffers {
    private val mBuffers = mutableListOf<ByteBuffer>()
    private var order = ByteOrder.BIG_ENDIAN
    private var remaining = 0

    constructor() {}

    constructor(vararg b: ByteBuffer) {
        addAll(*b)
    }


    constructor(buf: ByteArray) : super() {
        val b = createByteBuffer(buf)
        add(b)
    }

    override fun order(): ByteOrder {
        return order
    }

    override fun order(order: ByteOrder) {
        this.order = order
    }

    override fun readAll(): Array<ByteBuffer> {
        val ret = mBuffers.toTypedArray<ByteBuffer>()
        mBuffers.clear()
        remaining = 0
        return ret
    }

    override fun remaining(): Int {
        return remaining
    }

    override fun peekShort(): Short {
        return read(2).getShort(mBuffers.peekFirst().position())
    }

    override fun peek(): Byte {
        return read(1).get(mBuffers.peekFirst().position())
    }

    override fun peekByteChar(): Char {
        return peek().toChar()
    }

    override fun peekInt(): Int {
        return read(4).getInt(mBuffers.peekFirst().position())
    }

    override fun peekLong(): Long {
        return read(8).getLong(mBuffers.peekFirst().position())
    }

    override fun peekBytes(size: Int): ByteArray {
        val ret = ByteArray(size)
        val buffer = read(size)
        buffer.mark()
        buffer.get(ret)
        buffer.reset()
        return ret
    }

    override fun skip(length: Int): ByteBufferList {
        if (length == 0)
            return this
        readInternal(null, 0, length)
        return this
    }

    override fun readInt(): Int {
        val ret = read(4).getInt()
        remaining -= 4
        return ret
    }

    override fun readByteChar(): Char {
        val ret = read(1).get().toChar()
        remaining--
        return ret
    }

    override fun readShort(): Short {
        val ret = read(2).getShort()
        remaining -= 2
        return ret
    }

    override fun readByte(): Byte {
        val ret = read(1).get()
        remaining--
        return ret
    }

    override fun readLong(): Long {
        val ret = read(8).getLong()
        remaining -= 8
        return ret
    }

    override fun read(bytes: ByteArray) {
        read(bytes, 0, bytes.size)
    }

    override fun read(buffer: ByteBuffer) {
        require(remaining >= buffer.remaining()) { "length" }

        val reading = buffer.remaining()

        while (buffer.hasRemaining()) {
            val b = mBuffers.peekFirst()
            if (buffer.remaining() < b.remaining()) {
                val oldLimit = b.limit()
                b.limit(b.position() + buffer.remaining())
                buffer.put(b)
                b.limit(oldLimit)
            } else {
                buffer.put(b)
                trim()
            }
        }

        remaining -= reading
    }

    private fun readInternal(bytes: ByteArray?, offset: Int, length: Int) {
        var offset = offset
        require(remaining >= length) { "length" }

        var need = length
        while (need > 0) {
            val b = mBuffers.peekFirst()
            val read = min(b.remaining(), need)
            if (bytes != null) {
                b.get(bytes, offset, read)
            } else {
                //when bytes is null, just skip data.
                b.position(b.position() + read)
            }
            need -= read
            offset += read
            if (b.remaining() == 0) {
                mBuffers.removeFirst()
                reclaim(b)
            }
        }

        remaining -= length
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int) {
        readInternal(bytes, offset, length)
    }

    override fun read(into: WritableBuffers, length: Int) {
        require(remaining >= length) { "length" }
        var offset = 0

        while (offset < length) {
            val b = mBuffers.removeFirst()
            val remaining = b.remaining()

            if (remaining == 0) {
                reclaim(b)
                continue
            }

            if (offset + remaining > length) {
                val need = length - offset
                // this is shared between both
                val subset = obtain(need)
                subset.limit(need)
                b.get(subset.array(), 0, need)
                into.add(subset)
                mBuffers.addFirst(b)
                break
            } else {
                // this belongs to the new list
                into.add(b)
            }

            offset += remaining
        }

        remaining -= length
    }

    override fun read(into: WritableBuffers): Boolean {
        val hadData = hasRemaining()
        read(into, remaining)
        return hadData
    }

    operator fun get(length: Int): ByteBufferList {
        val ret = ByteBufferList()
        read(ret, length)
        ret.order(order)
        return ret
    }

    override fun readScan(into: WritableBuffers, scan: ByteArray): Boolean {
        var bytesRead = 0
        var matchCount = 0

        val buffers = readAll()

        var bufferPosition = 0
        while (bufferPosition < buffers.size && matchCount < scan.size) {
            // grab the next buffer for searching
            val b = buffers[bufferPosition]

            var scanPosition = b.position()
            while (scanPosition < b.limit() && matchCount < scan.size) {
                if (scan[matchCount] == b.get(scanPosition)) {
                    matchCount++
                } else {
                    bytesRead++
                    // abort and account for any match sequence
                    bytesRead += matchCount
                    // reset the match
                    matchCount = 0
                }
                scanPosition++
            }
            bufferPosition++
        }

        // add everything back in.
        addAll(*buffers)

        // if we had a match, we can read that too.
        if (matchCount == scan.size)
            bytesRead += scan.size

        read(into, bytesRead)

        return matchCount == scan.size
    }

    override fun readByteBuffer(): ByteBuffer {
        if (remaining == 0)
            return EMPTY_BYTEBUFFER
        read(remaining)
        return remove()
    }

    override fun readByteBuffer(length: Int): ByteBuffer {
        val ret = obtain(length)
        ret.limit(length)
        read(ret)
        ret.flip()
        return ret
    }

    private fun read(count: Int): ByteBuffer {
        require(remaining >= count) { "count : $remaining/$count" }

        var first: ByteBuffer = mBuffers.peekFirst()
        while (!first.hasRemaining()) {
            reclaim(mBuffers.removeFirst())
            first = mBuffers.peekFirst()
        }

        if (first.remaining() >= count) {
            return first.order(order)
        }

        val ret = obtain(count)
        ret.limit(count)
        val bytes = ret.array()
        var offset = 0
        var bb: ByteBuffer? = null
        while (offset < count) {
            bb = mBuffers.removeFirst()
            val toRead = min(count - offset, bb.remaining())
            bb.get(bytes, offset, toRead)
            offset += toRead
            if (bb.remaining() == 0) {
                reclaim(bb)
                bb = null
            }
        }
        // if there was still data left in the last buffer we popped
        // toss it back into the head
        if (bb != null && bb.remaining() > 0)
            mBuffers.addFirst(bb)
        mBuffers.addFirst(ret)
        return ret.order(order)
    }

    private fun trim() {
        // this clears out buffers that are empty in the beginning of the list
        read(0)
    }

    override fun add(b: ReadableBuffers): ByteBufferList {
        b.read(this)
        return this
    }

    override fun add(b: ByteBuffer): ByteBufferList {
        if (b.remaining() <= 0) {
            //            System.out.println("reclaiming remaining: " + b.remaining());
            reclaim(b)
            return this
        }
        addRemaining(b.remaining())
        // see if we can fit the entirety of the buffer into the end
        // of the current last buffer
        if (mBuffers.size > 0) {
            val last = mBuffers.peekLast()
            if (last.capacity() - last.limit() >= b.remaining()) {
                last.mark()
                last.position(last.limit())
                last.limit(last.capacity())
                last.put(b)
                last.limit(last.position())
                last.reset()
                reclaim(b)
                trim()
                return this
            }
        }
        mBuffers.add(b)
        trim()
        return this
    }

    override fun addFirst(b: ByteBuffer): ByteBufferList {
        if (b.remaining() <= 0) {
            reclaim(b)
            return this
        }
        addRemaining(b.remaining())
        // see if we can fit the entirety of the buffer into the beginning
        // of the current first buffer
        if (mBuffers.size > 0) {
            val first = mBuffers.peekFirst()
            if (first.position() >= b.remaining()) {
                first.position(first.position() - b.remaining())
                first.mark()
                first.put(b)
                first.reset()
                reclaim(b)
                return this
            }
        }
        mBuffers.addFirst(b)
        return this
    }

    private fun addRemaining(remaining: Int) {
        if (this.remaining >= 0)
            this.remaining += remaining
    }

    override fun free() {
        while (mBuffers.size > 0) {
            reclaim(mBuffers.removeFirst())
        }
        remaining = 0
    }

    fun remove(): ByteBuffer {
        val ret = mBuffers.removeFirst()
        remaining -= ret.remaining()
        return ret
    }

    private fun size(): Int {
        return mBuffers.size
    }

    // allocate or extend an existing buffer.
    // return the buffer with the mark set so position can be restored after writing.
    private fun grow(length: Int): ByteBuffer {
        if (!mBuffers.isEmpty()) {
            val b = mBuffers.peekLast()
            if (b.limit() + length < b.capacity()) {
                b.mark()
                b.position(b.limit())
                b.limit(b.limit() + length)
                remaining += length
                return b.order(order)
            }
        }

        val ret = obtain(length)
        ret.mark()
        ret.limit(length)
        add(ret)

        return ret.order(order)
    }

    override fun put(b: Byte): ByteBufferList {
        grow(1).put(b).reset()
        return this
    }

    override fun putBytes(bytes: ByteArray): ByteBufferList {
        grow(bytes.size).put(bytes).reset()
        return this
    }

    override fun putShort(s: Short): ByteBufferList {
        grow(2).putShort(s).reset()
        return this
    }

    override fun putInt(i: Int): ByteBufferList {
        grow(4).putInt(i).reset()
        return this
    }

    override fun putLong(l: Long): ByteBufferList {
        grow(8).putLong(l).reset()
        return this
    }

    override fun putByteChar(c: Char): ByteBufferList {
        return put(c.toByte())
    }

    @UseExperimental(ExperimentalStdlibApi::class)
    override fun putUtf8String(s: String): ByteBufferList {
        add(s.encodeToByteArray())
        return this
    }

    // not doing toString as this is really nasty in the debugger...
    @UseExperimental(ExperimentalStdlibApi::class)
    override fun peekUtf8String(): String {
        val builder = StringBuilder()
        for (bb in mBuffers) {
            val bytes: ByteArray
            val offset: Int
            val length: Int
            if (bb.isDirect()) {
                bytes = ByteArray(bb.remaining())
                offset = 0
                length = bb.remaining()
                bb.get(bytes)
            } else {
                bytes = bb.array()
                offset = bb.arrayOffset() + bb.position()
                length = bb.remaining()
            }
            builder.append(bytes.decodeToString(offset, offset + length))
        }
        return builder.toString()
    }

    companion object {
        val EMPTY_BYTEBUFFER = createByteBuffer(ByteArray(0))
        var MAX_ITEM_SIZE = 1024 * 256

        fun reclaim(b: ByteBuffer?) {
        }

        fun obtain(size: Int): ByteBuffer {
            return allocateByteBuffer(max(8192, size))
        }

        fun deepCopyIfDirect(copyOf: ByteBuffer): ByteBuffer {
            return if (copyOf.isDirect()) deepCopy(copyOf) else copyOf
        }

        fun deepCopy(copyOf: ByteBuffer): ByteBuffer {
            return obtain(copyOf.remaining()).put(copyOf.duplicate()).flip() as ByteBuffer
        }
    }
}
