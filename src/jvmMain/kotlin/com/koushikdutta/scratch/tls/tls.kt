package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.Allocator
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.external.OkHostnameVerifier
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.connect
import java.security.cert.X509Certificate
import javax.net.ssl.*
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession

actual typealias SSLSession = SSLSession
actual typealias SSLEngine = javax.net.ssl.SSLEngine
actual typealias SSLContext = javax.net.ssl.SSLContext
actual typealias SSLException = javax.net.ssl.SSLException
actual typealias SSLHandshakeException = SSLHandshakeException

actual var SSLEngine.useClientMode: Boolean
    get() = this.useClientMode
    set(value) {
        this.useClientMode = value
    }

actual fun createTLSContext(): SSLContext {
    return SSLContext.getInstance("TLS")
}

actual fun getDefaultSSLContext(): SSLContext {
    return javax.net.ssl.SSLContext.getDefault()
}

actual class AsyncTlsSocket actual constructor(override val socket: AsyncSocket, val engine: SSLEngine, private val options: AsyncTlsOptions?) : AsyncWrappingSocket {
    private var finishedHandshake = false
    private val socketRead = InterruptibleRead(socket::read)

    private val decryptedRead = (socketRead::read as AsyncRead).pipe { read ->
        val unfiltered = ByteBufferList();
        decrypt@{ filtered ->
            if (!read(unfiltered) && unfiltered.isEmpty)
                return@decrypt false

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
                val byteBuffer = unfiltered.readByteBuffer()
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
                else if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    encryptedWrite(ByteBufferList())
                }
                else if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                    continue
                }

                handleHandshakeStatus(result.handshakeStatus)
            } while (bytesConsumed != 0 || bytesProduced != 0)

            true
        }
    }

    private val unencryptedWriteBuffer = ByteBufferList()
    private val encryptedWriteBuffer = ByteBufferList()
    private val encryptedWrite: AsyncWrite = { buffer ->
        buffer.read(unencryptedWriteBuffer)

        var alloced = calculateAlloc(unencryptedWriteBuffer.remaining())
        do {
            // if the handshake is finished, don't attempt to wrap 0 bytes of data.
            // this seems to terminate the ssl engine.
//            if (finishedHandshake && unencryptedWriteBuffer.isEmpty)
//                break

            // SSLEngine bytesProduced/bytesConsumed is unreliable, it doesn't really
            // take into account that wrap/unwrap in the context of a handshake may
            // "produce" bytes that are only used for the handshake, and not actual application
            // data.
            val available = unencryptedWriteBuffer.remaining()

            val encrypted = ByteBufferList.obtain(alloced)
            alloced = encrypted.remaining()
            val unencrypted = unencryptedWriteBuffer.readAll()
            val result = engine.wrap(unencrypted, encrypted)
            encrypted.flip()
            var bytesProduced = encrypted.remaining()
            // add unused unencrypted data back to the wrap buffer
            unencryptedWriteBuffer.addAll(*unencrypted)
            val bytesConsumed = available - unencryptedWriteBuffer.remaining()
            // queue up the encrypted data for write
            encryptedWriteBuffer.add(encrypted)
            socket.write(encryptedWriteBuffer)

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
            else if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                socketRead.interrupt()
            }
            else if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                continue
            }

            handleHandshakeStatus(result.handshakeStatus)
        } while (bytesConsumed != 0 || bytesProduced != 0)
    }

    private fun calculateAlloc(remaining: Int): Int {
        // alloc 50% more than we need for writing
        var alloc = remaining * 3 / 2
        if (alloc == 0)
            alloc = 8192
        return alloc
    }

    var peerCertificates: Array<X509Certificate>? = null
        private set

    private suspend fun handleHandshakeStatus(status: SSLEngineResult.HandshakeStatus) {
        if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            val task = engine.delegatedTask
            task.run()
        }

        if (!finishedHandshake && (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING || engine.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED)) {
            if (engine.useClientMode) {
                var trusted = true
                var peerUnverifiedCause: Throwable? = null
                try {
                    trusted = false
                    if (engine.peerHost != null) {
                        val verifier: HostnameVerifier = options?.hostnameVerifier ?: OkHostnameVerifier
                        if (!verifier.verify(engine.peerHost, engine.session))
                            throw SSLException("hostname verification failed for <$engine.peerHost>")
                    }
                    trusted = true
                }
                catch (exception: Exception) {
                    peerUnverifiedCause = exception
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
            encryptedWrite(ByteBufferList())
        }
    }

    private val allocator = Allocator().setMinAlloc(8192)

    override suspend fun close() {
        socket.close()
    }

    override suspend fun write(buffer: ReadableBuffers) {
        // do not allow empty writes
        if (buffer.isEmpty)
            return
        encryptedWrite(buffer)
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        return reader(buffer)
    }

    override suspend fun await() {
        socket.await()
    }

    // need a reader to catch any overflow from the handshake
    private var reader: AsyncRead = decryptedRead

    internal actual suspend fun awaitHandshake() {
        val handshakeBuffer = ByteBufferList()
        reader = {
            reader = decryptedRead
            handshakeBuffer.read(it)
            true
        }

        while (!finishedHandshake) {
            // the suspending read calls in awaitData will be interrupted when an
            // unwrap is necessary.
            // keep wrapping/unwrapping until the handshake finishes.

            // trigger a wrap call
            encryptedWrite(ByteBufferList())
            // trigger an unwrap call, wait for data
            // this will unsuspend once the handshake completes, even if no data is available.
            // an empty data set is still a valid
            if (!decryptedRead(handshakeBuffer) && !finishedHandshake)
                throw SSLException("socket unexpectedly closed")
        }
    }
}
