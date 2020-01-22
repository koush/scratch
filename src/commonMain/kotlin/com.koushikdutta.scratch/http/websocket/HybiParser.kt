package com.koushikdutta.scratch.http.websocket


//
// HybiParser.java: draft-ietf-hybi-thewebsocketprotocol-13 parser
//
// Based on code from the faye project.
// https://github.com/faye/faye-websocket-node
// Copyright (c) 2009-2012 James Coglan
//
// Ported from Javascript to Java by Eric Butler <eric@codebutler.com>
//
// (The MIT License)
//
// Permission is hereby granted, free of charge, to any person obtaining
// a copy of this software and associated documentation files (the
// "Software"), to deal in the Software without restriction, including
// without limitation the rights to use, copy, modify, merge, publish,
// distribute, sublicense, and/or sell copies of the Software, and to
// permit persons to whom the Software is furnished to do so, subject to
// the following conditions:
//
// The above copyright notice and this permission notice shall be
// included in all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
// EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
// MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
// NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
// LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
// OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
// WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ByteOrder
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.createByteBufferList
import com.koushikdutta.scratch.http.client.middleware.createContentLengthPipe
import com.koushikdutta.scratch.http.http2.and
import kotlin.experimental.or
import kotlin.experimental.xor
import kotlin.random.Random

class HybiMessage(val opcode: Int, val final: Boolean, val read: AsyncRead, private val code: Int? = null) {
    val closeCode: Int
        get() {
            if (code == null)
                throw IllegalStateException("opcode is not OP_CLOSE")
            return code
        }
}

// todo: deflate?
// the problem with deflate is that it kinda sucks with hybi protocol due to mandatory client masking
// making the data appear random, and not compressing well.
// furthermore, kotlin does not have a builtin zlib.
class HybiParser(private val reader: AsyncReader, private val masking: Boolean) {
    private var closed = false

    suspend fun parse(): HybiMessage {
        // parse the opcode
        val byte0 = reader.readByte()
        val rsv1 = byte0 and RSV1 == RSV1
        val rsv2 = byte0 and RSV2 == RSV2
        val rsv3 = byte0 and RSV3 == RSV3
        if (rsv1 || rsv2 || rsv3)
            throw ProtocolError("RSV not zero")
        val final = byte0 and FIN == FIN
        val opcode = byte0 and OPCODE
        if (!OPCODES.contains(opcode))
            throw ProtocolError("Bad opcode")
        if (!FRAGMENTED_OPCODES.contains(opcode) && !final)
            throw ProtocolError("Expected non-final packet")

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

        val mask: ByteArray?
        if (masked) {
            mask = reader.readBytes(4)
        }
        else {
            mask = null
        }

        val rawRead = reader.pipe(createContentLengthPipe(length))
        val read = if (masked) {
            rawRead.pipe {
                var index = 0
                while (it(buffer)) {
                    val bytes = buffer.readBytes()
                    for (i in bytes.indices) {
                        bytes[i] = bytes[i] xor mask!![index % 4]
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
            return HybiMessage(opcode, final, read)

        val reader = AsyncReader(read)
        return HybiMessage(opcode, final, reader::read, reader.readShort().toInt())
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
        if (closed)
            return ByteBufferList()
        val buffer = ByteBufferList()
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(code.toShort())
        buffer.putUtf8String(reason)
        val ret = frame(OP_CLOSE, buffer)
        closed = true
        return ret
    }

    /**
     * Flip the opcode so to avoid the name collision with the public method
     *
     * @param opcode
     * @param data
     * @param errorCode
     * @return
     */
    private fun frame(opcode: Int, data: ReadableBuffers): ReadableBuffers {
        if (closed) throw IOException("hybiparser closed")

        val length = data.remaining()
        val header = if (length <= 125) 2 else if (length <= 65535) 4 else 10
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
                array[i] = (array[i] xor mask[i % 4]) as Byte
            }
            frame.add(array)
        }
        else {
            frame.add(data);
        }

        return frame
    }

    class ProtocolError(detailMessage: String) : IOException(detailMessage)
    companion object {
        private const val TAG = "HybiParser"
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
