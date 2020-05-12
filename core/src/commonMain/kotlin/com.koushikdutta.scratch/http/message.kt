package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncRead
import com.koushikdutta.scratch.uri.URI

typealias AsyncHttpMessageCompletion = suspend(throwable: Throwable?) -> Unit

abstract class AsyncHttpMessage {
    val headers: Headers
    protected abstract val messageLine: String
    var body: AsyncRead? = null
        internal set
    abstract val protocol: String
    internal val sent: AsyncHttpMessageCompletion?

    constructor(headers: Headers, body: AsyncRead?, sent: AsyncHttpMessageCompletion? = null) {
        this.headers = headers
        this.body = body
        this.sent = sent
    }

    constructor(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null, sent: AsyncHttpMessageCompletion? = null) {
        this.headers = headers
        if (body != null) {
            this.body = body.read
            headers.contentLength = body.contentLength
        }
        this.sent = sent
    }

    fun toMessageString(): String {
        return headers.toHeaderString(messageLine)
    }

    override fun toString(): String {
        return toMessageString()
    }

    suspend fun close() {
        sent?.invoke(null)
    }
}


interface AsyncHttpMessageBody {
    val contentType: String?
    val contentLength: Long?
    val read: AsyncRead
}


class RequestLine {
    val method: String
    val uri: URI
    val protocol: String

    constructor(method: String, uri: URI, protocol: String) {
        this.uri = uri
        this.method = method
        this.protocol = protocol
    }

    constructor(requestLine: String) {
        val parts = requestLine.trim().split(" ")
        require(parts.size == 3) { "invalid request line $requestLine" }

        method = parts[0]
        uri = URI.create(parts[1])
        protocol = parts[2]
    }
}

typealias AsyncHttpRequestProperties = MutableMap<String, Any>
typealias AsyncHttpResponseProperties = MutableMap<String, Any>

open class AsyncHttpRequest : AsyncHttpMessage {
    val requestLine: RequestLine
    constructor(requestLine: RequestLine, headers: Headers, body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null) : super(headers, body, sent) {
        this.requestLine = requestLine
    }
    constructor(requestLine: RequestLine, headers: Headers, body: AsyncHttpMessageBody?, sent: AsyncHttpMessageCompletion? = null) : super(headers, body, sent) {
        this.requestLine = requestLine
    }
    constructor(uri: URI, method: String = "GET", protocol: String = "HTTP/1.1", headers: Headers = Headers(), body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null) : this(RequestLine(method, uri, protocol), headers, body, sent)
    constructor(uri: URI, method: String = "GET", protocol: String = "HTTP/1.1", headers: Headers = Headers(), body: AsyncHttpMessageBody?, sent: AsyncHttpMessageCompletion? = null) : this(RequestLine(method, uri, protocol), headers, body, sent)

    override val messageLine: String
        get() = "$method $requestLinePathAndQuery $protocol"

    val method: String
        get() = requestLine.method

    val uri: URI
        get() = requestLine.uri


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

class ResponseLine {
    val code: Int
    val message: String
    val protocol: String

    constructor(code: StatusCode, protocol: String) : this(code.code, code.message, protocol)

    constructor(code: Int, message: String, protocol: String) {
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

open class AsyncHttpResponse : AsyncHttpMessage {
    val responseLine: ResponseLine
    constructor (responseLine: ResponseLine, headers: Headers = Headers(), body: AsyncRead? = null, sent: AsyncHttpMessageCompletion? = null) : super(headers, body, sent) {
        this.responseLine = responseLine
    }
    constructor(responseLine: ResponseLine, headers: Headers = Headers(), body: AsyncHttpMessageBody?, sent: AsyncHttpMessageCompletion? = null) : super(headers, body, sent) {
        this.responseLine = responseLine
    }

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