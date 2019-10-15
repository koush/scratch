package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncRead
import java.net.URI

abstract class AsyncHttpMessage {
    val headers: Headers
    protected abstract val messageLine: String
    var body: AsyncRead? = null
        internal set

    abstract val protocol: String

    constructor(headers: Headers, body: AsyncRead?) {
        this.headers = headers
        this.body = body
    }

    constructor(headers: Headers = Headers(), body: AsyncHttpMessageBody? = null) {
        this.headers = headers
        if (body != null) {
            this.body = body.read
            headers.contentLength = body.contentLength
        }
    }

    fun toMessageString(): String {
        return headers.toHeaderString(messageLine)
    }

    override fun toString(): String {
        return toMessageString()
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

open class AsyncHttpRequest : AsyncHttpMessage {
    private val requestLine: RequestLine
    constructor(requestLine: RequestLine, headers: Headers, body: AsyncRead? = null) : super(headers, body) {
        this.requestLine = requestLine
    }
    constructor(requestLine: RequestLine, headers: Headers, body: AsyncHttpMessageBody?) : super(headers, body) {
        this.requestLine = requestLine
    }
    constructor(uri: URI, method: String = "GET", protocol: String = "HTTP/1.1", headers: Headers = Headers(), body: AsyncRead? = null) : this(RequestLine(method, uri, protocol), headers, body)
    constructor(uri: URI, method: String = "GET", protocol: String = "HTTP/1.1", headers: Headers = Headers(), body: AsyncHttpMessageBody?) : this(RequestLine(method, uri, protocol), headers, body)

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

    val properties: AsyncHttpRequestProperties = mutableMapOf()

    companion object
}

class ResponseLine {
    val code: Int
    val message: String
    val protocol: String

    constructor(code: Int, message: String, protocol: String) {
        this.code = code
        this.message = message
        this.protocol = protocol
    }

    constructor(responseLine: String) {
        val parts = responseLine.split(Regex(" "), 3)
        require(parts.size >= 2) { "invalid response line $responseLine" }
        protocol = parts[0]
        code = Integer.parseInt(parts[1])
        message = if (parts.size == 3) parts[2] else ""
    }

    override fun toString(): String {
        return "$protocol $code $message"
    }
}

class AsyncHttpResponse : AsyncHttpMessage {
    private val responseLine: ResponseLine
    constructor (responseLine: ResponseLine, headers: Headers = Headers(), body: AsyncRead? = null) : super(headers, body) {
        this.responseLine = responseLine
    }
    constructor(responseLine: ResponseLine, headers: Headers = Headers(), body: AsyncHttpMessageBody?) : super(headers, body) {
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

    companion object
}