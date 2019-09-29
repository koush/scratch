package com.koushikdutta.scratch

import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.*

interface AsyncTlsTrustFailureCallback {
    fun handleOrRethrow(throwable: Throwable)
}

class AsyncTlsOptions(internal val trustManagers: Array<TrustManager>? = null, internal val hostnameVerifier: HostnameVerifier? = null, internal val trustFailureCallback: AsyncTlsTrustFailureCallback?)

class AsyncTlsSocket(private val socket: AsyncSocket, private val host: String?, private val engine: SSLEngine, private val options: AsyncTlsOptions?) : AsyncSocket {
    var finishedHandshake = false
    val decryptedPipe = object : NonBlockingWritePipe() {
        override fun writable() {
            readYielder.resume()
        }
    }
    val encryptedPipe = object : NonBlockingWritePipe() {
        override fun writable() {
            writeYielder.resume()
        }
    }
    val readYielder = Cooperator()
    init {
        async {
            (encryptedPipe::read as AsyncRead).copy(socket::write)
        }
        async {
            val buffers = ByteBufferList()
            while (socket.read(buffers)) {
                if (!unwrap(buffers))
                    readYielder.yield()
            }
            if (!unwrap(buffers))
                readYielder.yield()
        }
    }

    fun calculateAlloc(remaining: Int): Int {
        // alloc 50% more than we need for writing
        var alloc = remaining * 3 / 2
        if (alloc == 0)
            alloc = 8192
        return alloc
    }

    var peerCertificates: Array<X509Certificate>? = null
        private set

    fun handleHandshakeStatus(status: SSLEngineResult.HandshakeStatus) {
        if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            val task = engine.delegatedTask
            task.run()
        }

        if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
            wrap(ByteBufferList())
        }

        if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
            unwrap(ByteBufferList())
        }

        if (!finishedHandshake && (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING || engine.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED)) {
            if (engine.useClientMode) {
                var trustManagers: Array<TrustManager>? = this.options?.trustManagers
                if (trustManagers == null) {
                    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    tmf.init(null as KeyStore?)
                    trustManagers = tmf.trustManagers
                }
                var trusted = false
                var peerUnverifiedCause: Exception? = null
                for (tm in trustManagers!!) {
                    try {
                        val xtm = tm as X509TrustManager
                        peerCertificates = engine.session.peerCertificates as Array<X509Certificate>
                        xtm.checkServerTrusted(peerCertificates, "DHE_DSS")
                        if (engine.peerHost != null) {
                            val verifier: HostnameVerifier = options?.hostnameVerifier
                                    ?: OkHostnameVerifier
                            if (!verifier.verify(host, engine.session)) {
                                throw SSLException("hostname verification failed for <$host>")
                            }
                        }
                        trusted = true
                        break
                    } catch (ex: GeneralSecurityException) {
                        peerUnverifiedCause = ex
                    } catch (ex: SSLException) {
                        peerUnverifiedCause = ex
                    }
                }

                finishedHandshake = true
                if (!trusted) {
                    if (options?.trustFailureCallback == null)
                        throw peerUnverifiedCause!!
                    else
                        options.trustFailureCallback.handleOrRethrow(peerUnverifiedCause!!)
                }
            }
            else {
                finishedHandshake = true
            }

            // upon handshake completion, trigger a wrap/unwrap so that all pending input/output
            // gets flushed
            unwrap(ByteBufferList())
            wrap(ByteBufferList())
        }
    }


    val wrapBuffer = ByteBufferList()
    val writeBuffer = ByteBufferList()
    fun wrap(buffer: ReadableBuffers): Boolean {
        var writable: Boolean = true
        buffer.get(wrapBuffer)

        var alloced = calculateAlloc(wrapBuffer.remaining())
        do {
            // if the handshake is finished, don't attempt to wrap 0 bytes of data.
            // this seems to terminate the ssl engine.
            if (finishedHandshake && wrapBuffer.isEmpty)
                break

            // SSLEngine bytesProduced/bytesConsumed is unreliable, it doesn't really
            // take into account that wrap/unwrap in the context of a handshake may
            // "produce" bytes that are only used for the handshake, and not actual application
            // data.
            val available = wrapBuffer.remaining()

            val encrypted = ByteBufferList.obtain(alloced)
            alloced = encrypted.remaining()
            val unencrypted = wrapBuffer.all
            val result = engine.wrap(unencrypted, encrypted)
            encrypted.flip()
            var bytesProduced = encrypted.remaining()
            // add unused unencrypted data back to the wrap buffer
            wrapBuffer.addAll(*unencrypted)
            val bytesConsumed = available - wrapBuffer.remaining()
            // queue up the encrypted data for write
            writeBuffer.add(encrypted)
            writable = encryptedPipe.write(writeBuffer)

            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                // allow the loop to continue
                bytesProduced = -1
                alloced *= 2
                continue
            }
            else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                // this should never happen, as it is not possible to underflow
                // with application data
                break
            }

            // this may result in a recursive call
            handleHandshakeStatus(result.handshakeStatus)
        } while (bytesConsumed != 0 || bytesProduced != 0)

        return writable
    }

    var eos = false
    val unwrapBuffer = ByteBufferList()
    val readBuffer = ByteBufferList()
    val allocator = Allocator().setMinAlloc(8192)
    fun unwrap(buffer: ReadableBuffers): Boolean {
        var writable: Boolean

        buffer.get(unwrapBuffer)
        do {
            // SSLEngine bytesProduced/bytesConsumed is unreliable, it doesn't really
            // take into account that wrap/unwrap in the context of a handshake may
            // "produce" bytes that are only used for the handshake, and not actual application
            // data.
            val available = unwrapBuffer.remaining()

            val decrypted = allocator.allocate()
            // must collapse into a single buffer because the unwrap call does not accept
            // an array of ByteBuffers
            val byteBuffer = unwrapBuffer.byteBuffer
            val result = engine.unwrap(byteBuffer, decrypted)
            decrypted.flip()
            var bytesProduced = decrypted.remaining()
            // track the allocation to estimate future allocation needs
            allocator.track(decrypted.remaining().toLong())
            // add any unused data back to the unwrap buffer
            unwrapBuffer.add(byteBuffer)
            val bytesConsumed = available - unwrapBuffer.remaining()
            // queue up the decrypted data for read
            readBuffer.add(decrypted)
            writable = decryptedPipe.write(readBuffer)

            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                // allow the loop to continue
                bytesProduced = -1
                allocator.setMinAlloc(allocator.getMinAlloc() * 2)
                continue
            }
            else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                break
            }

            handleHandshakeStatus(result.handshakeStatus)
        } while (bytesConsumed != 0 || bytesProduced != 0)

        return writable
    }

    override suspend fun close() {
    }

    val writeYielder = Cooperator()
    override suspend fun write(buffer: ReadableBuffers) {
        if (!wrap(buffer))
            writeYielder.yield()
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        return decryptedPipe.read(buffer)
    }
}

fun tls(host: String, engine: SSLEngine, options: AsyncTlsOptions? = null): AsyncCodec {
    return {
        AsyncTlsSocket(it, host, engine, options)
    }
}

