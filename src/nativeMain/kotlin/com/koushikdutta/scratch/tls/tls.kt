package com.koushikdutta.scratch.tls

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers

actual class AsyncTlsSocket actual constructor(
    override val socket: AsyncSocket,
    val engine: SSLEngine,
    private val options: AsyncTlsOptions?
) : AsyncWrappingSocket, AsyncAffinity by socket {


    private val socketRead = InterruptibleRead({socket.read(it)})
    private val _sread: AsyncRead = {socketRead.read(it)}
    private val decryptedRead = _sread.pipe { read ->
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

                // must collapse into a single buffer because the unwrap call does not accept
                // an array of ByteBuffers
                val byteBuffer = unfiltered.readByteBuffer()
                val existing = filtered.remaining()
                val result = engine.unwrap(byteBuffer, filtered)
                val bytesProduced = filtered.remaining() - existing
                // add any unused data back to the unwrap buffer
                unfiltered.add(byteBuffer)
                val bytesConsumed = available - unfiltered.remaining()
                // queue up the decrypted data for read

                if (result == SSLStatus.SSL_ERROR_WANT_READ) {
                    continue
                } else if (result == SSLStatus.SSL_ERROR_WANT_WRITE) {
                    encryptedWrite(ByteBufferList())
                    break
                }
            } while (bytesConsumed != 0 || bytesProduced != 0)

            true
        }
    }


    private val unencryptedWriteBuffer = ByteBufferList()
    private val encryptedWriteBuffer = ByteBufferList()
    private val encryptedWrite: AsyncWrite = { buffer ->
        buffer.read(unencryptedWriteBuffer)

        do {
            // if the handshake is finished, don't attempt to wrap 0 bytes of data.
            // this seems to terminate the ssl engine.
            // if (engine.finishedHandshake && unencryptedWriteBuffer.isEmpty)
            //     break

            // SSLEngine bytesProduced/bytesConsumed is unreliable, it doesn't really
            // take into account that wrap/unwrap in the context of a handshake may
            // "produce" bytes that are only used for the handshake, and not actual application
            // data.
            val available = unencryptedWriteBuffer.remaining()

            val unencrypted = unencryptedWriteBuffer.readByteBuffer()
            val existing = encryptedWriteBuffer.remaining()
            val result = engine.wrap(unencrypted, encryptedWriteBuffer)
            val bytesProduced = encryptedWriteBuffer.remaining() - existing
            // add unused unencrypted data back to the wrap buffer
            unencryptedWriteBuffer.add(unencrypted)
            val bytesConsumed = available - unencryptedWriteBuffer.remaining()
            if (encryptedWriteBuffer.hasRemaining())
                socket.write(encryptedWriteBuffer)

            if (result == SSLStatus.SSL_ERROR_NONE) {
                continue
            }
            else if (result == SSLStatus.SSL_ERROR_WANT_WRITE) {
                continue
            } else if (result == SSLStatus.SSL_ERROR_WANT_READ) {
                socketRead.interrupt()
                break
            }
        } while (bytesConsumed != 0 || bytesProduced != 0)
    }

    override suspend fun close() {
        socket.close()
    }

    override suspend fun write(buffer: ReadableBuffers) {
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

        while (!engine.finishedHandshake) {
            // the suspending read calls in awaitData will be interrupted when an
            // unwrap is necessary.
            // keep wrapping/unwrapping until the handshake finishes.

            // trigger a wrap call
            encryptedWrite(ByteBufferList())
            if (engine.finishedHandshake) {
                // println("${engine.useClientMode} write handshake finished")
                break
            }

            // trigger an unwrap call, wait for data
            // this will unsuspend once the handshake completes, even if no data is available.
            // an empty data set is still a valid
            if (!decryptedRead(handshakeBuffer) && !engine.finishedHandshake)
                throw SSLException("socket unexpectedly closed")

            if (engine.finishedHandshake) {
                // println("${engine.useClientMode} read finished handhsake")
                break
            }
        }

        // some ssl implementations finish the handshake, but still have a final wrap.
        // ensure that happens.
        // also trigger a background read to read that final packet if sent
        // from the peer.
        socketRead.readTransient()
        encryptedWrite(ByteBufferList())
    }
}

