/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.koushikdutta.scratch.http.http2.okhttp

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.http.http2.*
import com.koushikdutta.scratch.http.http2.okhttp.Http2.CONNECTION_PREFACE
import com.koushikdutta.scratch.http.http2.okhttp.Http2.FLAG_ACK
import com.koushikdutta.scratch.http.http2.okhttp.Http2.FLAG_COMPRESSED
import com.koushikdutta.scratch.http.http2.okhttp.Http2.FLAG_END_HEADERS
import com.koushikdutta.scratch.http.http2.okhttp.Http2.FLAG_END_STREAM
import com.koushikdutta.scratch.http.http2.okhttp.Http2.FLAG_PADDED
import com.koushikdutta.scratch.http.http2.okhttp.Http2.FLAG_PRIORITY
import com.koushikdutta.scratch.http.http2.okhttp.Http2.INITIAL_MAX_FRAME_SIZE
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_CONTINUATION
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_DATA
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_GOAWAY
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_HEADERS
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_PING
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_PRIORITY
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_PUSH_PROMISE
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_RST_STREAM
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_SETTINGS
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_WINDOW_UPDATE


/**
 * Reads HTTP/2 transport frames.
 *
 * This implementation assumes we do not send an increased [frame][Settings.getMaxFrameSize] to the
 * peer. Hence, we expect all frames to have a max length of [Http2.INITIAL_MAX_FRAME_SIZE].
 */
