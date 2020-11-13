package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.uv.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.system.getTimeMillis
import kotlin.system.getTimeNanos

actual fun milliTime(): Long {
    return getTimeMillis()
}
actual fun nanoTime(): Long {
    return getTimeNanos()
}

fun EventLoopClosedException(): Exception {
    return Exception("Event Loop Closed")
}

actual typealias AsyncNetworkSocket = UvSocket

class UvSocket internal constructor(val loop: UvEventLoop, internal val socket: AllocedHandle<uv_stream_t>) : AsyncSocket, AsyncAffinity by loop {
    val localPort: Int
    internal val nio = NonBlockingWritePipe {
        uv_read_start(socket.ptr, allocBufferPtr, readCallbackPtr)
    }

    init {
        // set a handle destructor to ensure the correspoding UvSocket is also cleaned up
        loop.handles[this.socket] = ::closeInternal

        // socket handle data points to this instance
        socket.struct.data = StableRef.create(this).asCPointer()

        localPort = memScoped {
            val sockaddr: sockaddr_storage = alloc()
            val sockaddrSize = alloc<IntVar>()
            sockaddrSize.value = sockaddr_storage.size.toInt()
            uv_tcp_getsockname(socket.ptr.reinterpret(), sockaddr.ptr.reinterpret(), sockaddrSize.ptr)
            getAddrPort(sockaddr.reinterpret<sockaddr_in>().sin_port.toInt())
        }
    }

    private val writeAlloc = Alloced<uv_write_t>(nativeHeap.alloc())
    private val bufsAlloc = AllocedArray<uv_buf_t>().ensure<uv_buf_t>(10)
    internal val buffers = ByteBufferList()
    internal var pinnedBuffer: ByteBuffer? = null
    internal var pinned: Pinned<ByteArray>? = null
    override suspend fun read(buffer: WritableBuffers): Boolean {
        try {
            return nio.read(buffer)
        } finally {
            loop.post()
        }
    }

    private var pinnedWrites: List<Pinned<ByteArray>>? = null
    override suspend fun write(buffer: ReadableBuffers) {
        if (buffer.isEmpty)
            return
        val writeBuffers = buffer.readAll()
        pinnedWrites = writeBuffers.map { it.array().pin() }
        bufsAlloc.ensure<uv_buf_t>(pinnedWrites!!.size)
        for (i in pinnedWrites!!.indices) {
            val b = pinnedWrites!![i]
            val buf = bufsAlloc.array!![i]
            buf.base = pinnedWrites!![i].addressOf(0)
            buf.len = writeBuffers[i].remaining().toULong()
        }
        try {
            return loop.safeSuspendCoroutine {
                writeAlloc.struct.data = StableRef.create(it).asCPointer()
                uv_write(writeAlloc.ptr, socket.ptr, bufsAlloc.array, bufsAlloc.length.toUInt(), writeCallbackPtr)
            }
        }
        finally {
            for (pinnedWrite in pinnedWrites!!) {
                pinnedWrite.unpin()
            }
            buffer.reclaim(*writeBuffers!!)
        }
    }

    private fun closeInternal() {
        nio.end(EventLoopClosedException())
    }

    override suspend fun close() {
        await()
        socket.free()
        nio.end()
    }

    internal fun startReading() {
        uv_read_start(socket.ptr, allocBufferPtr, readCallbackPtr)
    }

