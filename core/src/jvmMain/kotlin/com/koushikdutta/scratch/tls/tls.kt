package com.koushikdutta.scratch.tls

import javax.net.ssl.SSLHandshakeException

actual typealias SSLEngine = javax.net.ssl.SSLEngine
actual typealias SSLContext = javax.net.ssl.SSLContext
actual typealias SSLException = javax.net.ssl.SSLException
actual typealias SSLHandshakeException = SSLHandshakeException

actual fun createTLSContext(): SSLContext {
    return SSLContext.getInstance("TLS")
}

actual fun getDefaultSSLContext(): SSLContext {
    return javax.net.ssl.SSLContext.getDefault()
}

