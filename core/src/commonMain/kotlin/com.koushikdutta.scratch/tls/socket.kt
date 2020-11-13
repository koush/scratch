package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.AllocationTracker
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers

class AsyncTlsSocket(override val socket: AsyncSocket, val engine: SSLEngine, private val options: AsyncTlsOptions?) : AsyncSocket, AsyncWrappingSocket, AsyncAffinity by socket {
    private var finishedHandshake = false
    private val socketRead = InterruptibleRead(socket)
    private val decryptAllocator = AllocationTracker()
    private val decryptedRead = socketRead.pipe {
        val unfiltered = ByteBufferList();
        while (true) {
            val awaitingHandshake = !finishedHandshake

            while (true) {
                val result = engine.unwrap(unfiltered, buffer, decryptAllocator)

                if (result.status == SSLEngineStatus.BUFFER_UNDERFLOW) {
                    // need more data, so just break and wait for another read to come in to
                    // trigger the read again.
                    break
                } else if (result.handshakeStatus == SSLEngineHandshakeStatus.NEED_WRAP) {
                    // this may complete the handshake
                    encryptedWrite(ByteBufferList())
                }
                else if (result.handshakeStatus == SSLEngineHandshakeStatus.NEED_UNWRAP) {
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

            if (!buffer.isEmpty)
                flush()

            if (!it(unfiltered) && unfiltered.isEmpty)
                break
        }
    }

    private val unencryptedWriteBuffer = ByteBufferList()
    private val encryptedWriteBuffer = ByteBufferList()
    private val encryptAllocator = AllocationTracker()
    private val encryptedWrite = AsyncWrite { buffer ->
        await()

        if (encryptedWriteBuffer.hasRemaining()) {
            socket.write(encryptedWriteBuffer)
            return@AsyncWrite
        }

        // move the unencrypted data from upstream into a working buffer.
        buffer.read(unencryptedWriteBuffer)

        if (finishedHandshake)
            encryptAllocator.minAlloc = unencryptedWriteBuffer.remaining()

        val awaitingHandshake = !finishedHandshake
        while (true) {
            val result = engine.wrap(unencryptedWriteBuffer, encryptedWriteBuffer, encryptAllocator)

            if (encryptedWriteBuffer.hasRemaining()) {
                // before the handshake is completed, ensure that all writes are fully written before
                // returning to the handshake loop.
                // without blocking on a
                // after completion, partial writes are used for back pressure.
                if (!awaitingHandshake)
                    socket.write(encryptedWriteBuffer)
                else
                    socket.drain(encryptedWriteBuffer as ReadableBuffers)
            }

            if (result.status == SSLEngineStatus.BUFFER_UNDERFLOW) {
                // this should never happen, as it is not possible to underflow
                // with application data
                break
            } else if (result.handshakeStatus == SSLEngineHandshakeStatus.NEED_UNWRAP) {
                socketRead.interrupt()
                break
            } else if (result.handshakeStatus == SSLEngineHandshakeStatus.NEED_WRAP) {
                continue
            }

            handleHandshakeStatus(result.handshakeStatus)

            if (awaitingHandshake && finishedHandshake)
                break
            if (finishedHandshake && unencryptedWriteBuffer.isEmpty)
                break
        }
    }

    var peerCertificates: Array<X509Certificate>? = null
        private set

    private suspend fun handleHandshakeStatus(status: SSLEngineHandshakeStatus) {
        if (status == SSLEngineHandshakeStatus.NEED_TASK)
            engine.runHandshakeTask()

        if (!finishedHandshake && engine.checkHandshakeStatus() == SSLEngineHandshakeStatus.FINISHED) {
            if (engine.useClientMode) {
                var trusted = true
                var peerUnverifiedCause: Exception? = null
                try {
                    trusted = false
                    val verifier: HostnameVerifier = options?.hostnameVerifier ?: DefaultHostnameVerifier
                    if (!verifier.verify(engine))
                        throw SSLException("hostname verification failed for <$engine.peerHost>")
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

    internal suspend fun awaitHandshake() {
        val handshakeBuffer = ByteBufferList()
        reader = AsyncRead {
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
