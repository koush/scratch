package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncRead
import java.net.URI

abstract class AsyncHttpMessage(val headers: Headers = Headers()) {
    protected abstract val messageLine: String
    open var protocol: String = "HTTP/1.1"
        protected set

    fun toMessageString(): String {
        return "${messageLine}\r\n${headers}\r\n"
    }

    override fun toString(): String {
        return toMessageString()
    }
}

open class AsyncHttpRequest(val uri: URI, val method: String = "GET", private val body: AsyncRead? = null) : AsyncHttpMessage() {
    override val messageLine: String
        get() = "$method $requestLinePathAndQuery $protocol"

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

    override var protocol: String = "HTTP/1.1"
    val properties = mutableMapOf<String, Any>()

    init {
        headers.add("Host", uri.host)
        headers.add("User-Agent", "ion/1.0")
    }
}

class AsyncHttpResponse internal constructor(override val messageLine: String, headers: Headers = Headers(), body: AsyncRead? = null) : AsyncHttpMessage(headers) {
    val code: Int
    val message: String
    var body: AsyncRead? = body
        internal set

    init {
        val parts = messageLine.split(Regex(" "), 3)
        require(parts.size >= 2) { "invalid status line" }
        protocol = parts[0]
        code = Integer.parseInt(parts[1])
        message = if (parts.size == 3) parts[2] else ""
    }
}