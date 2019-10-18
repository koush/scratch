package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.IOException
import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.buffers.allocateByteBuffer
import com.koushikdutta.scratch.crypto.*
import kotlinx.cinterop.*


actual interface SSLSession


actual abstract class SSLEngine(val engine: CPointer<SSL>) {
    abstract  fun unwrap(src: ByteBuffer, dst: WritableBuffers): SSLStatus
    abstract fun wrap(src: ByteBuffer, dst: WritableBuffers): SSLStatus
    abstract val finishedHandshake: Boolean
    internal var clientMode: Boolean? = null
    internal fun validate() {
        if (clientMode == null)
            throw SSLException("client/server mode not specified")
    }
}
actual var SSLEngine.useClientMode: Boolean
    get() {
        return clientMode!!
    }
    set(value) {
        if (clientMode != null)
            throw SSLException("client/server mode already set to $clientMode")
        clientMode = value
        if (value)
            SSL_set_connect_state(engine)
        else
            SSL_set_accept_state(engine)
    }

actual open class SSLException(message: String) : IOException(message)
actual open class SSLHandshakeException(message: String) : SSLException(message)

actual fun createTLSContext(): SSLContext {
    return SSLContext()
}

private val defaultContext = SSLContext()
actual fun getDefaultSSLContext(): SSLContext {
    return defaultContext
}

actual class SSLContext(val ctx: CPointer<SSL_CTX> = SSL_CTX_new(SSLv23_method!!.invoke())!!) {
    companion object {
        // seem to need to reference this class to trigger the static constructor. wierd.
        fun doInit() {
        }
        init {
            OPENSSL_init_ssl(0, null)
            ERR_load_SSL_strings()
            ERR_load_BIO_strings()
            ERR_load_CRYPTO_strings()
        }

        private val default = SSLContext(SSL_CTX_new(SSLv23_method!!.invoke())!!)
        fun getDefault(): SSLContext {
            return default
        }
    }

    init {
        doInit()
    }

    actual fun createSSLEngine(): SSLEngine {
        return SSLEngineImpl(SSL_new(ctx)!!)
    }

    actual fun createSSLEngine(host: String?, port: Int): SSLEngine {
        // this enables openssl automatic hostname verificate (including subject alternate name)
        // should we use this? use okhostnameverifier instead?
        val engine = createSSLEngine()

        SSL_set_hostflags(engine.engine, X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS)
        var ret = SSL_set1_host(engine.engine, host!!)
        println(ret)
        SSL_set_verify(engine.engine, SSL_VERIFY_PEER, null);




        // val param = SSL_get0_param(engine.engine)

        // X509_VERIFY_PARAM_set_hostflags(param, X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS)
        // X509_VERIFY_PARAM_set1_host(param, host, host!!.length.toULong())
        // SSL_set_verify(engine.engine, SSL_VERIFY_PEER, null)

        return engine
    }
}

enum class SSLStatus {
    SSL_ERROR_NONE, SSL_ERROR_WANT_READ, SSL_ERROR_WANT_WRITE
}

class SSLEngineImpl(engine: CPointer<SSL>): SSLEngine(engine) {
    val rbio = BIO_new(BIO_s_mem())
    val wbio = BIO_new(BIO_s_mem())

    init {
        SSL_set_bio(engine, rbio, wbio);
    }

    private fun getStatusOrThrow(n: Int, stage: String): SSLStatus {
        return when (val err = SSL_get_error(engine, n)) {
            SSL_ERROR_NONE -> SSLStatus.SSL_ERROR_NONE
            SSL_ERROR_WANT_WRITE -> SSLStatus.SSL_ERROR_WANT_WRITE
            SSL_ERROR_WANT_READ -> SSLStatus.SSL_ERROR_WANT_READ
            else -> throw SSLException("error: $err")
        }
    }

    private fun doSSLHandshake(): SSLStatus
    {
        // try to finish the handshake
        try {
            val handshakeResult = getStatusOrThrow(SSL_do_handshake(engine), "handshake")
            finishedHandshake = SSL_is_init_finished(engine) != 0
            return handshakeResult
        }
        catch (exception: SSLException) {
            throw SSLHandshakeException(exception.message!!)
        }
    }

    override var finishedHandshake = false
    override fun unwrap(src: ByteBuffer, dst: WritableBuffers): SSLStatus {
        // keep calling SSL_read to decrypt data until there's nothing left
        while (true) {
            val bytesConsumed = if (src.hasRemaining()) writeBio(src) else 0

            // check if handshaking
            if (SSL_is_init_finished(engine) == 0) {
                // try to finish the handshake
                val handshakeResult = doSSLHandshake()
                if (handshakeResult != SSLStatus.SSL_ERROR_WANT_WRITE)
                    return handshakeResult
            }

            val bytesProduced = readSSL(dst)

            if (bytesConsumed == 0 && bytesProduced == 0)
                return SSLStatus.SSL_ERROR_NONE

            val status = getStatusOrThrow(bytesProduced, "unwrap")
            if (bytesProduced <= 0)
                return status
        }
    }

    override fun wrap(src: ByteBuffer, dst: WritableBuffers): SSLStatus {
        while (true) {
            val bytesConsumed = if (src.hasRemaining()) writeSSL(src) else 0

            // check if handshaking
            if (SSL_is_init_finished(engine) == 0) {
                // try to finish the handshake
                val handshakeResult = doSSLHandshake()
                if (handshakeResult != SSLStatus.SSL_ERROR_WANT_READ)
                    return handshakeResult
            }

            val bytesProduced = readBio(dst)

            if (bytesConsumed == 0 && bytesProduced == 0)
                return SSLStatus.SSL_ERROR_NONE

            // keep going until nothing is produced or consumed
            if (bytesProduced <= 0 && bytesConsumed <= 0)
                return getStatusOrThrow(bytesConsumed, "wrap")
        }
    }

    private fun readSSL(dst: WritableBuffers): Int {
        var ret = 0
        while (true) {
            val n = READ_op(::SSL_read, engine, dst)
            if (n == 0)
                return ret
            else if (n < 0)
                return n
            ret += n
        }
    }

    private fun writeSSL(src: ByteBuffer): Int {
        return IO_op(::SSL_write, engine, src)
    }

    private fun writeBio(src: ByteBuffer): Int {
        return IO_op(::BIO_write, rbio, src)
    }

    private fun readBio(dst: WritableBuffers): Int {
        return readBio(wbio, dst)
    }

    companion object {
        private fun <T> IO_op(op: (T?, CValuesRef<*>, dlen: Int) -> Int, io: T?, src: ByteBuffer): Int {
            src.array().usePinned { pinnedSrc ->
                val n = op(io, pinnedSrc.addressOf(src.position()), src.remaining())
                if (n > 0)
                    src.position(src.position() + n)
                return n
            }
        }
        private fun <T> READ_op(op: (T?, CValuesRef<*>, dlen: Int) -> Int, io: T?, dst: WritableBuffers): Int {
            val buf = allocateByteBuffer(8192)
            val n = IO_op(op, io, buf)
            buf.flip()
            dst.add(buf)
            return n
        }

        internal fun readBio(bio: CValuesRef<BIO>?, dst: WritableBuffers): Int {
            var ret = 0
            while (true) {
                val n = READ_op(::BIO_read, bio, dst)
                if (n <= 0) {
                    if (BIO_test_flags(bio, BIO_FLAGS_SHOULD_RETRY) == 0)
                        throw SSLException("wrap failed, !BIO_FLAGS_SHOULD_RETRY")
                    return ret
                }
                ret += n
            }
        }
    }
}
