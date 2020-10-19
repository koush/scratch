package com.koushikdutta.scratch

import kotlinx.coroutines.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.jvm.JvmStatic

interface Result<T> {
    fun getOrThrow(): T
    fun getThrowable(): Throwable?
    fun resume(resume: Continuation<T>)
    val isSuccess: Boolean
    val isFailure: Boolean
    val isCancelled: Boolean

    companion object {
        @JvmStatic
        fun <T> failure(throwable: Throwable): Result<T> {
            return object : Result<T> {
                override val isCancelled = throwable is CancellationException
                override val isSuccess = false
                override val isFailure = true
                override fun getOrThrow(): T {
                    throw throwable
                }
                override fun getThrowable(): Throwable? {
                    return throwable
                }
                override fun resume(resume: Continuation<T>) {
                    resume.resumeWithException(throwable)
                }
            }
        }

        @JvmStatic
        fun <T> success(value: T): Result<T> {
            return object : Result<T> {
                override val isCancelled = false
                override val isSuccess = true
                override val isFailure = false
                override fun getOrThrow(): T {
                    return value
                }

                override fun getThrowable(): Throwable? {
                    return null
                }
                override fun resume(resume: Continuation<T>) {
                    resume.resume(value)
                }
            }
        }
    }
}
