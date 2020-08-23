package com.koushikdutta.scratch

actual open class Promise<T> : PromiseBase<T> {
    actual constructor(block: suspend () -> T): super(block)
    internal actual constructor(): super()
}
