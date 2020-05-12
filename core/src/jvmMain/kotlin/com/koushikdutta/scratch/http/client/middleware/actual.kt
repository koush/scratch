package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.http.client.AsyncHttpClient

actual fun AsyncHttpClient.addPlatformMiddleware(client: AsyncHttpClient) {
    try {
        client.middlewares.add(ConscryptMiddleware(client.eventLoop))
//        client.middlewares.add(0, CacheMiddleware(client.eventLoop))
    }
    catch (throwable: Throwable) {
        System.err.println("failed to load conscrypt")
    }
}