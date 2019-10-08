package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.http.client.AsyncHttpClientSession


open class AsyncHttpClientMiddleware {
    open suspend fun prepare(session: AsyncHttpClientSession) {
    }
    open suspend fun connectSocket(session: AsyncHttpClientSession): Boolean {
        return false
    }
    open suspend fun exchangeMessages(session: AsyncHttpClientSession): Boolean {
        return false
    }
    open suspend fun onResponseStarted(session: AsyncHttpClientSession) {
    }
    open suspend fun onResponseComplete(session: AsyncHttpClientSession) {
    }
    open suspend fun onBodyReady(session: AsyncHttpClientSession) {
    }
}
