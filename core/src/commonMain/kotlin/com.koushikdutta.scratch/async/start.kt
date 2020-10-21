package com.koushikdutta.scratch.async

import com.koushikdutta.scratch.AsyncAffinity
import com.koushikdutta.scratch.exitProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class UnhandledAsyncExceptionError(throwable: Throwable): Error(throwable)

/**
 * callers of this internal function MUST rethrow UnhandledAsyncExceptionError
 */
@Deprecated(message = "must not throw.")
internal fun startSafeCoroutine(block: suspend() -> Unit) {
    val originalStack = Exception()
    val wrappedBlock : suspend() -> Unit = {
        try {
            block()
        }
        catch (exception: Throwable) {
            exitProcess(UnhandledAsyncExceptionError(exception), originalStack)
        }
    }
    wrappedBlock.startCoroutine(Continuation(EmptyCoroutineContext){
        // should not throw, as it crashes the process before it gets that far.
        it.getOrThrow()
    })
}

fun <S: AsyncAffinity, T> S.async(block: suspend S.() -> T) = GlobalScope.async(Dispatchers.Unconfined) {
    await()
    block()
}

fun <S: AsyncAffinity, T> S.launch(block: suspend S.() -> T) = GlobalScope.launch(Dispatchers.Unconfined) {
    await()
    block()
}
