@file:Suppress("BlockingMethodInNonBlockingContext")

package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.*
import com.koushikdutta.scratch.stream.closeQuietly
import java.io.IOException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.coroutines.Continuation
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine


actual class AsyncNetworkServerSocket internal constructor(val server: AsyncEventLoop, private val channel: ServerSocketChannel) : AsyncServerSocket<AsyncNetworkSocket>, AsyncAffinity by server {
    private val key: SelectionKey = channel.register(server.selector.selector, SelectionKey.OP_ACCEPT)
    actual val localAddress: InetAddress = channel.socket().inetAddress!!
    actual val localPort = channel.socket().localPort

    init {
        key.attach(this)
    }

    private fun closeInternal() {
        channel.closeQuietly()
        try {
            key.cancel()
        }
        catch (e: Exception) {
        }
    }

    override suspend fun close() {
        closeInternal()
        queue.end()
    }

    override suspend fun close(throwable: Throwable) {
        closeInternal()
        queue.end(throwable)
    }

    // todo: backlog?
    private val queue = AsyncQueue<AsyncNetworkSocket>()

    override fun accept(): AsyncIterable<AsyncNetworkSocket> {
        return queue
    }

    internal fun accepted() {
        var sc: SocketChannel? = null
        try {
            sc = channel.accept()
            if (sc == null)
                return
            sc.configureBlocking(false)
            val key = sc.register(server.selector.selector, SelectionKey.OP_READ)
            val socket = AsyncNetworkSocket(server, sc, key)
            key.attach(socket)
            queue.add(socket)
        }
        catch (e: IOException) {
            sc?.closeQuietly()
        }
    }
}

interface NIOChannel {
    fun readable(): Int
    fun writable()
}

private data class NIODatagramPacket(val remoteAddress: InetSocketAddress, val data: ByteBuffer)


actual class AsyncDatagramSocket internal constructor(val server: AsyncEventLoop, private val channel: DatagramChannel, private val key: SelectionKey) : AsyncSocket, NIOChannel, AsyncAffinity by server {
    actual val localPort = channel.socket().localPort
    val localAddress = channel.socket().localAddress!!
    private val output = BlockingWritePipe {
        while (it.hasRemaining()) {
            val before = it.remaining()
            it.readBuffers(channel::write)
            it.takeReclaimedBuffers(pending)
            val after = it.remaining()
            if (before == after) {
                key.interestOps(SelectionKey.OP_WRITE or key.interestOps())
                break
            }
        }
    }
    private var closed = false
    private val iterable = AsyncQueue<NIODatagramPacket>()
    private val pending = ByteBufferList()

    init {
        key.attach(this)
    }

    var broadcast: Boolean
        get() = channel.socket().broadcast
        set(value) {
            channel.socket().broadcast = value
        }

    override fun writable() {
        key.interestOps(SelectionKey.OP_WRITE.inv() and key.interestOps())
        output.writable()
    }

    override suspend fun write(buffer: ReadableBuffers) {
        await()
        output.write(buffer)
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        receivePacket(buffer)
        return true
    }

    override fun readable(): Int {
        var total = 0
        try {
            val buffer = pending.obtain(8192 * 2)
            val address: InetSocketAddress?
            if (!channel.isConnected) {
                address = channel.receive(buffer) as java.net.InetSocketAddress?
                if (address == null) {
                    pending.reclaim(buffer)
                    iterable.end()
                    return total
                }
            }
            else {
                val read = channel.read(buffer)
                if (read == 0) {
                    pending.reclaim(buffer)
                    return total
                }
                if (read < 0) {
                    pending.reclaim(buffer)
                    iterable.end()
                    return total
                }
                address = channel.socket().remoteSocketAddress as InetSocketAddress
            }
            buffer.flip()
            total += buffer.remaining()
            iterable.add(NIODatagramPacket(address, buffer))

        }
        catch (exception: Exception) {
            closed = true
            closeInternal()
            iterable.end(exception)
            output.close(exception)
        }

        return total
    }

    actual suspend fun receivePacket(buffer: WritableBuffers): InetSocketAddress {
        await()
        pending.takeReclaimedBuffers(buffer)

        val packet = iterable.iterator().next()
        buffer.add(packet.data)
        return packet.remoteAddress
    }

    actual suspend fun sendPacket(socketAddress: InetSocketAddress, buffer: ReadableBuffers) {
        await()

        val singleBuffer = buffer.readByteBuffer()
        channel.send(singleBuffer, socketAddress)
    }

    private fun closeInternal() {
        channel.closeQuietly()
        try {
            key.cancel()
        }
        catch (e: Exception) {
        }

        if (!closed) {
            closed = true
            iterable.end()
        }
    }

    override suspend fun close() {
        await()
        closeInternal()
    }

    actual suspend fun connect(socketAddress: InetSocketAddress) {
        await()
        channel.connect(socketAddress)
    }

    actual suspend fun disconnect() {
        await()
        channel.disconnect()
    }
}