internal class Http2Reader(
  /** Creates a frame reader with max header table size of 4096. */
  private val socket: AsyncSocket,
  private val client: Boolean,
  private val reader: AsyncReader
) {
  private val headerSource: Buffer = ByteBufferList()
  private val dataSource: Buffer = ByteBufferList()
  private val source: Buffer = ByteBufferList()
  private val hpackReader: Hpack.Reader = Hpack.Reader(
          source = headerSource,
          headerTableSizeSetting = 4096
  )

  suspend fun readConnectionPreface(handler: Handler) {
    if (client) {
      // The client reads the initial SETTINGS frame.
      if (!nextFrame(true, handler)) {
        throw IOException("Required SETTINGS preface not received")
      }
    } else {
      // The server reads the CONNECTION_PREFACE byte string.
      if (!reader.readLength(source, Http2.CONNECTION_PREFACE.size))
        throw IOException("Server expected connection preface.")
      val connectionPreface = source.readByteString(Http2.CONNECTION_PREFACE.size.toLong())
      if (CONNECTION_PREFACE != connectionPreface) {
        throw IOException("Expected a connection header but was ${connectionPreface.utf8()}")
      }
    }
  }

  suspend fun nextFrame(requireSettings: Boolean, handler: Handler): Boolean {
    if (source.hasRemaining())
      throw AssertionError("source not empty")

    if (!reader.readLength(source, 9))
      return false

    //  0                   1                   2                   3
    //  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
    // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
    // |                 Length (24)                   |
    // +---------------+---------------+---------------+
    // |   Type (8)    |   Flags (8)   |
    // +-+-+-----------+---------------+-------------------------------+
    // |R|                 Stream Identifier (31)                      |
    // +=+=============================================================+
    // |                   Frame Payload (0...)                      ...
    // +---------------------------------------------------------------+
    val length = source.readMedium()
    if (length > INITIAL_MAX_FRAME_SIZE) {
      throw IOException("FRAME_SIZE_ERROR: $length")
    }
    val type = source.readByte() and 0xff
    if (requireSettings && type != TYPE_SETTINGS) {
      throw IOException("Expected a SETTINGS frame but was $type")
    }
    val flags = source.readByte() and 0xff
    val streamId = source.readInt() and 0x7fffffff // Ignore reserved bit.

    reader.readLength(source, length)

    when (type) {
      TYPE_DATA -> readData(handler, length, flags, streamId)
      TYPE_HEADERS -> readHeaders(handler, length, flags, streamId)
      TYPE_PRIORITY -> readPriority(handler, length, flags, streamId)
      TYPE_RST_STREAM -> readRstStream(handler, length, flags, streamId)
      TYPE_SETTINGS -> readSettings(handler, length, flags, streamId)
      TYPE_PUSH_PROMISE -> readPushPromise(handler, length, flags, streamId)
      TYPE_PING -> readPing(handler, length, flags, streamId)
      TYPE_GOAWAY -> readGoAway(handler, length, flags, streamId)
      TYPE_WINDOW_UPDATE -> readWindowUpdate(handler, length, flags, streamId)
      else -> source.skip(length.toLong()) // Implementations MUST discard frames of unknown types.
    }

    return true
  }

  private suspend fun readHeaders(handler: Handler, length: Int, flags: Int, streamId: Int) {
    if (streamId == 0) throw IOException("PROTOCOL_ERROR: TYPE_HEADERS streamId == 0")

    val endStream = (flags and FLAG_END_STREAM) != 0
    val padding = if (flags and FLAG_PADDED != 0) source.readByte() and 0xff else 0

    var headerBlockLength = length
    if (flags and FLAG_PRIORITY != 0) {
      readPriority(handler, streamId)
      headerBlockLength -= 5 // account for above read.
    }
    headerBlockLength = lengthWithoutPadding(headerBlockLength, flags, padding)
    val headerBlock = readHeaderBlock(headerBlockLength, padding, flags, streamId)

    handler.headers(endStream, streamId, -1, headerBlock)
  }

  private suspend fun readContinuation(previousStreamId: Int): Int {
    if (source.hasRemaining())
      throw AssertionError("source not empty")

    if (!reader.readLength(source, 9))
      throw IOException("end of stream reached mid continuation block")

    val length = source.readMedium()
    val type = source.readByte() and 0xff
    val flags = source.readByte() and 0xff
    val streamId = source.readInt() and 0x7fffffff

    if (type != TYPE_CONTINUATION) throw IOException("$type != TYPE_CONTINUATION")
    if (streamId != previousStreamId) throw IOException("TYPE_CONTINUATION streamId changed")

    if (!reader.readLength(headerSource, length))
      throw IOException("end of stream reached mid continuation block")

    return flags
  }

  private suspend fun readHeaderBlock(length: Int, padding: Int, headerFlags: Int, streamId: Int): List<Header> {
    if (headerSource.hasRemaining())
      throw AssertionError("header source not empty")

    var flags = headerFlags

    source.read(headerSource, length)
    source.skip(padding)

    while (flags and FLAG_END_HEADERS == 0) {
      flags = readContinuation(streamId)
    }

    // TODO: Concat multi-value headers with 0x0, except COOKIE, which uses 0x3B, 0x20.
    // http://tools.ietf.org/html/draft-ietf-httpbis-http2-17#section-8.1.2.5
    hpackReader.readHeaders()
    return hpackReader.getAndResetHeaderList()
  }

  private fun readData(handler: Handler, length: Int, flags: Int, streamId: Int) {
    if (streamId == 0) throw IOException("PROTOCOL_ERROR: TYPE_DATA streamId == 0")

    // TODO: checkState open or half-closed (local) or raise STREAM_CLOSED
    val inFinished = flags and FLAG_END_STREAM != 0
    val gzipped = flags and FLAG_COMPRESSED != 0
    if (gzipped) {
      throw IOException("PROTOCOL_ERROR: FLAG_COMPRESSED without SETTINGS_COMPRESS_DATA")
    }

    val padding = if (flags and FLAG_PADDED != 0) source.readByte() and 0xff else 0
    val dataLength = lengthWithoutPadding(length, flags, padding)

    source.read(dataSource, dataLength)

    handler.data(inFinished, streamId, dataSource, dataLength)
    source.skip(padding.toLong())
  }

  private fun readPriority(handler: Handler, length: Int, flags: Int, streamId: Int) {
    if (length != 5) throw IOException("TYPE_PRIORITY length: $length != 5")
    if (streamId == 0) throw IOException("TYPE_PRIORITY streamId == 0")
    readPriority(handler, streamId)
  }

  private fun readPriority(handler: Handler, streamId: Int) {
    val w1 = source.readInt()
    val exclusive = w1 and 0x80000000.toInt() != 0
    val streamDependency = w1 and 0x7fffffff
    val weight = (source.readByte() and 0xff) + 1
    handler.priority(streamId, streamDependency, weight, exclusive)
  }

  private fun readRstStream(handler: Handler, length: Int, flags: Int, streamId: Int) {
    if (length != 4) throw IOException("TYPE_RST_STREAM length: $length != 4")
    if (streamId == 0) throw IOException("TYPE_RST_STREAM streamId == 0")
    val errorCodeInt = source.readInt()
    val errorCode = ErrorCode.fromHttp2(errorCodeInt)
            ?: throw IOException(
        "TYPE_RST_STREAM unexpected error code: $errorCodeInt")
    handler.rstStream(streamId, errorCode)
  }

  private fun readSettings(handler: Handler, length: Int, flags: Int, streamId: Int) {
    if (streamId != 0) throw IOException("TYPE_SETTINGS streamId != 0")
    if (flags and FLAG_ACK != 0) {
      if (length != 0) throw IOException("FRAME_SIZE_ERROR ack frame should be empty!")
      handler.ackSettings()
      return
    }

    if (length % 6 != 0) throw IOException("TYPE_SETTINGS length % 6 != 0: $length")
    val settings = Settings()
    for (i in 0 until length step 6) {
      var id = source.readShort() and 0xffff
      val value = source.readInt()

      when (id) {
        // SETTINGS_HEADER_TABLE_SIZE
        1 -> {
        }

        // SETTINGS_ENABLE_PUSH
        2 -> {
          if (value != 0 && value != 1) {
            throw IOException("PROTOCOL_ERROR SETTINGS_ENABLE_PUSH != 0 or 1")
          }
        }

        // SETTINGS_MAX_CONCURRENT_STREAMS
        3 -> id = 4 // Renumbered in draft 10.

        // SETTINGS_INITIAL_WINDOW_SIZE
        4 -> {
          id = 7 // Renumbered in draft 10.
          if (value < 0) {
            throw IOException("PROTOCOL_ERROR SETTINGS_INITIAL_WINDOW_SIZE > 2^31 - 1")
          }
        }

        // SETTINGS_MAX_FRAME_SIZE
        5 -> {
          if (value < INITIAL_MAX_FRAME_SIZE || value > 16777215) {
            throw IOException("PROTOCOL_ERROR SETTINGS_MAX_FRAME_SIZE: $value")
          }
        }

        // SETTINGS_MAX_HEADER_LIST_SIZE
        6 -> { // Advisory only, so ignored.
        }

        // Must ignore setting with unknown id.
        else -> {
        }
      }
      settings[id] = value
    }
    handler.settings(false, settings)
  }

  private suspend fun readPushPromise(handler: Handler, length: Int, flags: Int, streamId: Int) {
    if (streamId == 0) {
      throw IOException("PROTOCOL_ERROR: TYPE_PUSH_PROMISE streamId == 0")
    }
    val padding = if (flags and FLAG_PADDED != 0) source.readByte() and 0xff else 0
    val promisedStreamId = source.readInt() and 0x7fffffff
    val headerBlockLength = lengthWithoutPadding(length - 4, flags, padding) // - 4 for readInt().
    val headerBlock = readHeaderBlock(headerBlockLength, padding, flags, streamId)
    handler.pushPromise(streamId, promisedStreamId, headerBlock)
  }

  private fun readPing(handler: Handler, length: Int, flags: Int, streamId: Int) {
    if (length != 8) throw IOException("TYPE_PING length != 8: $length")
    if (streamId != 0) throw IOException("TYPE_PING streamId != 0")
    val payload1 = source.readInt()
    val payload2 = source.readInt()
    val ack = flags and FLAG_ACK != 0
    handler.ping(ack, payload1, payload2)
  }

  private fun readGoAway(handler: Handler, length: Int, flags: Int, streamId: Int) {
    if (length < 8) throw IOException("TYPE_GOAWAY length < 8: $length")
    if (streamId != 0) throw IOException("TYPE_GOAWAY streamId != 0")
    val lastStreamId = source.readInt()
    val errorCodeInt = source.readInt()
    val opaqueDataLength = length - 8
    val errorCode = ErrorCode.fromHttp2(errorCodeInt)
            ?: throw IOException(
        "TYPE_GOAWAY unexpected error code: $errorCodeInt")
    var debugData = ByteString.EMPTY
    if (opaqueDataLength > 0) { // Must read debug data in order to not corrupt the connection.
      debugData = source.readByteString(opaqueDataLength.toLong())
    }
    handler.goAway(lastStreamId, errorCode, debugData)
  }

  private fun readWindowUpdate(handler: Handler, length: Int, flags: Int, streamId: Int) {
    if (length != 4) throw IOException("TYPE_WINDOW_UPDATE length !=4: $length")
    val increment = source.readInt() and 0x7fffffffL
    if (increment == 0L) throw IOException("windowSizeIncrement was 0")
    handler.windowUpdate(streamId, increment)
  }

  fun close() {
    startSafeCoroutine {
      socket.close()
    }
  }

  interface Handler {
    fun data(inFinished: Boolean, streamId: Int, source: BufferedSource, length: Int)

    /**
     * Create or update incoming headers, creating the corresponding streams if necessary. Frames
     * that trigger this are HEADERS and PUSH_PROMISE.
     *
     * @param inFinished true if the sender will not send further frames.
     * @param streamId the stream owning these headers.
     * @param associatedStreamId the stream that triggered the sender to create this stream.
     */
    fun headers(
      inFinished: Boolean,
      streamId: Int,
      associatedStreamId: Int,
      headerBlock: List<Header>
    )

    fun rstStream(streamId: Int, errorCode: ErrorCode)

    fun settings(clearPrevious: Boolean, settings: Settings)

    /** HTTP/2 only. */
    fun ackSettings()

    /**
     * Read a connection-level ping from the peer. `ack` indicates this is a reply. The data
     * in `payload1` and `payload2` opaque binary, and there are no rules on the content.
     */
    fun ping(
      ack: Boolean,
      payload1: Int,
      payload2: Int
    )

    /**
     * The peer tells us to stop creating streams. It is safe to replay streams with
     * `ID > lastGoodStreamId` on a new connection.  In- flight streams with
     * `ID <= lastGoodStreamId` can only be replayed on a new connection if they are idempotent.
     *
     * @param lastGoodStreamId the last stream ID the peer processed before sending this message. If
     *     [lastGoodStreamId] is zero, the peer processed no frames.
     * @param errorCode reason for closing the connection.
     * @param debugData only valid for HTTP/2; opaque debug data to send.
     */
    fun goAway(
            lastGoodStreamId: Int,
            errorCode: ErrorCode,
            debugData: ByteString
    )

    /**
     * Notifies that an additional `windowSizeIncrement` bytes can be sent on `streamId`, or the
     * connection if `streamId` is zero.
     */
    fun windowUpdate(
      streamId: Int,
      windowSizeIncrement: Long
    )

    /**
     * Called when reading a headers or priority frame. This may be used to change the stream's
     * weight from the default (16) to a new value.
     *
     * @param streamId stream which has a priority change.
     * @param streamDependency the stream ID this stream is dependent on.
     * @param weight relative proportion of priority in `[1..256]`.
     * @param exclusive inserts this stream ID as the sole child of `streamDependency`.
     */
    fun priority(
      streamId: Int,
      streamDependency: Int,
      weight: Int,
      exclusive: Boolean
    )

    /**
     * HTTP/2 only. Receive a push promise header block.
     *
     * A push promise contains all the headers that pertain to a server-initiated request, and a
     * `promisedStreamId` to which response frames will be delivered. Push promise frames are sent
     * as a part of the response to `streamId`.
     *
     * @param streamId client-initiated stream ID.  Must be an odd number.
     * @param promisedStreamId server-initiated stream ID.  Must be an even number.
     * @param requestHeaders minimally includes `:method`, `:scheme`, `:authority`, and `:path`.
     */
    fun pushPromise(
      streamId: Int,
      promisedStreamId: Int,
      requestHeaders: List<Header>
    )

    /**
     * HTTP/2 only. Expresses that resources for the connection or a client- initiated stream are
     * available from a different network location or protocol configuration.
     *
     * See [alt-svc][alt_svc].
     *
     * [alt_svc]: http://tools.ietf.org/html/draft-ietf-httpbis-alt-svc-01
     *
     * @param streamId when a client-initiated stream ID (odd number), the origin of this alternate
     *     service is the origin of the stream. When zero, the origin is specified in the `origin`
     *     parameter.
     * @param origin when present, the [origin](http://tools.ietf.org/html/rfc6454) is typically
     *     represented as a combination of scheme, host and port. When empty, the origin is that of
     *     the `streamId`.
     * @param protocol an ALPN protocol, such as `h2`.
     * @param host an IP address or hostname.
     * @param port the IP port associated with the service.
     * @param maxAge time in seconds that this alternative is considered fresh.
     */
    fun alternateService(
            streamId: Int,
            origin: String,
            protocol: ByteString,
            host: String,
            port: Int,
            maxAge: Long
    )
  }

  companion object {
    fun lengthWithoutPadding(length: Int, flags: Int, padding: Int): Int {
      var result = length
      if (flags and FLAG_PADDED != 0) result-- // Account for reading the padding length.
      if (padding > result) {
        throw IOException("PROTOCOL_ERROR padding $padding > remaining length $result")
      }
      result -= padding
      return result
    }
  }
}
