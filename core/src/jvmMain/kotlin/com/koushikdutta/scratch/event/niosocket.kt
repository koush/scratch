package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.*
import java.io.IOException
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel


actual typealias AsyncNetworkServerSocket = NIOServerSocket
class NIOServerSocket internal constructor(val server: AsyncEventLoop, private val channel: ServerSocketChannel) : AsyncServerSocket<AsyncNetworkSocket>, AsyncAffinity by server {
    private val key: SelectionKey = channel.register(server.mSelector.selector, SelectionKey.OP_ACCEPT)
    val localAddress: InetAddress = channel.socket().inetAddress!!
    val localPort = channel.socket().localPort

    init {
        key.attach(this)
    }

    private var closed = false
    override suspend fun close() {
        closeQuietly(channel)
        try {
            key.cancel()
        }
        catch (e: Exception) {
        }
        if (!closed) {
            closed = true
            queue.end()
        }
    }

    // todo: backlog?
    private val queue = AsyncQueue<NIOSocket>()

    override fun accept(): AsyncIterable<AsyncNetworkSocket> {
        return queue
    }

    internal fun accepted() {
//        server.post {
        var sc: SocketChannel? = null
        try {
            sc = channel.accept()
            if (sc == null)
                return
            sc.configureBlocking(false)
            queue.add(NIOSocket(server, sc, sc.register(server.mSelector.selector, SelectionKey.OP_READ)))
        }
        catch (e: IOException) {
            closeQuietly(sc)
        }
//        }
    }
}

actual typealias AsyncDatagramSocket = NIODatagram

interface NIOChannel {
    fun readable(): Int
    fun writable()
}

private data class NIODatagramPacket(val remoteAddress: InetSocketAddress, val data: ByteBuffer)


class NIODatagram internal constructor(val server: AsyncEventLoop, private val channel: DatagramChannel, private val key: SelectionKey) : AsyncSocket, NIOChannel, AsyncAffinity by server {
    val localPort = channel.socket().localPort
    val localAddress = channel.socket().localAddress!!
    private val output = BlockingWritePipe {
        while (it.hasRemaining()) {
            val before = it.remaining()
            val buffers = it.readAll()
            channel.write(buffers)
            it.addAll(*buffers)
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

    suspend fun receivePacket(buffer: WritableBuffers): InetSocketAddress {
        await()
        pending.takeReclaimedBuffers(buffer)

        val packet = iterable.iterator().next()
        buffer.add(packet.data)
        return packet.remoteAddress
    }

    suspend fun sendPacket(socketAddress: InetSocketAddress, buffer: ReadableBuffers) {
        await()

        val singleBuffer = buffer.readByteBuffer()
        channel.send(singleBuffer, socketAddress)
    }

    private fun closeInternal() {
        closeQuietly(channel)
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

    suspend fun connect(socketAddress: InetSocketAddress) {
        await()
        channel.socket().connect(socketAddress)
    }

    suspend fun disconnect() {
        await()
        channel.socket().disconnect()
    }
}

actual typealias AsyncNetworkSocket = NIOSocket

class NIOSocket internal constructor(val server: AsyncEventLoop, private val channel: SocketChannel, private val key: SelectionKey) : AsyncSocket, NIOChannel, AsyncAffinity by server {
    init {
        key.attach(this)
    }

    val socket = channel.socket()
    val localPort = channel.socket().localPort
    val localAddress = channel.socket().localAddress
    val remoteAddress: java.net.InetSocketAddress? = channel.socket().remoteSocketAddress as InetSocketAddress
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
            val buffers = it.readAll()
            channel.write(buffers)
            it.addAll(*buffers)
            val after = it.remaining()
            if (before == after) {
                key.interestOps(SelectionKey.OP_WRITE or key.interestOps())
                break
            }
        }
    }

    private fun closeInternal(t: Throwable?) {
        closeQuietly(channel)
        try {
            key.cancel()
        }
        catch (e: Exception) {
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

    private val trackingSocketReader: BuffersBufferWriter<Int> = {
        val ret = channel.read(it)
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
                read = inputBuffer.putAllocatedBuffer(allocator.requestNextAllocation(), trackingSocketReader)
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
}
