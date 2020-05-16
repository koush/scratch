package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.http.client.AsyncHttpClientSession

class DefaultsMiddleware : AsyncHttpClientMiddleware() {
    private fun addHeaderIfNotExists(session: AsyncHttpClientSession, name: String, value: String) {
        if (!session.request.headers.contains(name))
            session.request.headers.add(name, value)
    }

    private fun addDefaultHeaders(session: AsyncHttpClientSession) {
        val host = session.request.uri.host
        if (host != null)
            addHeaderIfNotExists(session, "Host", host)
        addHeaderIfNotExists(session, "User-Agent", "scratch/1.0")
    }

    override suspend fun prepare(session: AsyncHttpClientSession) {
        addDefaultHeaders(session)
    }
}