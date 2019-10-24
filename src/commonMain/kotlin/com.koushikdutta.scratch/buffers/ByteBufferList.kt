@file:Suppress("NAME_SHADOWING")

package com.koushikdutta.scratch.buffers

import kotlin.math.max
import kotlin.math.min

private fun ArrayList<ByteBuffer>.peekFirst(): ByteBuffer {
    return get(0)
}
private fun ArrayList<ByteBuffer>.peekLast(): ByteBuffer {
    return get(size - 1)
}
private fun ArrayList<ByteBuffer>.removeFirst(): ByteBuffer {
    return removeAt(0)
}
private fun ArrayList<ByteBuffer>.addFirst(byteBuffer: ByteBuffer) {
    add(0, byteBuffer)
}

class ByteBufferList : Buffers {
    private val buffers = arrayListOf<ByteBuffer>()
    private val freeBuffers = arrayListOf<ByteBuffer>()
    private var order = ByteOrder.BIG_ENDIAN
    private var remaining = 0

    init {
        buffers.ensureCapacity(10)
    }

    constructor()

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
        val ret = buffers.toTypedArray<ByteBuffer>()
        buffers.clear()
        remaining = 0
        return ret
    }

    override fun remaining(): Int {
        return remaining
    }

    override fun peekShort(): Short {
        return read(2).getShort(buffers.peekFirst().position())
    }

    override fun peek(): Byte {
        return read(1).get(buffers.peekFirst().position())
    }

    override fun peekByteChar(): Char {
        return peek().toChar()
    }

    override fun peekInt(): Int {
        return read(4).getInt(buffers.peekFirst().position())
    }

    override fun peekLong(): Long {
        return read(8).getLong(buffers.peekFirst().position())
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
            val b = buffers.peekFirst()
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
            val b = buffers.peekFirst()
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
                buffers.removeFirst()
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


        // after this buffer gives filled buffers to the target buffer,
        // take all the empty buffers from the target.
        into.giveReclaimedBuffers(freeBuffers)

        while (offset < length) {
            val b = buffers.removeFirst()
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
                buffers.addFirst(b)
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
        return readFirst()
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

        var first: ByteBuffer = buffers.peekFirst()
        while (!first.hasRemaining()) {
            reclaim(buffers.removeFirst())
            first = buffers.peekFirst()
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
            bb = buffers.removeFirst()
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
            buffers.addFirst(bb)
        buffers.addFirst(ret)
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
        remaining += b.remaining()
        // see if we can fit the entirety of the buffer into the end
        // of the current last buffer
        if (buffers.size > 0) {
            val last = buffers.peekLast()
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
        buffers.add(b)
        trim()
        return this
    }

    override fun addFirst(b: ByteBuffer): ByteBufferList {
        if (b.remaining() <= 0) {
            reclaim(b)
            return this
        }
        remaining += b.remaining()
        // see if we can fit the entirety of the buffer into the beginning
        // of the current first buffer
        if (buffers.size > 0) {
            val first = buffers.peekFirst()
            if (first.position() >= b.remaining()) {
                first.position(first.position() - b.remaining())
                first.mark()
                first.put(b)
                first.reset()
                reclaim(b)
                return this
            }
        }
        buffers.addFirst(b)
        return this
    }

    override fun free() {
        while (buffers.size > 0) {
            reclaim(buffers.removeFirst())
        }
        remaining = 0
    }

    override fun readFirst(): ByteBuffer {
        val ret = buffers.removeFirst()
        remaining -= ret.remaining()
        return ret
    }

    private fun size(): Int {
        return buffers.size
    }

    // allocate or extend an existing buffer.
    // return the buffer with the mark set so position can be restored after writing.
    private fun grow(length: Int, requireArray: Boolean = false): ByteBuffer {
        if (!buffers.isEmpty()) {
            val b = buffers.peekLast()
            if (b.limit() + length <= b.capacity() && (!b.isDirect() || !requireArray)) {
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
        buffers.add(ret)
        remaining += ret.remaining()

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

    override fun putAllocatedBytes(allocate: Int, writer: BuffersArrayWriter): ByteBufferList {
        val buffer = grow(allocate)
        writer(buffer.array(), buffer.arrayOffset() + buffer.position())
        return this
    }

    override fun <T> putAllocatedBuffer(allocate: Int, writer: BuffersBufferWriter<T>): T {
        val buffer = grow(allocate)
        // the amount written may not be the same as the amount allocated.
        // this will happen in the case of
        // resolve that discrepency when tracking remaining.
        try {
            return writer(buffer)
        }
        finally {
            // the writer may throw! so the adjustment needs to happen here.
            // see how much was actually written.
            val used = allocate - buffer.remaining()
            // update the limit to the current write cursor
            buffer.limit(buffer.position())
            // reset hte position to the mark set in grow
            buffer.reset()
            remaining = remaining - allocate + used
        }
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
        for (bb in buffers) {
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

    private fun reclaimInternal(vararg buffers: ByteBuffer?) {
        for (b in buffers) {
            // only wholly owned arrays can be reclaimed
            if (b == null || b.isDirect() || b.array().size != b.capacity() || b.arrayOffset() != 0 || b.capacity() > MAX_ITEM_SIZE || b.capacity() < MIN_ITEM_SIZE)
                return
            freeBuffers.add(b)
        }
    }

    private val maxReclaimed = 500
    override fun reclaim(vararg buffers: ByteBuffer?) {
//        if (freeBuffers.size > maxReclaimed)
//            throw AssertionError("ByteBufferList has reclaimed over $maxReclaimed buffers. There is a leak somewhere. Typically reads and puts between buffers will automatically swap empty ByteBuffers in the appropriate direction. Use obtainAll/takeAll to pass buffers upstream.")
        reclaimInternal(*buffers)
    }

    override fun obtain(size: Int): ByteBuffer {
        for ((index, b) in freeBuffers.withIndex()) {
            if (size <= b.capacity()) {
                freeBuffers.removeAt(index)
                b.clear()
                return b
            }
        }
        return ByteBufferList.obtain(size)
    }

    override fun giveReclaimedBuffers(into: ArrayList<ByteBuffer>) {
//        if (into.size > maxReclaimed)
//            throw AssertionError("ByteBufferList has obtained over $maxReclaimed buffers. There is a leak somewhere. Typically reads and puts between buffers will automatically swap empty ByteBuffers in the appropriate direction. Use obtainAll/takeAll to pass buffers upstream.")
        into.addAll(freeBuffers)
        freeBuffers.clear()
    }

    override fun takeReclaimedBuffers(from: AllocatingBuffers) {
        from.giveReclaimedBuffers(freeBuffers)
    }

    companion object {
        val EMPTY_BYTEBUFFER = createByteBuffer(ByteArray(0))
        var MAX_ITEM_SIZE = 65536
        var MIN_ITEM_SIZE = 1024
        val totalObtained: Long
            get() = totalObtained2
            val totalObtainCount: Int
            get() = totalObtainCount2

        fun obtain(size: Int): ByteBuffer {
            totalObtained2 += size
            totalObtainCount2++
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

internal var totalObtained2 = 0L
internal var totalObtainCount2 = 0