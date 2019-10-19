package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.AsyncWrappingSocket
import com.koushikdutta.scratch.IOException


expect class SSLContext {
    fun createSSLEngine(): SSLEngine
    fun createSSLEngine(host: String?, port: Int): SSLEngine
}
expect abstract class SSLEngine {
    abstract fun getUseClientMode(): Boolean
    abstract fun setUseClientMode(value: Boolean)
}
expect open class SSLException: IOException
expect class SSLHandshakeException : SSLException

expect interface RSAPrivateKey
expect abstract class X509Certificate

expect fun createSelfSignedCertificate(subjectName: String): Pair<RSAPrivateKey, X509Certificate>

expect fun SSLContext.init(pk: RSAPrivateKey, certificate: X509Certificate): SSLContext
expect fun SSLContext.init(certificate: X509Certificate): SSLContext
expect fun createTLSContext(): SSLContext
expect fun getDefaultSSLContext(): SSLContext

expect class AsyncTlsSocket(socket: AsyncSocket, engine: SSLEngine, options: AsyncTlsOptions?) : AsyncWrappingSocket {
    internal suspend fun awaitHandshake()
}
