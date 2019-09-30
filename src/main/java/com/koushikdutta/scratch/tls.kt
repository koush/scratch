package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.*
import com.koushikdutta.scratch.external.OkHostnameVerifier
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.*

interface AsyncTlsTrustFailureCallback {
    fun handleOrRethrow(throwable: Throwable)
}

class AsyncTlsOptions(internal val trustManagers: Array<TrustManager>? = null, internal val hostnameVerifier: HostnameVerifier? = null, internal val trustFailureCallback: AsyncTlsTrustFailureCallback?)

class AsyncTlsSocket(override val socket: AsyncSocket, private val host: String?, private val engine: SSLEngine, private val options: AsyncTlsOptions?) : AsyncWrappingSocket {
    private var finishedHandshake = false
    private val socketRead = InterruptibleRead(socket::read)
    private val decryptFilter = object : AsyncReadFilter(socketRead::read) {
        override fun filter(unfiltered: Buffers, filtered: Buffers) {
            // SSLEngine.unwrap
            do {
                // SSLEngine bytesProduced/bytesConsumed is unreliable, it doesn't really
                // take into account that wrap/unwrap in the context of a handshake may
                // "produce" bytes that are only used for the handshake, and not actual application
                // data.
                val available = unfiltered.remaining()

                val decrypted = allocator.allocate()
                // must collapse into a single buffer because the unwrap call does not accept
                // an array of ByteBuffers
                val byteBuffer = unfiltered.byteBuffer
                val result = engine.unwrap(byteBuffer, decrypted)
                decrypted.flip()
                var bytesProduced = decrypted.remaining()
                // track the allocation to estimate future allocation needs
                allocator.track(decrypted.remaining().toLong())
                // add any unused data back to the unwrap buffer
                unfiltered.add(byteBuffer)
                val bytesConsumed = available - unfiltered.remaining()
                // queue up the decrypted data for read
                filtered.add(decrypted)

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
        }
    }

    private val encryptFilter = object : AsyncWriteFilter(this::safeWrite) {
        // SSLEngine.wrap
        override fun filter(unfiltered: Buffers, filtered: Buffers) {
            var alloced = calculateAlloc(unfiltered.remaining())
            do {
                // if the handshake is finished, don't attempt to wrap 0 bytes of data.
                // this seems to terminate the ssl engine.
                if (finishedHandshake && unfiltered.isEmpty)
                    break

                // SSLEngine bytesProduced/bytesConsumed is unreliable, it doesn't really
                // take into account that wrap/unwrap in the context of a handshake may
                // "produce" bytes that are only used for the handshake, and not actual application
                // data.
                val available = unfiltered.remaining()

                val encrypted = ByteBufferList.obtain(alloced)
                alloced = encrypted.remaining()
                val unencrypted = unfiltered.all
                val result = engine.wrap(unencrypted, encrypted)
                encrypted.flip()
                var bytesProduced = encrypted.remaining()
                // add unused unencrypted data back to the wrap buffer
                unfiltered.addAll(*unencrypted)
                val bytesConsumed = available - unfiltered.remaining()
                // queue up the encrypted data for write
                filtered.add(encrypted)

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
            encryptFilter.invokeWrite()
        }

        if (status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
            socketRead.interrupt()
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
            socketRead.interrupt()
            encryptFilter.invokeWrite()
        }
    }

    private val allocator = Allocator().setMinAlloc(8192)

    override suspend fun close() {
    }

    override suspend fun write(buffer: ReadableBuffers) {
        encryptFilter.write(buffer)
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        return decryptFilter.read(buffer)
    }

    override suspend fun await() {
        socket.await()
    }

    internal suspend fun awaitHandshake() {
        while (!finishedHandshake) {
            // the suspending read calls in awaitData will be interrupted when an
            // unwrap is necessary.
            // keep wrapping/unwrapping until the handshake finishes.

            // trigger a wrap call
            encryptFilter.invokeWrite()
            // trigger an unwrap call, wait for data
            // this will unsuspend once the handshake completes, even if no data is available.
            // an empty data set is still a valid
            if (!decryptFilter.read() && !finishedHandshake)
                throw SSLException("socket unexpectedly closed")
        }
    }

    private val writeHandler = AwaitHandler(this::await)
    private suspend fun safeWrite(buffer: ReadableBuffers) {
        writeHandler.run {
            socket.write(buffer)
        }
    }
}

suspend fun tlsHandshake(socket: AsyncSocket, host: String, engine: SSLEngine, options: AsyncTlsOptions? = null): AsyncTlsSocket {
    val tlsSocket = AsyncTlsSocket(socket , host, engine, options)
    tlsSocket.awaitHandshake()
    return tlsSocket
}

