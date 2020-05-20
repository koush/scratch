package com.koushikdutta.scratch

import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface Result<T> {
    fun getOrThrow(): T
    fun resume(resume: Continuation<T>)
    val isSuccess: Boolean
    val isFailure: Boolean

    companion object {
        fun <T> failure(throwable: Throwable): Result<T> {
            return object : Result<T> {
                override val isSuccess = false
                override val isFailure = true
                override fun getOrThrow(): T {
                    throw throwable
                }
                override fun resume(resume: Continuation<T>) {
                    resume.resumeWithException(throwable)
                }
            }
        }

        fun <T> success(value: T): Result<T> {
            return object : Result<T> {
                override val isSuccess = true
                override val isFailure = false
                override fun getOrThrow(): T {
                    return value
                }
                override fun resume(resume: Continuation<T>) {
                    resume.resume(value)
                }
            }
        }
    }
}
