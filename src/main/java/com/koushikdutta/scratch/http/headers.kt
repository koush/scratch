package com.koushikdutta.scratch.http

data class Header(val name: String, val value: String)

class Headers {
    private val headers = mutableListOf<Header>()

    fun remove(name: String) {
        headers.removeIf {
            headerEquals(name, it.name)
        }
    }

    fun add(header: Header) {
        headers.add(header)
    }

    fun set(header: Header) {
        remove(header.name)
        add(header)
    }

    fun set(name: String, value: String) {
        remove(name)
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

    fun get(name: String): String? {
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


    fun toStringBuilder(): StringBuilder {
        val result = StringBuilder(256)
        for (header in headers) {
            result.append(header.name)
                    .append(": ")
                    .append(header.value)
                    .append("\r\n")
        }
        result.append("\r\n")
        return result
    }

    override fun toString(): String {
        return toStringBuilder().toString()
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
