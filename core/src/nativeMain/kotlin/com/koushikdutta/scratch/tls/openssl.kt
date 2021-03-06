package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.IOException
import com.koushikdutta.scratch.buffers.*
import com.koushikdutta.scratch.crypto.*
import kotlinx.cinterop.*

actual object DefaultHostnameVerifier : HostnameVerifier {
    override fun verify(engine: SSLEngine): Boolean {
        return true
    }
}
actual fun SSLEngine.runHandshakeTask() {
    // noop
}
actual fun SSLEngine.checkHandshakeStatus(): SSLEngineHandshakeStatus {
    return if (finishedHandshake)
        SSLEngineHandshakeStatus.FINISHED
    else
        SSLEngineHandshakeStatus.NEED_TASK
}
actual open class SSLException actual constructor(message: String) : IOException(message)
actual open class SSLHandshakeException(message: String) : SSLException(message)

actual fun createTLSContext(): SSLContext {
    return SSLContext()
}

actual fun createALPNTLSContext(): SSLContext {
    return SSLContext()
}

private val defaultContext = SSLContext()
actual fun getDefaultSSLContext(): SSLContext {
    return defaultContext
}

actual fun getDefaultALPNSSLContext(): SSLContext {
    return defaultContext
}

fun verifyCallback(preverifyOk: Int, x509Store: CPointer<X509_STORE_CTX>?): Int {
    val err = X509_STORE_CTX_get_error(x509Store);
    val str = X509_verify_cert_error_string(err.toLong())?.toKString()
    val ssl = X509_STORE_CTX_get_ex_data(x509Store, SSL_get_ex_data_X509_STORE_CTX_idx())?.reinterpret<SSL>()
    val ref = SSL_get_ex_data(ssl, SSLContext.engineIndex)
    val engine = ref?.asStableRef<SSLEngineImpl>()?.get()
    engine?.handshakeError = str
    return preverifyOk
}
val verifyCallbackPtr = staticCFunction(::verifyCallback)

fun alpnCallback(ssl: CPointer<SSL>?, out: CPointer<CPointerVar<UByteVar>>?, outLen: CPointer<UByteVar>?, inBuf: CPointer<UByteVar>?, inBufLen: UInt, arg: COpaquePointer?): Int {
    val sslData = SSL_get_ex_data(ssl, SSLContext.engineIndex) ?: return SSL_TLSEXT_ERR_NOACK

    val engine = sslData.asStableRef<SSLEngineImpl>().get()
    if (engine.alpnprotos.isEmpty())
        return SSL_TLSEXT_ERR_NOACK

    val alpnBytes = inBuf!!.reinterpret<ByteVar>().readBytes(inBufLen.toInt())
    val buf = alpnBytes.createByteBufferList()
    try {
        while (!buf.isEmpty) {
            val protoLen = buf.readByte().toInt()
            val proto = buf.readUtf8String(protoLen)

            if (proto in engine.alpnprotos) {
                // found a matching protocol, set teh outBuf to a pointer within inBuf
                val protocolOffset = inBufLen.toInt() - buf.remaining() - protoLen
                out!![0] = inBuf.plus(protocolOffset)
                outLen!![0] = protoLen.toUByte()
                return SSL_TLSEXT_ERR_OK
            }
        }
    }
    catch (exception: Exception) {
        return SSL_TLSEXT_ERR_NOACK
    }

    return SSL_TLSEXT_ERR_NOACK
}
val alpnCallbackPtr = staticCFunction(::alpnCallback)