    internal suspend fun connectInternal(socket: AllocedHandle<uv_tcp_t>, connect: Alloced<uv_connect_t>, port: Int, address: InetAddress) = loop.safeSuspendCoroutine<Unit> {
        // track the socket handle destruction, in case the event loop gets closed before the connect completes.
        loop.handles[socket] = {
            closeInternal()
            it.resumeWithException(EventLoopClosedException())
        }
        connect.struct.data = StableRef.create(it).asCPointer()

        memScoped {
            val connectErr: Int
            // todo sanity check addresses
            if (address is Inet4Address) {
                val sockaddr: sockaddr_in = alloc()
                uv_ip4_addr(address.toString(), port, sockaddr.ptr)
                connectErr = uv_tcp_connect(connect.ptr, socket.ptr, sockaddr.ptr.reinterpret(), connectCallbackPtr)
            } else {
                val sockaddr: sockaddr_in6 = alloc()
                uv_ip6_addr(address.toString(), port, sockaddr.ptr)
                connectErr = uv_tcp_connect(connect.ptr, socket.ptr, sockaddr.ptr.reinterpret(), connectCallbackPtr)
            }
            checkZero(connectErr, "tcp connect failed")
        }
    }

    suspend fun connect(socketAddress: InetSocketAddress) {
        // these need to be allocated outside of the memScope as they need to stay alive for the entire duration of the
        // call, until the callback is invoked.
        val connect = Alloced(nativeHeap.alloc<uv_connect_t>())
        try {
            connectInternal(socket as AllocedHandle<uv_tcp_t>, connect, socketAddress.getPort(), socketAddress.getAddress())
        }
        catch (throwable: Throwable) {
            throw throwable
        }
        finally {
            loop.handles[this.socket] = ::closeInternal
            connect.free()
        }

        loop.post()
        startReading()
    }
}

actual typealias AsyncNetworkServerSocket = UvServerSocket

class UvServerSocket(val loop: UvEventLoop, socket: uv_tcp_t, val localAddress: InetAddress, val localPort: Int) : AsyncServerSocket<UvSocket>, AsyncAffinity by loop {
    internal val socket: AllocedHandle<uv_tcp_t> = AllocedHandle(loop, socket)

    init {
        // set a handle destructor to ensure the correspoding UvSocket is also cleaned up
        loop.handles[this.socket] = ::closeInternal
        // socket handle data points to this instance
        socket.data = StableRef.create(this).asCPointer()
    }

    internal val queue = AsyncQueue<UvServerSocket>()
    val acceptIter = asyncIterator<UvSocket> {
        val iter = queue.iterator()
        while (iter.hasNext()) {
            // server is the same as socket, just queueing itself to keep track
            // of the number of accepts that can be called
            iter.next()

            // get off uv callback stack. can yield catch/post like safeSuspendCoroutine?
            loop.post()

            val client = AllocedHandle(loop, nativeHeap.alloc<uv_tcp_t>())
            try {
                checkZero(uv_tcp_init(loop.loop.ptr, client.ptr), "tcp init failed")
            }
            catch (exception: Throwable) {
                client.free()
                throw exception
            }

            checkZero(uv_accept(socket.ptr.reinterpret(), client.ptr.reinterpret()), "accept failed")
            val uvSocket = UvSocket(loop, client as AllocedHandle<uv_stream_t>)
            uvSocket.startReading()
            yield(uvSocket)
        }
    }

    val acceptIterable = object : AsyncIterable<UvSocket> {
        override fun iterator(): AsyncIterator<UvSocket> {
            return acceptIter
        }
    }

    override fun accept(): AsyncIterable<out UvSocket> {
        return acceptIterable
    }

    override suspend fun close() {
        await()
        socket.free()
        closeInternal()
    }

    override suspend fun close(throwable: Throwable) {
        await()
        socket.free()
        closeInternal(throwable)
    }

    private fun closeInternal() {
        queue.end()
    }

    private fun closeInternal(throwable: Throwable) {
        queue.end(throwable)
    }
}

