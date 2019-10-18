package com.koushikdutta.scratch

expect open class IOException(): Exception {
    constructor(message: String)
}