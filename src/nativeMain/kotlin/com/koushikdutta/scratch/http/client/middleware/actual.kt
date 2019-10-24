package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.http.client.AsyncHttpClient

actual fun AsyncHttpClient.addPlatformMiddleware(client: AsyncHttpClient) {
    OpenSSLMiddleware(client.eventLoop).install(client)
}