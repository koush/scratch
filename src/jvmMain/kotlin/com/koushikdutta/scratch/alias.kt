package com.koushikdutta.scratch

import java.io.IOException

actual typealias IOException = IOException
internal actual fun exitProcess(throwable: Throwable): Nothing {
    throwable.printStackTrace()
    kotlin.system.exitProcess(-1)
}
