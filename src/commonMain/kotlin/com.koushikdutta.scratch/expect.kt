package com.koushikdutta.scratch

expect open class IOException(): Exception {
    constructor(message: String)
}

internal expect fun exitProcess(throwable: Throwable): Nothing
