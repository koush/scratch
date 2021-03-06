package com.koushikdutta.scratch.http.body

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.collections.StringMultimap
import com.koushikdutta.scratch.collections.getFirst
import com.koushikdutta.scratch.collections.parseSemicolonDelimited
import com.koushikdutta.scratch.http.*
import com.koushikdutta.scratch.http.Parser.Companion.ensureReadString
import com.koushikdutta.scratch.http.client.getHttpBody
import kotlin.random.Random

private fun createPartBoundaries(boundary: String, parts: AsyncIterator<Part>): AsyncIterator<AsyncRead> {
    return asyncIterator {
        yield(ByteBufferList().putUtf8String("--").putUtf8String(boundary).createReader())
        while (parts.hasNext()) {
            yield(ByteBufferList().putUtf8String("\r\n").createReader())
            val part = parts.next()
            yield(ByteBufferList().putUtf8String(part.headers.toHeaderString()).createReader())
            yield(part.body)
            yield(ByteBufferList().putUtf8String("\r\n--").putUtf8String(boundary).createReader())
        }
        yield(ByteBufferList().putUtf8String("--\r\n").createReader())
    }
}

/**
 * Multipart content.
 * Parts are represented by an AsyncIterator.
 * Each part is valid for reading until iterator.next() (or hasNext) is called for the next part,
 * at which point all unread part data will be skipped.
 */
class Multipart : AsyncHttpMessageContent {
    override val contentType: String

    var partRead: AsyncRead? = null
    override suspend fun read(buffer: WritableBuffers): Boolean {
        if (partRead == null)
            partRead = createPartBoundaries(boundary, iterator).join()
        return partRead!!(buffer)
    }

    override suspend fun close() {
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
                    val partRead = AsyncRead {
                        if (partEnd)
                            return@AsyncRead false

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
    constructor(headers: Headers = Headers(), body: AsyncHttpMessageContent) : this(headers, body as AsyncRead) {
        headers.contentLength = body.contentLength
        if (headers.contentType == null)
            headers.contentType = body.contentType
    }

    val contentType: String?
        get() {
            return headers.get("Content-Type")
        }

    val contentDisposition: StringMultimap?
        get() {
            val contentDisposition = headers["Content-Disposition"] ?: return null
            return parseSemicolonDelimited(contentDisposition)
        }
}

val Part.filename: String?
    get() {
        return contentDisposition?.getFirst("filename")
    }

val Part.name: String?
    get() {
        return contentDisposition?.getFirst("name")
    }

val Headers.multipartBoundary: String?
    get() {
        if (contentType == null)
            return null
        val map = parseSemicolonDelimited(contentType)
        return map.getFirst("boundary")
    }
