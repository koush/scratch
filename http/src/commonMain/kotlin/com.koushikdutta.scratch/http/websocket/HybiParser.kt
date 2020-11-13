package com.koushikdutta.scratch.http.websocket

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ByteOrder
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.http.client.createContentLengthPipe
import com.koushikdutta.scratch.http.http2.and
import kotlin.experimental.or
import kotlin.experimental.xor
import kotlin.random.Random


// closeCode was in an optional arg, but this was causing VerifyError on Android?
class HybiFrame(val opcode: Int, val final: Boolean, val read: AsyncRead) {
    var closeCode: Int? = null
        internal set
}

class HybiProtocolError(detailMessage: String) : IOException(detailMessage)

// todo: deflate?
// the problem with deflate is that it kinda sucks with hybi protocol due to mandatory client masking
// making the data appear random, and not compressing well.
// furthermore, kotlin does not have a builtin zlib.
class HybiParser(private val reader: AsyncReader, private val masking: Boolean) {
    var isClosed = false
        private set
    var isEnded = false
        private set

    suspend fun parse(): HybiFrame {
        try {
            return parseInternal()
        }
        catch (throwable: Throwable) {
            isEnded = true
            isClosed = true
            throw throwable
        }
    }

    // the parser will simply parse frames. it will not reassemble data+continuation* frames
    // into whole messages. fragmented message management (both read and write)
    // is left to the consumer of the parser.
    private suspend fun parseInternal(): HybiFrame {
        if (isEnded)
            throw IOException("HybiParser peer has closed the connection")

        // parse the opcode
        val byte0 = reader.readByte()
        val rsv1 = byte0 and RSV1 == RSV1
        val rsv2 = byte0 and RSV2 == RSV2
        val rsv3 = byte0 and RSV3 == RSV3
        if (rsv1 || rsv2 || rsv3)
            throw HybiProtocolError("RSV not zero")
        val final = byte0 and FIN == FIN
        val opcode = byte0 and OPCODE
        if (!OPCODES.contains(opcode))
            throw HybiProtocolError("Bad opcode")
        if (!FRAGMENTED_OPCODES.contains(opcode) && !final)
            throw HybiProtocolError("Expected non-final packet")

        // parse the payload length
        val byte1 = reader.readByte()
        val masked = byte1 and MASK == MASK
        val payloadLength = byte1 and LENGTH
        val length = if (payloadLength > 125) {
            if (payloadLength == 126) {
                reader.readShort().toLong()
            }
            else {
                reader.readLong()
            }
        }
        else {
            payloadLength.toLong()
        }

        // parse the payload
        val rawRead = reader.pipe(createContentLengthPipe(length))
        val read = if (masked) {
            val mask = reader.readBytes(4)
            rawRead.pipe {
                var index = 0
                while (it(buffer)) {
                    val bytes = buffer.readBytes()
                    for (i in bytes.indices) {
                        bytes[i] = bytes[i] xor mask[index % 4]
                        index++
                    }
                    buffer.add(bytes)
                    flush()
                }
            }
        }
        else {
            rawRead
        }

        if (opcode != OP_CLOSE)
            return HybiFrame(opcode, final, read)

        isEnded = true
        val reader = AsyncReader(read)
        return HybiFrame(opcode, final, reader).run {
            closeCode = reader.readShort().toInt()
            this
        }
    }

    fun frame(data: String): ReadableBuffers {
        return frame(OP_TEXT, data.createByteBufferList())
    }

    fun frame(data: ReadableBuffers): ReadableBuffers {
        return frame(OP_BINARY, data)
    }

    fun pingFrame(data: String): ReadableBuffers {
        return frame(OP_PING, data.createByteBufferList())
    }

    fun pongFrame(data: String): ReadableBuffers {
        return frame(OP_PONG, data.createByteBufferList())
    }

    fun closeFrame(code: Int, reason: String): ReadableBuffers {
        if (isClosed)
            return ByteBufferList()
        val buffer = ByteBufferList()
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(code.toShort())
        buffer.putUtf8String(reason)
        val ret = frame(OP_CLOSE, buffer)
        isClosed = true
        return ret
    }

    private fun frame(opcode: Int, data: ReadableBuffers): ReadableBuffers {
        if (isClosed) throw IOException("hybiparser closed")

        if (!FRAGMENTED_OPCODES.contains(opcode) && data.remaining() > 125)
            throw IllegalArgumentException("hybi control code can not send data payloads larger than 125 bytes")

        val length = data.remaining()
        val masked = if (masking) MASK else 0

        val frame = ByteBufferList()
        frame.put((FIN.toByte() or opcode.toByte()))

        if (length <= 125) {
            frame.put((masked or length).toByte())
        } else if (length <= 65535) {
            frame.put((masked or 126).toByte())
            frame.put((length / 256).toByte())
            frame.put((length and BYTE).toByte())
        } else {
            frame.put((masked or 127).toByte())
            frame.put((length / _2_TO_56_ and BYTE.toLong()).toByte())
            frame.put((length / _2_TO_48_ and BYTE.toLong()).toByte())
            frame.put((length / _2_TO_40_ and BYTE.toLong()).toByte())
            frame.put((length / _2_TO_32_ and BYTE.toLong()).toByte())
            frame.put((length / _2_TO_24 and BYTE.toLong()).toByte())
            frame.put((length / _2_TO_16_ and BYTE.toLong()).toByte())
            frame.put((length / _2_TO_8_ and BYTE.toLong()).toByte())
            frame.put((length and BYTE).toByte())
        }
        if (masking) {
            val mask = Random.nextBytes(4)
            frame.add(mask)
            val array = data.readBytes()
            for (i in array.indices) {
                array[i] = (array[i] xor mask[i % 4])
            }
            frame.add(array)
        }
        else {
            frame.add(data);
        }

        return frame
    }

    companion object {
        private const val BYTE = 255
        private const val FIN = 128
        private const val MASK = 128
        private const val RSV1 = 64
        private const val RSV2 = 32
        private const val RSV3 = 16
        private const val OPCODE = 15
        private const val LENGTH = 127
        private const val MODE_TEXT = 1
        private const val MODE_BINARY = 2
        const val OP_CONTINUATION = 0
        const val OP_TEXT = 1
        const val OP_BINARY = 2
        const val OP_CLOSE = 8
        const val OP_PING = 9
        const val OP_PONG = 10
        private val OPCODES: List<Int> = listOf(
                OP_CONTINUATION,
                OP_TEXT,
                OP_BINARY,
                OP_CLOSE,
                OP_PING,
                OP_PONG
        )
        private val FRAGMENTED_OPCODES: List<Int> = listOf(
                OP_CONTINUATION, OP_TEXT, OP_BINARY
        )

        private const val BASE: Long = 2
        private const val _2_TO_8_ = BASE shl 7
        private const val _2_TO_16_ = BASE shl 15
        private const val _2_TO_24 = BASE shl 23
        private const val _2_TO_32_ = BASE shl 31
        private const val _2_TO_40_ = BASE shl 39
        private const val _2_TO_48_ = BASE shl 47
        private const val _2_TO_56_ = BASE shl 55
    }
}
