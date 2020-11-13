package com.koushikdutta.scratch

import java.io.IOException

actual typealias IOException = IOException
internal actual fun exitProcess(throwable: Throwable, originalstack: Throwable): Nothing {
    println("Original Stack Trace:")
    originalstack.printStackTrace()
    println("Cause:")
    throwable.printStackTrace()
    kotlin.system.exitProcess(-1)
}
