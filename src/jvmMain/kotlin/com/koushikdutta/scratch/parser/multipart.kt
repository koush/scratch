package com.koushikdutta.scratch.parser

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.collections.StringMultimap
import com.koushikdutta.scratch.collections.getFirst
import com.koushikdutta.scratch.collections.parseSemicolonDelimited
import com.koushikdutta.scratch.http.AsyncHttpMessageBody
import com.koushikdutta.scratch.http.Headers
import com.koushikdutta.scratch.http.client.middleware.getHttpBody
import com.koushikdutta.scratch.http.contentLength
import com.koushikdutta.scratch.http.readHeaderBlock
import com.koushikdutta.scratch.parser.Parser.Companion.ensureReadString
import kotlin.random.Random

private fun createPartBoundaries(boundary: String, parts: AsyncIterator<Part>): AsyncIterator<AsyncRead> {
    return asyncIterator {
        yield(ByteBufferList().putUtf8String("--").putUtf8String(boundary).reader())
        while (parts.hasNext()) {
            yield(ByteBufferList().putUtf8String("\r\n").reader())
            val part = parts.next()
            yield(ByteBufferList().putUtf8String(part.headers.toHeaderString()).reader())
            yield(part.body)
            yield(ByteBufferList().putUtf8String("\r\n--").putUtf8String(boundary).reader())
        }
        yield(ByteBufferList().putUtf8String("--\r\n").reader())
    }
}

/**
 * Multipart content.
 * Parts are represented by an AsyncIterator.
 * Each part is valid for reading until iterator.next() (or hasNext) is called for the next part,
 * at which point all unread part data will be skipped.
 */
class Multipart : AsyncHttpMessageBody {
    override val contentType: String

    var partRead: AsyncRead? = null
    override val read: AsyncRead
        get() = read@{
            if (partRead == null)
                partRead = createPartBoundaries(boundary, iterator).join()
            partRead!!(it)
        }

    override val contentLength: Long? = null

    internal constructor(iterator: AsyncIterator<Part>, contentType: String) {
        this.iterator = iterator
        this.contentType = contentType
    }

    constructor(parts: Iterable<Part>) : this(createAsyncIterable(parts).iterator())

    constructor(iterator: AsyncIterator<Part>) : this(iterator, "multipart/form-data;boundary=----${randomHex()}")

    val boundary: String
        get() {
            return parseSemicolonDelimited(contentType).getFirst("boundary")!!
        }

    private val iterator: AsyncIterator<Part>
    operator fun iterator(): AsyncIterator<Part> {
        return iterator
    }

    companion object {
        fun randomHex(): String {
            val bytes = ByteArray(8)
            Random.Default.nextBytes(bytes)
            return bytes.joinToString("") { (it.toInt() and 0x000000FF).toString(16) }
        }

        /*
        Mac:work$ curl -F person=anonymous -F secret=@test.kt  http://localhost:5555

        POST / HTTP/1.1
        Host: localhost:5555
        User-Agent: curl/7.54.0
        Content-Length: 372
        Expect: 100-continue
        Content-Type: multipart/form-data; boundary=------------------------17903558439eb6ff

        --------------------------17903558439eb6ff              <--- note! two dashes before boundary
        Content-Disposition: form-data; name="person"

        anonymous
        --------------------------17903558439eb6ff              <--- note! two dashes before boundary
        Content-Disposition: form-data; name="secret"; filename="test.kt"
        Content-Type: application/octet-stream

        fun main(args: Array<String>) {
            println("Hello JavaScript!")
        }

        --------------------------17903558439eb6ff--            <--- note! two dashes before AND after boundary
        */
        private const val CRLF = "\r\n"

        suspend fun parseMultipart(boundary: String, reader: AsyncReader): Multipart {
            val boundaryStart = "--$boundary"
            val boundaryBreak = "\r\n--$boundary"
            val boundaryBreakBytes = boundaryBreak.encodeToByteArray()
            val boundaryEnd = "--\r\n"

            ensureReadString(reader, boundaryStart)

            val partBuffer = ByteBufferList()
            val drain = ByteBufferList()

            return Multipart(asyncIterator {
                while (true) {
                    // a completed multipart body ends with a boundary end of --\r\n.
                    if (reader.peekString(boundaryEnd.length) == boundaryEnd) {
                        reader.skip(boundaryEnd.length)
                        break
                    }

                    // otherwise, expecting a CRLF
                    ensureReadString(reader, CRLF)

                    val headers = reader.readHeaderBlock()

                    var partEnd = false
                    val partRead: AsyncRead = read@{
                        if (partEnd)
                            return@read false

                        val scan = reader.readScanChunk(partBuffer, boundaryBreakBytes)
                        if (scan == false)
                            throw Exception("Multipart stream ended unexpectedly.")

                        var reading = partBuffer.remaining()
                        // cut out the boundary if it was found
                        if (scan == true) {
                            // note that this part has ended, subsequent read will eos
                            partEnd = true
                            reading -= boundaryBreakBytes.size
                        }

                        partBuffer.read(it, reading)
                        partBuffer.free()

                        true
                    }

                    // content-length is valid in a part.
                    val partBody = getHttpBody(headers, AsyncReader(partRead), false)

                    yield(Part(headers, partBody))

                    // upon resume, ensure that the part was fully read until the next boundary.
                    while (partRead(drain)) {
                        drain.free()
                    }
                }
            })
        }

    }
}

class Part(val headers: Headers = Headers(), val body: AsyncRead) {
    constructor(headers: Headers = Headers(), body: AsyncHttpMessageBody) : this(headers, body.read){
        headers.contentLength = body.contentLength
    }

    val contentType: String?
        get() {
            return headers.get("Content-Type")
        }

    val contentDisposition: StringMultimap?
        get() {
            val contentDisposition = headers.get("Content-Disposition") ?: return null
            return parseSemicolonDelimited(contentDisposition)
        }

    val filename: String?
        get() {
            return contentDisposition?.getFirst("filename")
        }
}