class UvDatagram(val loop: UvEventLoop): AsyncSocket, AsyncAffinity by loop {
    val localPort: Int = 0
    suspend fun receivePacket(buffer: WritableBuffers): InetSocketAddress {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
    suspend fun sendPacket(socketAddress: InetSocketAddress, buffer: ReadableBuffers) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
    suspend fun connect(socketAddress: InetSocketAddress) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
    suspend fun disconnect() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override suspend fun close() {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    override suspend fun write(buffer: ReadableBuffers) {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }
}

actual typealias AsyncDatagramSocket = UvDatagram


actual typealias AsyncEventLoop = UvEventLoop

class UvEventLoop : AsyncScheduler<UvEventLoop>() {
    override fun wakeup() {
        if (!running)
            return
        uv_async_send(wakeup.ptr)
    }

    internal suspend inline fun <T> safeSuspendCoroutine(crossinline block: (Continuation<T>) -> Unit): T {
        try {
            return suspendCoroutine(block)
        }
        catch (exception: Exception) {
            // this catch block ensures that exceptions are posted into the scheduler queue.
            // this ensures a clean C++/kotlin stack. otherwise, kotlin exceptions being thrown in C code go unhandled.

            // the alternative approach is to post all callbacks onto the scheduler queue, but this would be
            // expensive for reads. that approach would be the analogue for the java selector pattern.
            post()
            throw exception
        }
    }

    suspend fun createDatagram(port: Int = 0, address: InetAddress? = null, reuseAddress: Boolean = false): AsyncDatagramSocket {
        TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
    }

    suspend fun getAllByName(host: String): Array<InetAddress> = safeSuspendCoroutine {
        val getAddrInfo = Alloced(nativeHeap.alloc<uv_getaddrinfo_t>())
        getAddrInfo.struct.data = StableRef.create(it).asCPointer()
        uv_getaddrinfo(loop.ptr, getAddrInfo.ptr, addrInfoCallbackPtr, host, null, null)
    }

    suspend fun createSocket(): UvSocket {
        await()
        val socket = AllocedHandle(this, nativeHeap.alloc<uv_tcp_t>())
        checkZero(uv_tcp_init(loop.ptr, socket.ptr), "tcp init failed")
        return UvSocket(this, socket as AllocedHandle<uv_stream_t>)
    }

    suspend fun listen(port: Int = 0, address: InetAddress? = null, backlog: Int = 5): UvServerSocket {
        await()
        return listenInternal(port, address ?: parseInet4Address("0.0.0.0"), backlog)
    }

    suspend fun AsyncServer.listen(port: Int = 0, address: InetAddress? = null, backlog: Int = 5) =
            listen(this@UvEventLoop.listen(port, address, backlog))

    private suspend fun listenInternal(port: Int = 0, address: InetAddress, backlog: Int): UvServerSocket {
        val socket = AllocedHandle<uv_tcp_t>(this, nativeHeap.alloc())

        try {
            checkZero(uv_tcp_init(loop.ptr, socket.ptr), "tcp init failed")

            memScoped {
                val sockaddrPtr: CPointer<sockaddr>
                // todo sanity check addresses
                if (address is Inet4Address) {
                    val sockaddr: sockaddr_in = alloc()
                    uv_ip4_addr(address.toString(), port, sockaddr.ptr)
                    sockaddrPtr = sockaddr.ptr.reinterpret()
                } else {
                    val sockaddr: sockaddr_in6 = alloc()
                    uv_ip6_addr(address.toString(), port, sockaddr.ptr)
                    sockaddrPtr = sockaddr.ptr.reinterpret()
                }
                checkZero(uv_tcp_bind(socket.ptr.reinterpret(), sockaddrPtr, 0), "tcp bind failed")
                checkZero(uv_listen(socket.ptr.reinterpret(), backlog, listenCallbackPtr), "listen failed")

                val data = alloc(sockaddr_storage.size, sockaddr_storage.align)
                val sockaddrSize = alloc<IntVar>()
                sockaddrSize.value = sockaddr_storage.size.toInt()
                checkZero(uv_tcp_getsockname(socket.ptr, data.reinterpret<sockaddr>().ptr, sockaddrSize.ptr), "getsockname failed")
                val localPort = getAddrPort(data.reinterpret<sockaddr_in>().sin_port.toInt())

                return UvServerSocket(this@UvEventLoop, socket.struct, address, localPort)
            }
        }
        catch (exception: Exception) {
            socket.free()
            throw exception
        }
    }

    internal fun onIdle() {
        uv_stop(loop.ptr)
    }

    fun scheduleWakeup(timeout: Long) {
        uv_timer_stop(timer.ptr)
        uv_timer_start(timer.ptr, timerCallbackPtr, timeout.toULong(), 0)
    }

    fun stop() = stop(false)
    fun stop(wait: Boolean = false) {
        if (!running)
            return

        scheduleShutdown {
            uv_timer_stop(timer.ptr)

            val copy = HashMap(handles)
            handles.clear()
            for (handle in copy) {
                handle.key.free()
                handle.value()
            }

            running = false
        }
    }

    internal val loop = Alloced<uv_loop_t>(nativeHeap.alloc())
    private val wakeup = AllocedHandle<uv_async_t>(this, nativeHeap.alloc())
    private val timer = Alloced<uv_timer_t>(nativeHeap.alloc())
    private var running = false
    private val thisRef = StableRef.create(this).asCPointer()
    internal val handles = mutableMapOf<AllocedHandle<*>, () -> Unit>()

    override val isAffinityThread: Boolean
        get() = running

    init {
        checkZero(uv_loop_init(loop.ptr), "uv_loop_init failed")
        wakeup.struct.data = thisRef
        timer.struct.data = thisRef
        checkZero(uv_timer_init(loop.ptr, timer.ptr), "uv_timer_init failed")
    }

    fun run() {
        checkZero(uv_async_init(loop.ptr, wakeup.ptr, asyncCallbackPtr), "uv_async_init failed")
        handles[wakeup] = {}

        check(!running) { "loop already running" }
        running = true

        try {
            while (running) {
                val wait = lockAndRunQueue()
                if (wait != QUEUE_EMPTY)
                    scheduleWakeup(wait)

                uv_run(loop.ptr, UV_RUN_DEFAULT)
            }
        } finally {
            running = false
        }
    }

    companion object {
        val default = UvEventLoop()

        fun parseInet4Address(address: String): Inet4Address {
            memScoped {
                val sockaddr: sockaddr_in = alloc()
                checkZero(uv_ip4_addr(address, 0, sockaddr.ptr), "not an Inet4Address")
                return Inet4Address(sockaddr.reinterpret<sockaddr>().readValue())
            }
        }
        fun parseInet6Address(address: String): Inet6Address {
            memScoped {
                val sockaddr: sockaddr_in6 = alloc()
                checkZero(uv_ip6_addr(address, 0, sockaddr.ptr), "not an Inet6Address")
                return Inet6Address(sockaddr.reinterpret<sockaddr>().readValue())
            }
        }
        fun getInterfaceAddresses(): Array<InetAddress> {
            val ret = arrayListOf<InetAddress>()
            memScoped {
                val addresses: CPointerVar<uv_interface_address_t> = alloc()
                val addressCount: IntVar = alloc()

                checkZero(uv_interface_addresses(addresses.ptr, addressCount.ptr), "error in getInterfaceAddresses")
                val count = addressCount.value
                for (i in 0 until count) {
                    val addr = addresses.value!!.get(i).address.reinterpret<sockaddr>()
                    if (addr.sa_family.toInt() == AF_INET)
                        ret.add(Inet4Address(addr.readValue()))
                    else if (addr.sa_family.toInt() == AF_INET6)
                        ret.add(Inet6Address(addr.readValue()))
                }
                uv_free_interface_addresses(addresses.ptr.pointed.value, addressCount.ptr.pointed.value)
            }

            return ret.toTypedArray()
        }
    }
}

private fun getAddrPort(bigEndianPort: Int): Int {
    return ((bigEndianPort shl 8) and 0xff00) or ((bigEndianPort shr 8) and 0xff)
}

private fun checkZero(ret: Int, err: String) {
    if (ret == 0)
        return
    throw Exception("$err: $ret")
}
