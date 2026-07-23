package com.koushikdutta.scratch

import java.io.IOException

typealias IOException = IOException
internal fun exitProcess(throwable: Throwable, originalstack: Throwable): Nothing {
    println("Original Stack Trace:")
    originalstack.printStackTrace()
    println("Cause:")
    throwable.printStackTrace()
    kotlin.system.exitProcess(-1)
}
