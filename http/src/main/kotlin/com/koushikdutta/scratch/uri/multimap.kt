package com.koushikdutta.scratch.uri

import com.koushikdutta.scratch.collections.StringDecoder
import com.koushikdutta.scratch.collections.StringMultimap
import com.koushikdutta.scratch.collections.parseStringMultimap


val QUERY_DECODER: StringDecoder = {
    URLDecoder.decode(it)
}

val URL_DECODER: StringDecoder = {
    URLDecoder.decode(it)
}

fun URI.parseQuery(): StringMultimap {
    val ret = parseQuery(rawQuery)
    return ret
}

fun parseQuery(query: String?): StringMultimap {
    return parseStringMultimap(query, "&", false, QUERY_DECODER)
}

fun parseUrlEncoded(query: String?): StringMultimap {
    return parseStringMultimap(query, "&", false, URL_DECODER)
}
