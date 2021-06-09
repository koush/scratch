package com.koushikdutta.scratch.uri

expect class URI(str: String) {
    fun resolve(str: String): URI
    fun resolve(uri: URI): URI
}

expect val URI.rawQuery: String?
expect val URI.rawPath: String?
expect val URI.query: String?
expect val URI.scheme: String?
expect val URI.fragment: String?
expect val URI.host: String?
expect val URI.path: String?
expect val URI.port: Int

expect object URLDecoder {
    fun decode(s: String): String
}
expect object URLEncoder {
    fun encode(s: String): String
}
