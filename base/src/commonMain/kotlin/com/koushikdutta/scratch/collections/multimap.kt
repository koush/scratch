package com.koushikdutta.scratch.collections

typealias Multimap<K, V> = MutableMap<K, MutableList<V>>
typealias StringMultimap = Multimap<String, String?>

fun <K, V> Multimap<K, V>.add(key: K, value: V) {
    val list = getOrPut(key, { mutableListOf()} )
    list.add(value)
}

fun <K, V> Multimap<K, V>.removeValue(key: K, value: V) {
    val list = get(key) ?: return
    list.remove(value)
}

fun <K, V> Multimap<K, V>.pop(key: K): V? {
    val list = get(key)
    if (list == null || list.isEmpty())
        return null
    return list.removeAt(0)
}

fun <K, V> Multimap<K, V>.getFirst(key: K): V? {
    val list = get(key)
    if (list == null || list.isEmpty())
        return null
    return list.get(0)
}

fun <K, V> Multimap<K, V>.toSingleMap(): Map<K, V> {
    val ret = mutableMapOf<K, V>()
    for (entry in entries) {
        for (value in entry.value) {
            ret[entry.key] = value
            break
        }
    }
    return ret
}

typealias StringDecoder = (s: String) -> String;

fun parseStringMultimap(value: String?, delimiter: String, unquote: Boolean, decoder: StringDecoder? = null): StringMultimap {
    return parseStringMultimap(value, delimiter, "=", unquote, decoder)
}

fun parseStringMultimap(value: String?, delimiter: String, assigner: String, unquote: Boolean, decoder: StringDecoder?): StringMultimap {
    val map = mutableMapOf<String, MutableList<String?>>()
    if (value == null)
        return map
    val parts = value.split(delimiter.toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
    for (part in parts) {
        val pair = part.split(assigner.toRegex(), 2).toTypedArray()
        var key = pair[0].trim { it <= ' ' }
        // watch for empty string or trailing delimiter
        if (key.isEmpty())
            continue
        var v: String? = null
        if (pair.size > 1)
            v = pair[1]
        if (unquote && v != null && v.endsWith("\"") && v.startsWith("\""))
            v = v.substring(1, v.length - 1)
        if (decoder != null && v != null) {
            key = decoder(key)
            v = decoder(v)
        }
        map.add(key, v)
    }
    return map
}


fun parseSemicolonDelimited(header: String?): StringMultimap {
    return parseStringMultimap(header, ";", true, null)
}

fun parseCommaDelimited(header: String?): StringMultimap {
    return parseStringMultimap(header, ",", true, null)
}

fun StringMultimap.toString(keyDelimiter: String = "\n", valuesDelimiter: String = ", ", quote: Boolean = true): String {
    return keys.joinToString(keyDelimiter) {
        val values = get(it)!!.map {  value ->
            if (quote)
                "\"$value\""
            else
                value
        }.joinToString(valuesDelimiter)
        "$it=$values"
    }
}
