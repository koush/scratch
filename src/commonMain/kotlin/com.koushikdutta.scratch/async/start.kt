package com.koushikdutta.scratch.async

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.Promise
import com.koushikdutta.scratch.exitProcess
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class UnhandledAsyncExceptionError(throwable: Throwable): Error(throwable)

/**
 * callers of this internal function MUST rethrow UnhandledAsyncExceptionError
 */
@Deprecated(message = "deprecated for caller note: callers of this internal function MUST rethrow UnhandledAsyncExceptionError")
internal fun startSafeCoroutine(block: suspend() -> Unit) {
    val wrappedBlock : suspend() -> Unit = {
        try {
            block()
        }
        catch (exception: Throwable) {
            exitProcess(UnhandledAsyncExceptionError(exception))
        }
    }
    wrappedBlock.startCoroutine(Continuation(EmptyCoroutineContext){
        // should not throw, as it crashes the process before it gets that far.
        it.getOrThrow()
    })
}

fun <S: AsyncAffinity, T> S.async(block: suspend S.() -> T): Promise<T> {
    return Promise {
        await()
        block()
    }
}
