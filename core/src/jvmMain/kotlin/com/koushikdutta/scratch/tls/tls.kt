package com.koushikdutta.scratch.tls

import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.SecureRandom
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory

actual typealias SSLEngine = javax.net.ssl.SSLEngine
actual typealias SSLContext = javax.net.ssl.SSLContext
actual typealias SSLException = javax.net.ssl.SSLException
actual typealias SSLHandshakeException = SSLHandshakeException

actual fun createTLSContext(): SSLContext {
    return SSLContext.getInstance("TLS")
}

actual fun createALPNTLSContext(): SSLContext {
    if (!ConscryptHelper.hasConscrypt)
        return createTLSContext()
    return createConscryptContext()!!
}

actual fun getDefaultSSLContext(): SSLContext {
    return SSLContext.getDefault()
}

actual fun getDefaultALPNSSLContext(): SSLContext {
    return ConscryptHelper.alpnDefaultContext ?: getDefaultSSLContext()
}

private fun createConscryptContext(): SSLContext? {
    try {
        Conscrypt.checkAvailability()
        return SSLContext.getInstance("TLS", Conscrypt.newProvider())
    }
    catch (throwable: Throwable) {
        return null
    }
}

private fun createAndInitConscryptContext(): SSLContext? {
    try {
        Conscrypt.checkAvailability()
        val provider = Conscrypt.newProvider()
        val context = SSLContext.getInstance("TLS", provider)
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).provider)
        tmf.init(null as KeyStore?)
        context.init(null, tmf.trustManagers, SecureRandom())
        return context
    } catch (throwable: Throwable) {
        return null
    }
}

private class ConscryptHelper {
    companion object {
        val alpnDefaultContext = createAndInitConscryptContext()
        val hasConscrypt = alpnDefaultContext != null
    }
}

actual fun SSLEngine.setApplicationProtocols(vararg protocols: String) {
    if (!ConscryptHelper.hasConscrypt)
        return

    // no need to throw here, because the other end may not support negotiation anyways.
    if (!Conscrypt.isConscrypt(this))
        return

    Conscrypt.setApplicationProtocols(this, protocols)
}

actual fun SSLEngine.getApplicationProtocol(): String? {
    if (!ConscryptHelper.hasConscrypt)
        return null

    if (!Conscrypt.isConscrypt(this))
        return null

    return Conscrypt.getApplicationProtocol(this)
}