actual class SSLContext(val ctx: CPointer<SSL_CTX> = SSL_CTX_new(SSLv23_method!!.invoke())!!) {
    companion object {
        // seem to need to reference this class to trigger the static constructor. wierd.
        fun doInit() {
        }

        internal val engineIndex: Int
        init {
            OPENSSL_init_ssl(0.toULong(), null)
            ERR_load_X509_strings()
            ERR_load_X509V3_strings()
            ERR_load_RSA_strings()
            ERR_load_SSL_strings()
            ERR_load_BIO_strings()
            ERR_load_CRYPTO_strings()
            engineIndex = CRYPTO_get_ex_new_index(CRYPTO_EX_INDEX_SSL, 0, null, null, null, null)
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
        return SSLEngineImpl(ctx, SSL_new(ctx)!!)
    }

    actual fun createSSLEngine(host: String?, port: Int): SSLEngine {
        // this enables openssl automatic hostname verificate (including subject alternate name)
        // should we use this? use okhostnameverifier instead?
        val engine = createSSLEngine()

        SSL_set_hostflags(engine.engine, X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS)
        SSL_set1_host(engine.engine, host!!)
        SSL_set_verify(engine.engine, SSL_VERIFY_PEER, verifyCallbackPtr);

        return engine
    }
}

enum class SSLStatus {
    SSL_ERROR_NONE, SSL_ERROR_WANT_READ, SSL_ERROR_WANT_WRITE
}

private fun SSLStatus.convertToHandshakeStatus(): SSLEngineHandshakeStatus {
    return when (this) {
        SSLStatus.SSL_ERROR_NONE -> SSLEngineHandshakeStatus.NEED_TASK
        SSLStatus.SSL_ERROR_WANT_READ -> SSLEngineHandshakeStatus.NEED_WRAP
        SSLStatus.SSL_ERROR_WANT_WRITE -> SSLEngineHandshakeStatus.NEED_UNWRAP
    }
}

private fun SSLStatus.convertToEngineStatus(): SSLEngineStatus {
    return when (this) {
        SSLStatus.SSL_ERROR_NONE -> SSLEngineStatus.OK
        SSLStatus.SSL_ERROR_WANT_READ -> SSLEngineStatus.BUFFER_UNDERFLOW
        SSLStatus.SSL_ERROR_WANT_WRITE -> SSLEngineStatus.OK
    }
}

actual abstract class SSLEngine(internal val ctx: CPointer<SSL_CTX>, internal val engine: CPointer<SSL>) {
    val rbio = BIO_new(BIO_s_mem())
    val wbio = BIO_new(BIO_s_mem())

    init {
        SSL_set_bio(engine, rbio, wbio);
        // todo: Dispose?
        SSL_set_ex_data(engine, SSLContext.engineIndex, StableRef.create(this).asCPointer())
    }

    protected var clientMode: Boolean? = null
    internal fun validate() {
        if (clientMode == null)
            throw SSLException("client/server mode not specified")
    }


    abstract var finishedHandshake: Boolean
        protected set
    actual abstract fun getUseClientMode(): Boolean
    actual abstract fun setUseClientMode(value: Boolean)

    private var hasInitialized = false
    internal fun ensureInitialized() {
        if (hasInitialized)
            return
        hasInitialized = true
        if (clientMode == null)
            throw SSLException("client/server mode not specified")

        if (clientMode!!)
            SSL_set_connect_state(engine)
        else
            SSL_set_accept_state(engine)

        setupAlpn()
    }

    internal fun encodeAlpn(): ByteArray {
        // must encode the protos for wire format
        val buf = ByteBufferList()
        for (proto in alpnprotos) {
            buf.put(proto.length.toByte())
            buf.putUtf8String(proto)
        }
        return buf.readBytes();
    }

    private fun setupAlpn() {
        if (alpnprotos.isEmpty())
            return
        if (clientMode!!) {
            val encoded = encodeAlpn()
            encoded.usePinned {
                SSL_set_alpn_protos(engine, it.addressOf(0).reinterpret(), encoded.size.toUInt())
            }
            return
        }

        // openssl sets the alpn callback at the context level, which is odd, the callback
        // at least returns the engine.
        // no need to pass an arg, ssl ex data already has the info we need.
        SSL_CTX_set_alpn_select_cb(ctx, alpnCallbackPtr, null)
    }

    val alpnprotos = mutableListOf<String>()

    internal fun getErrorString(n: ULong): String {
        memScoped {
            return ERR_error_string(n, null)!!.toKString()
        }
    }

    var handshakeError: String? = null
    internal fun getStatusOrThrow(n: Int, stage: String): SSLStatus {
        return when (val err = SSL_get_error(engine, n)) {
            SSL_ERROR_NONE -> SSLStatus.SSL_ERROR_NONE
            SSL_ERROR_WANT_WRITE -> SSLStatus.SSL_ERROR_WANT_WRITE
            SSL_ERROR_WANT_READ -> SSLStatus.SSL_ERROR_WANT_READ
            else -> memScoped {
                if (handshakeError != null)
                    throw SSLHandshakeException(handshakeError!!)
                throw SSLException("error: $err ${getErrorString(err.toULong())}")
            }
        }
    }

    internal fun doSSLHandshake(): SSLStatus {
        // try to finish the handshake
        try {
            val handshakeResult = getStatusOrThrow(SSL_do_handshake(engine), "handshake")
            // println("$useClientMode shake $handshakeResult")
            finishedHandshake = SSL_is_init_finished(engine) != 0 && SSL_in_init(engine) == 0
            return handshakeResult
        } catch (exception: SSLException) {
            throw SSLHandshakeException(exception.message!!)
        }
    }

    internal fun readSSL(dst: WritableBuffers, tracker: AllocationTracker): Int {
        tracker.minAlloc = 65536
        tracker.finishTracking()

        var ret = 0
        while (true) {
            val n = dst.putAllocatedBuffer(tracker.requestNextAllocation()) {
                IO_op(::SSL_read, engine, it)
            }
            if (n == 0)
                return ret
            else if (n < 0)
                return n
                tracker.trackDataUsed(n)
            ret += n
        }
    }

    internal fun writeSSL(src: ByteBuffer): Int {
        return IO_op(::SSL_write, engine, src)
    }

    internal fun writeBio(src: ByteBuffer): Int {
        return IO_op(::BIO_write, rbio, src)
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

        internal fun readBio(bio: CValuesRef<BIO>?, dst: WritableBuffers, tracker: AllocationTracker = AllocationTracker()): Int {
            var ret = 0
            while (true) {
                val n = dst.putAllocatedBuffer(tracker.requestNextAllocation()) {
                    IO_op(::BIO_read, bio, it)
                }
                if (n <= 0) {
                    if (BIO_test_flags(bio, BIO_FLAGS_SHOULD_RETRY) == 0)
                        throw SSLException("wrap failed, !BIO_FLAGS_SHOULD_RETRY")
                    return ret
                }
                tracker.trackDataUsed(n)
                ret += n
            }
        }
    }
}

actual fun SSLEngine.setApplicationProtocols(vararg protocols: String) {
    alpnprotos.clear()
    alpnprotos.addAll(protocols)
}

actual fun SSLEngine.getApplicationProtocol(): String? {
    val encoded = memScoped {
        val encoded = alloc<CPointerVar<UByteVar>>()
        val encodedLen = alloc<UIntVar>()
        SSL_get0_alpn_selected(engine, encoded.ptr, encodedLen.ptr)
        if (encodedLen.value.toInt() == 0)
            return null
        encoded.value!!.readBytes(encodedLen.value.toInt())
    }

    return encoded.decodeToString()
}

class SSLEngineImpl(ctx: CPointer<SSL_CTX>, engine: CPointer<SSL>) : SSLEngine(ctx, engine) {

    override fun getUseClientMode(): Boolean {
        return clientMode!!
    }

    override fun setUseClientMode(value: Boolean) {
        clientMode = value
    }

    override var finishedHandshake = false
}

actual fun SSLEngine.unwrap(src: ByteBufferList, dst: WritableBuffers, tracker: AllocationTracker): SSLEngineResult {
    tracker.minAlloc = 65536
    ensureInitialized()
    // keep calling SSL_read to decrypt data until there's nothing left
    while (true) {
        var bytesConsumed = 0
        while (src.hasRemaining()) {
            val buffer = src.readFirst()
            val consumed = if (buffer.hasRemaining()) writeBio(buffer) else 0
            buffer.position(buffer.position() + consumed)
            src.addFirst(buffer)
            if (consumed == 0)
                break
            bytesConsumed += consumed
        }

        // check if handshaking
        if (bytesConsumed == 0 && !finishedHandshake) {
            // try to finish the handshake
            val handshakeResult = doSSLHandshake()
            if (handshakeResult == SSLStatus.SSL_ERROR_WANT_READ)
                return SSLEngineResult(SSLEngineStatus.BUFFER_UNDERFLOW, SSLEngineHandshakeStatus.NEED_TASK)
            if (handshakeResult == SSLStatus.SSL_ERROR_NONE)
                return SSLEngineResult(SSLEngineStatus.OK, handshakeResult.convertToHandshakeStatus())
        }

        val bytesProduced = readSSL(dst, tracker)

        if (!finishedHandshake)
            return SSLEngineResult(SSLEngineStatus.OK, SSLEngineHandshakeStatus.NEED_WRAP)

        if (bytesConsumed == 0 && bytesProduced == 0)
            return SSLEngineResult(SSLEngineStatus.OK, if (finishedHandshake) SSLEngineHandshakeStatus.FINISHED else SSLEngineHandshakeStatus.NEED_TASK)

        val status = getStatusOrThrow(bytesProduced, "unwrap")
        if (bytesProduced <= 0)
            return SSLEngineResult(status.convertToEngineStatus(), if (finishedHandshake) SSLEngineHandshakeStatus.FINISHED else SSLEngineHandshakeStatus.NEED_TASK)
    }
}

actual fun SSLEngine.wrap(src: ByteBufferList, dst: WritableBuffers, tracker: AllocationTracker): SSLEngineResult {
    tracker.finishTracking()

    ensureInitialized()
    while (true) {
        var bytesConsumed = 0
        while (src.hasRemaining()) {
            val buffer = src.readFirst()
            val consumed = if (buffer.hasRemaining()) writeSSL(buffer) else 0
            buffer.position(buffer.position() + consumed)
            src.addFirst(buffer)
            if (consumed == 0)
                break
            bytesConsumed += consumed
        }

        // check if handshaking
        if (bytesConsumed == 0 && !finishedHandshake) {
            // try to finish the handshake
            val handshakeResult = doSSLHandshake()
            if (handshakeResult != SSLStatus.SSL_ERROR_WANT_READ)
                return SSLEngineResult(SSLEngineStatus.OK, handshakeResult.convertToHandshakeStatus())
        }

        val bytesProduced = SSLEngine.readBio(wbio, dst, tracker)

        if (!finishedHandshake)
            return SSLEngineResult(SSLEngineStatus.OK, SSLEngineHandshakeStatus.NEED_UNWRAP)

        if (bytesConsumed == 0 && bytesProduced == 0)
            return SSLEngineResult(SSLEngineStatus.OK, if (finishedHandshake) SSLEngineHandshakeStatus.FINISHED else SSLEngineHandshakeStatus.NEED_TASK)

        // keep going until nothing is produced or consumed
        // if (bytesProduced <= 0 && bytesConsumed <= 0)
        //     return SSLEngineResult(getStatusOrThrow(bytesConsumed, "wrap").convertToEngineStatus(), if (finishedHandshake) SSLEngineHandshakeStatus.FINISHED else SSLEngineHandshakeStatus.NEED_TASK)
    }
}

