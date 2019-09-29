package com.koushikdutta.scratch

public class Log {
    companion object {
        fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
            println("$level $tag $message $throwable")
        }

        fun i(tag: String, message: String, throwable: Throwable? = null) = log ("i", tag, message, throwable)
        fun v(tag: String, message: String, throwable: Throwable? = null) = log ("v", tag, message, throwable)
        fun e(tag: String, message: String, throwable: Throwable? = null) = log ("e", tag, message, throwable)
        fun w(tag: String, message: String, throwable: Throwable? = null) = log ("w", tag, message, throwable)
    }
}