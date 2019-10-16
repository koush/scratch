package com.koushikdutta.scratch

//expect fun <R> synchronized(lock: Any, block: () -> R): R

expect inline fun <R> synchronized(lock: Any, block: () -> R): R