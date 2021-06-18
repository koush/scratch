package com.koushikdutta.scratch.uri

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

actual typealias URI = URI

actual val URI.query
    get() = query
actual val URI.scheme
    get() = scheme
actual val URI.rawPath
    get() = rawPath
actual val URI.rawQuery
    get() = rawQuery
actual val URI.fragment
    get() = fragment
actual val URI.host
    get() = host
actual val URI.path
    get() = path
actual val URI.port
    get() = port

actual object URLDecoder {
    actual fun decode(s: String) = URLDecoder.decode(s)
}

actual object URLEncoder {
    actual fun encode(s: String) = URLEncoder.encode(s)
}
