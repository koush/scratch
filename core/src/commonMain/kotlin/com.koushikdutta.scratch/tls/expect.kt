package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.IOException
import com.koushikdutta.scratch.buffers.AllocationTracker
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers

class SSLEngineResult constructor(val status: SSLEngineStatus, val handshakeStatus: SSLEngineHandshakeStatus)

enum class SSLEngineStatus {
    BUFFER_UNDERFLOW,
    OK,
    CLOSED,
}

enum class SSLEngineHandshakeStatus {
    FINISHED,
    NEED_TASK,
    NEED_UNWRAP,
    NEED_WRAP,
}

expect class SSLContext {
    fun createSSLEngine(): SSLEngine
    fun createSSLEngine(host: String?, port: Int): SSLEngine
}
expect abstract class SSLEngine {
    abstract fun getUseClientMode(): Boolean
    abstract fun setUseClientMode(value: Boolean)
}
expect fun SSLEngine.runHandshakeTask()
expect fun SSLEngine.checkHandshakeStatus(): SSLEngineHandshakeStatus
expect fun SSLEngine.unwrap(src: ByteBufferList, dst: WritableBuffers, tracker: AllocationTracker = AllocationTracker()): SSLEngineResult
expect fun SSLEngine.wrap(src: ByteBufferList, dst: WritableBuffers, tracker: AllocationTracker = AllocationTracker()): SSLEngineResult
expect fun SSLEngine.setNegotiatedProtocols(vararg protocols: String)
expect fun SSLEngine.getNegotiatedProtocol(): String?

expect open class SSLException(message: String): IOException
expect class SSLHandshakeException : SSLException

expect interface RSAPrivateKey
expect abstract class X509Certificate

expect fun createSelfSignedCertificate(subjectName: String): Pair<RSAPrivateKey, X509Certificate>

expect fun SSLContext.init(pk: RSAPrivateKey, certificate: X509Certificate): SSLContext
expect fun SSLContext.init(certificate: X509Certificate): SSLContext
expect fun createTLSContext(): SSLContext
expect fun createALPNTLSContext(): SSLContext
expect fun getDefaultSSLContext(): SSLContext
expect fun getDefaultALPNSSLContext(): SSLContext