actual class AsyncNetworkSocket internal constructor(actual val loop: AsyncEventLoop, private val channel: SocketChannel, private val key: SelectionKey) : AsyncRead, AsyncWrite, AsyncSocket, NIOChannel, AsyncAffinity by loop {
    val socket = channel.socket()
    actual val localPort = channel.socket().localPort
    val localAddress = channel.socket().localAddress
    val remoteAddress: java.net.InetSocketAddress? = channel.socket().remoteSocketAddress as InetSocketAddress?
    private val inputBuffer = ByteBufferList()
    private var closed = false
    private val allocator = AllocationTracker()
    private val input = NonBlockingWritePipe {
        await()
        key.interestOps(SelectionKey.OP_READ or key.interestOps())
    }
    private val output = BlockingWritePipe {
        await()
        while (it.hasRemaining()) {
            val before = it.remaining()
            it.readBuffers(channel::write)
            val after = it.remaining()
            if (before == after) {
                key.interestOps(SelectionKey.OP_WRITE or key.interestOps())
                break
            }
        }
    }

    private fun closeInternal(t: Throwable?) {
        channel.closeQuietly()
        try {
            key.cancel()
        }
        catch (e: Exception) {
        }

        val attachment = key.attachment()
        key.attach(null)
        if (attachment is Continuation<*>) {
            attachment.resumeWithException(t ?: CancellationException())
        }

        if (!closed) {
            closed = true
            if (t == null) {
                input.end()
                output.close()
            }
            else {
                input.end(t)
                output.close(t)
            }
        }
    }

    override suspend fun close() {
        await()
        closeInternal(null)
    }

    private val trackingSocketReader: BuffersBuffersWriter<Int> = {
        val ret = channel.read(it).toInt()
        if (ret > 0)
            allocator.trackDataUsed(ret)
        ret
    }

    private fun flushInputBuffer() {
        if (inputBuffer.isEmpty)
            return
        if (!input.write(inputBuffer))
            key.interestOps(SelectionKey.OP_READ.inv() and key.interestOps())
    }

    override fun readable(): Int {
        try {
            var read: Int
            do {
                read = inputBuffer.putAllocatedBuffers(allocator.requestNextAllocation(), trackingSocketReader)
                // flushing within the loop to get the buffer allocations recycling quicker
                flushInputBuffer()
            }
            while (read > 0)
            allocator.finishTracking()

            // clean close
            if (read < 0) {
                closeInternal(null)
            }
        } catch (e: Exception) {
            flushInputBuffer()
            allocator.finishTracking()

            // transport failure caused close
            closeInternal(e)
        }

        return allocator.lastAlloc
    }

    override fun writable() {
        key.interestOps(SelectionKey.OP_WRITE.inv() and key.interestOps())
        output.writable()
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        await()
        return input.read(buffer)
    }

    override suspend fun write(buffer: ReadableBuffers) {
        await()
        output.write(buffer)
    }

    actual suspend fun connect(socketAddress: InetSocketAddress) {
        await()

        if (key.attachment() != null)
            throw IllegalStateException("connection in progress")

        key.interestOps(SelectionKey.OP_CONNECT)

        val finishConnect = suspendCoroutine<Boolean> {
            key.attach(it)
            val finished = channel.connect(socketAddress)
            if (finished)
                it.resume(false)
        }

        if (finishConnect && !channel.finishConnect()) {
            key.cancel()
            throw IOException("socket failed to connect")
        }

        key.attach(this)
        key.interestOps(SelectionKey.OP_READ)
    }
}
