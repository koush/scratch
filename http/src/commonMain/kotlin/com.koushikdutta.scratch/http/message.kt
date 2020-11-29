package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncInput
import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.collections.StringMultimap
import com.koushikdutta.scratch.uri.URI
import com.koushikdutta.scratch.uri.parseQuery
import com.koushikdutta.scratch.uri.rawPath
import com.koushikdutta.scratch.uri.rawQuery

typealias AsyncHttpMessageCompletion = suspend(throwable: Throwable?) -> Unit

abstract class AsyncHttpMessage: AsyncInput {
    val headers: Headers
    protected abstract val messageLine: String
    var body: AsyncRead? = null
        internal set
    abstract val protocol: String
    private val sent: AsyncHttpMessageCompletion?

    constructor(headers: Headers, body: AsyncRead?, sent: AsyncHttpMessageCompletion? = null) {
        this.headers = headers
        this.body = body
        this.sent = sent
    }

    fun toMessageString(): String {
        return headers.toHeaderString(messageLine)
    }

    override fun toString(): String {
        return toMessageString()
    }

    suspend fun close(throwable: Throwable? = null) {
        sent?.invoke(throwable)
    }

    override suspend fun close() = close(null)
    override suspend fun read(buffer: WritableBuffers) = body?.invoke(buffer) ?: false
}

class RequestLine {
    val method: String
    val message: String
    val uri: URI?
    val protocol: String

    constructor(method: String, uri: URI, protocol: String) {
        this.uri = uri
        this.method = method
        this.protocol = protocol
        this.message = uri.toString()
    }

    constructor(method: String, message: String, protocol: String) {
        this.message = message
        this.uri = try {
             URI(message)
        }
        catch (_: Throwable) {
            null
        }
        this.method = method
        this.protocol = protocol
    }

    constructor(requestLine: String) {
        val parts = requestLine.trim().split(" ")
        require(parts.size == 3) { "invalid request line $requestLine" }

        method = parts[0]
        this.message = parts[1]
        this.uri = try {
            URI(message)
        }
        catch (_: Throwable) {
            null
        }
        protocol = parts[2]
    }
}

open class AsyncHttpRequest : AsyncHttpMessage {
    val requestLine: RequestLine
    constructor(requestLine: RequestLine, headers: Headers, body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null) : super(headers, body, sent) {
        this.requestLine = requestLine
    }
    constructor(uri: URI, method: String = "GET", protocol: String = "HTTP/1.1", headers: Headers = Headers(), body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null) : this(RequestLine(method, uri, protocol), headers, body, sent)

    override val messageLine: String
        get() = if (requestLine.uri != null) "$method $requestLinePathAndQuery $protocol" else "$method ${requestLine.message} $protocol"

    val method: String
        get() = requestLine.method

    // allow the error to be rethrown by parsing again
    val uri: URI
        get() = requestLine.uri ?: URI(requestLine.message)

    override val protocol: String
        get() = requestLine.protocol

    val requestLinePathAndQuery: String
        get() {
            var path = requestLinePath
            val query = requestLineQuery
            if (path == null || path.isEmpty())
                path = "/"
            if (query == null || query.isEmpty())
                return path
            return "$path?$query"
        }

    protected open val requestLinePath: String?
        get() {
            return uri.rawPath
        }

    protected open  val requestLineQuery: String?
        get() {
            return uri.rawQuery
        }

    // need this for extension methods
    companion object
}

fun AsyncHttpRequest.parseQuery(): StringMultimap {
    return uri.parseQuery()
}

class ResponseLine {
    val code: Int
    val message: String
    val protocol: String

    constructor(code: StatusCode, protocol: String = "HTTP/1.1") : this(code.code, code.message, protocol)

    constructor(code: Int, message: String, protocol: String = "HTTP/1.1") {
        this.code = code
        this.message = message
        this.protocol = protocol
    }

    constructor(responseLine: String) {
        val parts = responseLine.split(Regex(" "), 3)
        require(parts.size >= 2) { "invalid response line $responseLine" }
        protocol = parts[0]
        code = parts[1].toInt()
        message = if (parts.size == 3) parts[2] else ""
    }

    override fun toString(): String {
        return "$protocol $code $message"
    }
}

open class AsyncHttpResponse(val responseLine: ResponseLine, headers: Headers = Headers(), body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null) : AsyncHttpMessage(headers, body, sent) {

    override val messageLine: String
        get() = responseLine.toString()

    override val protocol: String
        get() = responseLine.protocol

    val code: Int
        get() = responseLine.code

    val message: String
        get() = responseLine.message

    // need this for extension methods
    companion object
}

val AsyncHttpResponse.statusCode: StatusCode?
    get() = StatusCode.values().find { code == it.code }