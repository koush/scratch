package com.koushikdutta.scratch.uri

internal fun Char.isISOControl(): Boolean {
    val codePoint = this.toInt()
    return codePoint <= 0x9F &&
            (codePoint >= 0x7F || (codePoint ushr 5 == 0));
}
