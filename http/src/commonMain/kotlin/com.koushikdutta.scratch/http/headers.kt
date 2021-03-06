package com.koushikdutta.scratch.http

import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.collections.StringMultimap
import com.koushikdutta.scratch.collections.add
import com.koushikdutta.scratch.collections.parseCommaDelimited

suspend fun AsyncReader.readHttpHeaderLine(): String {
    return readScanUtf8String("\r\n").trim()
}

suspend fun AsyncReader.readHeaderBlock(): Headers {
    val headers = Headers()
    while (true) {
        val headerLine = readHttpHeaderLine()
        if (headerLine.isEmpty())
            break
        headers.addLine(headerLine)
    }
    return headers
}

data class Header(val name: String, val value: String)

class Headers : Iterable<Header> {
    private val headers = mutableListOf<Header>()

    val keys
        get() = headers.map { it.name }

    val size
        get() = headers.size

    override operator fun iterator(): Iterator<Header> {
        return headers.iterator()
    }

    fun remove(name: String) {
        headers.removeAll {
            headerEquals(name, it.name)
        }
    }

    fun add(header: Header) {
        headers.add(header)
    }

    fun addMap(map: Map<String, String>) {
        for (entry in map.entries) {
            add(entry.key, entry.value)
        }
    }

    fun addAll(name: String, vararg values: String) {
        for (value in values) {
            add(name, value)
        }
    }

    fun set(header: Header) {
        remove(header.name)
        add(header)
    }

    operator fun set(name: String, value: String?) {
        remove(name)
        if (value != null)
            add(name, value)
    }

    fun add(name: String, value: String) {
        add(Header(name, value))
    }

    fun addLine(line: String) {
        val trimmed = line.trim()
        val parts = trimmed.split(Regex(":"), 2)
        if (parts.size == 2)
            add(parts[0].trim(), parts[1].trim())
        else
            add(parts[0].trim(), "")
    }

    operator fun get(name: String): String? {
        return headers.firstOrNull {
            headerEquals(it.name, name)
        }?.value
    }

    fun getAll(name: String): List<String> {
        return headers.filter { headerEquals(it.name, name) }.map { it.value }
    }

    fun contains(name: String): Boolean {
        return get(name) != null
    }

    fun toHeaderString(prefix: String = ""): String {
        val result = StringBuilder(256)
        if (prefix.isNotEmpty()) {
            result.append(prefix)
            result.append("\r\n")
        }
        for (header in headers) {
            result.append(header.name)
                    .append(": ")
                    .append(header.value)
                    .append("\r\n")
        }
        result.append("\r\n")
        return result.toString()
    }

    override fun toString(): String {
        return toHeaderString()
    }

    companion object {
        fun headerEquals(h1: String, h2: String): Boolean {
            return h1.equals(h2, true)
        }

        fun parse(payload: String): Headers {
            val lines = payload.split("\n")

            val headers = Headers()
            for (line in lines) {
                val trimmed = line.trim(' ').trim('\r').trim('\n')
                if (trimmed.isEmpty())
                    continue

                headers.addLine(line)
            }
            return headers
        }
    }
}

private fun Headers.setOrRemove(name: String, value: Any?) {
    if (value != null)
        set(name, value.toString())
    else
        remove(name)
}

var Headers.contentLength: Long?
    get() = get("Content-Length")?.toLong()
    set(value) {
        setOrRemove("Content-Length", value)
    }


var Headers.contentType: String?
    get() = get("Content-Type")
    set(value) {
        setOrRemove("Content-Type", value)
    }


var Headers.transferEncoding: String?
    get() = get("Transfer-Encoding")
    set(value) { setOrRemove("Transfer-Encoding", value) }

val Headers.acceptEncodingDeflate: Boolean
    get() {
        val accept = get("Accept-Encoding")
        if (accept == null || accept.isEmpty())
            return false
        val map = parseCommaDelimited(accept)
        return map.contains("deflate")
    }

var Headers.location: String?
    get() = get("Location") as String
    set(value) { setOrRemove("Location", value) }

fun Headers.toStringMultimap(): StringMultimap {
    val ret: StringMultimap = mutableMapOf()

    for (header in this) {
        ret.add(header.name, header.value)
    }

    return ret
}