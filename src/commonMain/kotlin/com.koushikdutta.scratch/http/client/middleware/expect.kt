package com.koushikdutta.scratch.http.client.middleware

import com.koushikdutta.scratch.http.client.AsyncHttpClient

expect fun AsyncHttpClient.addPlatformMiddleware(client: AsyncHttpClient)