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

import com.koushikdutta.scratch.IOException
import com.koushikdutta.scratch.http.http2.*
import com.koushikdutta.scratch.http.http2.okhttp.Http2.CONNECTION_PREFACE
import com.koushikdutta.scratch.http.http2.okhttp.Http2.FLAG_ACK
import com.koushikdutta.scratch.http.http2.okhttp.Http2.FLAG_END_HEADERS
import com.koushikdutta.scratch.http.http2.okhttp.Http2.FLAG_END_STREAM
import com.koushikdutta.scratch.http.http2.okhttp.Http2.FLAG_NONE
import com.koushikdutta.scratch.http.http2.okhttp.Http2.INITIAL_MAX_FRAME_SIZE
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_CONTINUATION
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_DATA
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_GOAWAY
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_HEADERS
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_PING
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_PUSH_PROMISE
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_RST_STREAM
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_SETTINGS
import com.koushikdutta.scratch.http.http2.okhttp.Http2.TYPE_WINDOW_UPDATE

/** Writes HTTP/2 transport frames. */
@Suppress("NAME_SHADOWING")
internal class Http2Writer(
        /** Creates a frame reader with max header table size of 4096. */
  private val sink: Buffer,
        private val client: Boolean
) {
  private val hpackBuffer: Buffer = Buffer()
  private var maxFrameSize: Int = INITIAL_MAX_FRAME_SIZE
  private var closed: Boolean = false
  val hpackWriter: Hpack.Writer = Hpack.Writer(out = hpackBuffer)

  fun connectionPreface() {
    if (closed) throw IOException("closed")
    if (!client) return // Nothing to write; servers don't send connection headers!
    sink.write(CONNECTION_PREFACE)
    sinkFlush()
  }

  /** Applies `peerSettings` and then sends a settings ACK. */
  fun applyAndAckSettings(peerSettings: Settings) {
    if (closed) throw IOException("closed")
    this.maxFrameSize = peerSettings.getMaxFrameSize(maxFrameSize)
    if (peerSettings.headerTableSize != -1) {
      hpackWriter.resizeHeaderTable(peerSettings.headerTableSize)
    }
    frameHeader(
        streamId = 0,
        length = 0,
        type = TYPE_SETTINGS,
        flags = FLAG_ACK
    )
    sinkFlush()
  }

  /**
   * HTTP/2 only. Send a push promise header block.
   *
   * A push promise contains all the headers that pertain to a server-initiated request, and a
   * `promisedStreamId` to which response frames will be delivered. Push promise frames are sent as
   * a part of the response to `streamId`. The `promisedStreamId` has a priority of one greater than
   * `streamId`.
   *
   * @param streamId client-initiated stream ID.  Must be an odd number.
   * @param promisedStreamId server-initiated stream ID.  Must be an even number.
   * @param requestHeaders minimally includes `:method`, `:scheme`, `:authority`, and `:path`.
   */
  fun pushPromise(
    streamId: Int,
    promisedStreamId: Int,
    requestHeaders: List<Header>
  ) {
    if (closed) throw IOException("closed")
    hpackWriter.writeHeaders(requestHeaders)

    val byteCount = hpackBuffer.remaining().toLong()
    val length = minOf(maxFrameSize - 4L, byteCount).toInt()
    frameHeader(
        streamId = streamId,
        length = length + 4,
        type = TYPE_PUSH_PROMISE,
        flags = if (byteCount == length.toLong()) FLAG_END_HEADERS else 0
    )
    sink.writeInt(promisedStreamId and 0x7fffffff)
    sink.write(hpackBuffer, length.toLong())

    if (byteCount > length) writeContinuationFrames(streamId, byteCount - length)
  }

  private fun sinkFlush() {
    
  }
  
  fun flush() {
    if (closed) throw IOException("closed")
    sinkFlush()
  }

  fun rstStream(streamId: Int, errorCode: ErrorCode) {
    if (closed) throw IOException("closed")
    require(errorCode.httpCode != -1)

    frameHeader(
        streamId = streamId,
        length = 4,
        type = TYPE_RST_STREAM,
        flags = FLAG_NONE
    )
    sink.writeInt(errorCode.httpCode)
    sinkFlush()
  }

  /** The maximum size of bytes that may be sent in a single call to [data]. */
  fun maxDataLength(): Int = maxFrameSize

  /**
   * `source.length` may be longer than the max length of the variant's data frame. Implementations
   * must send multiple frames as necessary.
   *
   * @param source the buffer to draw bytes from. May be null if byteCount is 0.
   * @param byteCount must be between 0 and the minimum of `source.length` and [maxDataLength].
   */
  fun data(outFinished: Boolean, streamId: Int, source: Buffer?, byteCount: Int) {
    if (closed) throw IOException("closed")
    var flags = FLAG_NONE
    if (outFinished) flags = flags or FLAG_END_STREAM
    dataFrame(streamId, flags, source, byteCount)
  }

  fun dataFrame(streamId: Int, flags: Int, buffer: Buffer?, byteCount: Int) {
    frameHeader(
        streamId = streamId,
        length = byteCount,
        type = TYPE_DATA,
        flags = flags
    )
    if (byteCount > 0) {
      sink.write(buffer!!, byteCount.toLong())
    }
  }

  /** Write okhttp's settings to the peer. */
  fun settings(settings: Settings) {
    if (closed) throw IOException("closed")
    frameHeader(
        streamId = 0,
        length = settings.size() * 6,
        type = TYPE_SETTINGS,
        flags = FLAG_NONE
    )
    for (i in 0 until Settings.COUNT) {
      if (!settings.isSet(i)) continue
      val id = when (i) {
        4 -> 3 // SETTINGS_MAX_CONCURRENT_STREAMS renumbered.
        7 -> 4 // SETTINGS_INITIAL_WINDOW_SIZE renumbered.
        else -> i
      }
      sink.writeShort(id)
      sink.writeInt(settings[i])
    }
    sinkFlush()
  }

  /**
   * Send a connection-level ping to the peer. `ack` indicates this is a reply. The data in
   * `payload1` and `payload2` opaque binary, and there are no rules on the content.
   */
  fun ping(ack: Boolean, payload1: Int, payload2: Int) {
    if (closed) throw IOException("closed")
    frameHeader(
        streamId = 0,
        length = 8,
        type = TYPE_PING,
        flags = if (ack) FLAG_ACK else FLAG_NONE
    )
    sink.writeInt(payload1)
    sink.writeInt(payload2)
    sinkFlush()
  }

  /**
   * Tell the peer to stop creating streams and that we last processed `lastGoodStreamId`, or zero
   * if no streams were processed.
   *
   * @param lastGoodStreamId the last stream ID processed, or zero if no streams were processed.
   * @param errorCode reason for closing the connection.
   * @param debugData only valid for HTTP/2; opaque debug data to send.
   */
  fun goAway(lastGoodStreamId: Int, errorCode: ErrorCode, debugData: ByteArray) {
    if (closed) throw IOException("closed")
    require(errorCode.httpCode != -1) { "errorCode.httpCode == -1" }
    frameHeader(
        streamId = 0,
        length = 8 + debugData.size,
        type = TYPE_GOAWAY,
        flags = FLAG_NONE
    )
    sink.writeInt(lastGoodStreamId)
    sink.writeInt(errorCode.httpCode)
    if (debugData.isNotEmpty()) {
      sink.write(debugData)
    }
    sinkFlush()
  }

  /**
   * Inform peer that an additional `windowSizeIncrement` bytes can be sent on `streamId`, or the
   * connection if `streamId` is zero.
   */
  fun windowUpdate(streamId: Int, windowSizeIncrement: Long) {
    if (closed) throw IOException("closed")
    require(windowSizeIncrement != 0L && windowSizeIncrement <= 0x7fffffffL) {
      "windowSizeIncrement == 0 || windowSizeIncrement > 0x7fffffffL: $windowSizeIncrement"
    }
    frameHeader(
        streamId = streamId,
        length = 4,
        type = TYPE_WINDOW_UPDATE,
        flags = FLAG_NONE
    )
    sink.writeInt(windowSizeIncrement.toInt())
    sinkFlush()
  }

  fun frameHeader(streamId: Int, length: Int, type: Int, flags: Int) {
    require(length <= maxFrameSize) { "FRAME_SIZE_ERROR length > $maxFrameSize: $length" }
    require(streamId and 0x80000000.toInt() == 0) { "reserved bit set: $streamId" }
    sink.writeMedium(length)
    sink.writeByte(type and 0xff)
    sink.writeByte(flags and 0xff)
    sink.writeInt(streamId and 0x7fffffff)
  }

  private fun writeContinuationFrames(streamId: Int, byteCount: Long) {
    var byteCount = byteCount
    while (byteCount > 0L) {
      val length = minOf(maxFrameSize.toLong(), byteCount)
      byteCount -= length
      frameHeader(
          streamId = streamId,
          length = length.toInt(),
          type = TYPE_CONTINUATION,
          flags = if (byteCount == 0L) FLAG_END_HEADERS else 0
      )
      sink.write(hpackBuffer, length)
    }
  }

  fun headers(
    outFinished: Boolean,
    streamId: Int,
    headerBlock: List<Header>
  ) {
    if (closed) throw IOException("closed")
    hpackWriter.writeHeaders(headerBlock)

    val byteCount = hpackBuffer.remaining().toLong()
    val length = minOf(maxFrameSize.toLong(), byteCount)
    var flags = if (byteCount == length) FLAG_END_HEADERS else 0
    if (outFinished) flags = flags or FLAG_END_STREAM
    frameHeader(
        streamId = streamId,
        length = length.toInt(),
        type = TYPE_HEADERS,
        flags = flags
    )
    sink.write(hpackBuffer, length)

    if (byteCount > length) writeContinuationFrames(streamId, byteCount - length)
  }
}
