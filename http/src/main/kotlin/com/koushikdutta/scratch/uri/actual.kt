package com.koushikdutta.scratch.uri

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

typealias URI = URI

val URI.query
    get() = query
val URI.scheme
    get() = scheme
val URI.rawPath
    get() = rawPath
val URI.rawQuery
    get() = rawQuery
val URI.fragment
    get() = fragment
val URI.host
    get() = host
val URI.path
    get() = path
val URI.port
    get() = port

object URLDecoder {
    fun decode(s: String) = URLDecoder.decode(s)
}

object URLEncoder {
    fun encode(s: String) = URLEncoder.encode(s)
}
