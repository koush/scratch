package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.AllocationTracker
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.external.OkHostnameVerifier
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSession

actual typealias SSLSession = SSLSession
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

actual class AsyncTlsSocket actual constructor(override val socket: AsyncSocket, val engine: SSLEngine, private val options: AsyncTlsOptions?) : AsyncWrappingSocket, AsyncAffinity by socket {
    private var finishedHandshake = false
    private val socketRead = InterruptibleRead(socket::read)
    private val decryptAllocator = AllocationTracker()
    private val decryptedRead = (socketRead::read as AsyncRead).pipe {
        val unfiltered = ByteBufferList();
        while (it(unfiltered) || !unfiltered.isEmpty) {
            val awaitingHandshake = !finishedHandshake

            while (true) {
                // must collapse into a single buffer because the unwrap call does not accept
                // an array of ByteBuffers
                val byteBuffer = unfiltered.readByteBuffer()

                val result = buffer.putAllocatedBuffer(decryptAllocator.requestNextAllocation()) {
                    val before = it.remaining()
                    val ret = engine.unwrap(byteBuffer, it)
                    val bytesProduced = before - it.remaining()
                    // track the allocation to estimate future allocation needs
                    decryptAllocator.trackDataUsed(bytesProduced)
                    ret
                }

                // add any unused data back to the unwrap buffer
                unfiltered.add(byteBuffer)

                if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                    // allow the loop to continue
                    decryptAllocator.minAlloc *= 2
                    continue
                } else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                    // need more data, so just break and wait for another read to come in to
                    // trigger the read again.
                    break
                } else if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    // this may complete the handshake
                    encryptedWrite(ByteBufferList())
                } else if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                    // wants an unwrap, a read is not necessary
                    continue
                }

                handleHandshakeStatus(result.handshakeStatus)
                // flush possibly empty buffer on handshake status change to trigger handshake completion
                if (awaitingHandshake && finishedHandshake) {
                    flush()
                    break
                }

                // if there's no handshake, and also no data left, just bail.
                if (finishedHandshake && unfiltered.isEmpty)
                    break
            }

            decryptAllocator.finishTracking()
            if (!buffer.isEmpty)
                flush()
        }
    }

    private val unencryptedWriteBuffer = ByteBufferList()
    private val encryptedWriteBuffer = ByteBufferList()
    private val encryptAllocator = AllocationTracker()
    private val encryptedWrite: AsyncWrite = write@{ buffer ->
        await()

        if (encryptedWriteBuffer.hasRemaining()) {
            socket.write(encryptedWriteBuffer)
            return@write
        }

        // move the unencrypted data from upstream into a working buffer.
        buffer.read(unencryptedWriteBuffer)

        if (finishedHandshake)
            encryptAllocator.minAlloc = unencryptedWriteBuffer.remaining()

        val awaitingHandshake = !finishedHandshake
        while (true) {
            // SSLEngine bytesProduced/bytesConsumed is unreliable, it doesn't really
            // take into account that wrap/unwrap in the context of a handshake may
            // "produce" bytes that are only used for the handshake, and not actual application
            // data.
            val available = unencryptedWriteBuffer.remaining()

            val unencrypted = unencryptedWriteBuffer.readAll()
            val result = encryptedWriteBuffer.putAllocatedBuffer(encryptAllocator.requestNextAllocation()) {
                val before = it.remaining()
                val ret = engine.wrap(unencrypted, it)
                val bytesProduced = before - it.remaining()
                // track the allocation to estimate future allocation needs
                encryptAllocator.trackDataUsed(bytesProduced)
                ret
            }

            // add unused unencrypted data back to the wrap buffer
            unencryptedWriteBuffer.addAll(*unencrypted)

            // queue up the encrypted data for write
            if (encryptedWriteBuffer.hasRemaining()) {
                // before the handshake is completed, ensure that all writes are fully written before
                // returning to the handshake loop.
                // without blocking on a
                // after completion, partial writes are used for back pressure.
                if (!awaitingHandshake)
                    socket.write(encryptedWriteBuffer)
                else
                    socket::write.drain(encryptedWriteBuffer)
            }

            if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                // allow the loop to continue
                encryptAllocator.minAlloc *= 2
                continue
            } else if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                // this should never happen, as it is not possible to underflow
                // with application data
                break
            } else if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                socketRead.interrupt()
                break
            } else if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                continue
            }

            handleHandshakeStatus(result.handshakeStatus)

            if (awaitingHandshake && finishedHandshake)
                break
            if (finishedHandshake && unencryptedWriteBuffer.isEmpty)
                break
        }
        encryptAllocator.finishTracking()
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
                var peerUnverifiedCause: Exception? = null
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

    override suspend fun close() {
        socket.close()
    }

    override suspend fun write(buffer: ReadableBuffers) {
        // do not allow empty writes. this causes ssl engine to terminate on some platforms.
        if (buffer.isEmpty)
            return
        encryptedWrite(buffer)
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        return reader(buffer)
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

        // some ssl implementations finish the handshake, but still have a final wrap.
        // ensure that happens.
        // also trigger a background read to read that final packet if sent
        // from the peer.
        socketRead.readTransient()
        encryptedWrite(ByteBufferList())
    }
}
