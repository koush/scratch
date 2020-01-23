package com.koushikdutta.scratch

actual open class IOException: Exception {
    actual constructor(): super()
    actual constructor(message: String): super(message)
}

actual fun exitProcess(throwable: Throwable): Nothing {
    println(throwable)
    kotlin.system.exitProcess(-1)
}
