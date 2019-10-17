package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.ByteBuffer
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

class UvSocket(val loop: UvEventLoop, socket: uv_stream_t) : AsyncSocket {
    internal val nio = object : NonBlockingWritePipe() {
        override fun writable() {
            uv_read_start(socket.ptr, allocBufferPtr, readCallbackPtr)
        }
    }
    internal val socket: AllocedHandle<uv_stream_t> = AllocedHandle(loop, socket)

    init {
        // set a handle destructor to ensure the correspoding UvSocket is also cleaned up
        loop.handles[socket.ptr] = ::closeInternal
        // socket handle data points to this instance
        socket.data = StableRef.create(this).asCPointer()
        // start reading.
        uv_read_start(socket.ptr, allocBufferPtr, readCallbackPtr)
    }

    override suspend fun await() {
        loop.await()
    }

    internal val writeAlloc = Alloced<uv_write_t>(nativeHeap.alloc())
    internal val bufsAlloc = AllocedArray<uv_buf_t>().ensure<uv_buf_t>(10)
    internal var pinnedBuffer: ByteBuffer? = null
    internal var pinned: Pinned<ByteArray>? = null
    override suspend fun read(buffer: WritableBuffers): Boolean {
        try {
            return nio.read(buffer)
        } finally {
            loop.post()
        }
    }

    internal var writeBuffers: Array<ByteBuffer>? = null
    internal var pinnedWrites: List<Pinned<ByteArray>>? = null
    override suspend fun write(buffer: ReadableBuffers) {
        if (buffer.isEmpty)
            return
        writeBuffers = buffer.readAll()
        pinnedWrites = writeBuffers!!.map { it.array().pin() }
        bufsAlloc.ensure<uv_buf_t>(pinnedWrites!!.size)
        for (i in pinnedWrites!!.indices) {
            val b = pinnedWrites!![i]
            val buf = bufsAlloc.array!![i]
            buf.base = pinnedWrites!![i].addressOf(0)
            buf.len = writeBuffers!![i].remaining().toULong()
        }
        return loop.safeSuspendCoroutine {
            writeAlloc.struct.data = StableRef.create(it).asCPointer()
            uv_write(writeAlloc.ptr, socket.ptr, bufsAlloc.array, bufsAlloc.length.toUInt(), writeCallbackPtr)
        }
    }

    private fun closeInternal() {
        if (!nio.hasEnded)
            nio.end(EventLoopClosedException())
    }

    override suspend fun close() {
        await()
        socket.free()
        closeInternal()
    }
}

actual typealias AsyncNetworkServerSocket = UvServerSocket

class UvServerSocket(val loop: UvEventLoop, socket: uv_tcp_t, val localPort: Int) : AsyncServerSocket {
    internal val socket: AllocedHandle<uv_tcp_t> = AllocedHandle(loop, socket)

    init {
        // set a handle destructor to ensure the correspoding UvSocket is also cleaned up
        loop.handles[socket.ptr] = ::closeInternal
        // socket handle data points to this instance
        socket.data = StableRef.create(this).asCPointer()
    }

    internal val queue = AsyncDequeueIterator<UvServerSocket>()
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
            yield(UvSocket(loop, client.struct.reinterpret()))
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

    override suspend fun await() {
        loop.await()
    }

    internal fun closeInternal() {
        if (!queue.hasEnded)
            queue.end()
    }
}


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

    suspend fun getAllByName(host: String): Array<InetAddress> = safeSuspendCoroutine {
        val getAddrInfo = Alloced(nativeHeap.alloc<uv_getaddrinfo_t>())
        getAddrInfo.struct.data = StableRef.create(it).asCPointer()
        uv_getaddrinfo(loop.ptr, getAddrInfo.ptr, addrInfoCallbackPtr, host, null, null)
    }

    private suspend fun connectInternal(socket: AllocedHandle<uv_tcp_t>, connect: Alloced<uv_connect_t>, port: Int, address: InetAddress) = safeSuspendCoroutine<Unit> {
        try {
            checkZero(uv_tcp_init(loop.ptr, socket.ptr), "tcp init failed")
            // track the socket handle destruction, in case the event loop gets closed before the connect completes.
            handles[socket.ptr] = { it.resumeWithException(EventLoopClosedException()) }
            socket.struct.data = StableRef.create(it).asCPointer()

            connect.value = nativeHeap.alloc()
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
        } catch (exception: Exception) {
            socket.free()
            connect.free()
            throw exception
        }
    }

    suspend fun connect(socketAddress: InetSocketAddress): UvSocket {
        // these need to be allocated outside of the memScope as they need to stay alive for the entire duration of the
        // call, until the callback is invoked.
        val connect = Alloced(nativeHeap.alloc<uv_connect_t>())
        val socket = AllocedHandle(this, nativeHeap.alloc<uv_tcp_t>())
        try {
            connectInternal(socket, connect, socketAddress.port, socketAddress.address)
        }
        catch (exception: Exception) {
            socket.free()
            throw exception
        }
        finally {
            connect.free()
        }

        return UvSocket(this, socket.struct.reinterpret())
    }

    suspend fun listen(port: Int = 0, address: InetAddress? = null, backlog: Int = 5): UvServerSocket {
        val socket = AllocedHandle<uv_tcp_t>(this, nativeHeap.alloc())

        try {
            checkZero(uv_tcp_init(loop.ptr, socket.ptr), "tcp init failed")

            memScoped {
                val sockaddrPtr: CPointer<sockaddr>
                // todo sanity check addresses
                if (address == null) {
                    // libuv notes: 0.0.0.0 binds ipv4, :: binds ipv4 and ipv6.
                    // can specify ipv6 only with the UV_UDP_IPV6ONLY flag
                    val sockaddr: sockaddr_in6 = alloc()
                    uv_ip6_addr("0.0.0.0", port, sockaddr.ptr)
                    sockaddrPtr = sockaddr.ptr.reinterpret()
                }
                else if (address is Inet4Address) {
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

                return UvServerSocket(this@UvEventLoop, socket.struct, localPort)
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

    fun stop() {
        if (!running)
            return

        scheduleShutdown {
            uv_timer_stop(timer.ptr)

            val copy = HashMap(handles)
            handles.clear()
            for (handle in copy) {
                uv_close(handle.key.reinterpret(), closeCallbackPtr)
                handle.value()
            }

            running = false
        }
    }

    internal val loop = Alloced<uv_loop_t>(nativeHeap.alloc())
    private val wakeup = Alloced<uv_async_t>(nativeHeap.alloc())
    private val timer = Alloced<uv_timer_t>(nativeHeap.alloc())
    private var running = false
    private val thisRef = StableRef.create(this).asCPointer()
    internal val handles = mutableMapOf<CPointer<*>, () -> Unit>()

    override val isAffinityThread: Boolean = running

    init {
        checkZero(uv_loop_init(loop.ptr), "uv_loop_init failed")
        wakeup.struct.data = thisRef
        timer.struct.data = thisRef
        checkZero(uv_timer_init(loop.ptr, timer.ptr), "uv_timer_init failed")
    }

    fun run() {
        checkZero(uv_async_init(loop.ptr, wakeup.ptr, asyncCallbackPtr), "uv_async_init failed")
        handles[wakeup.ptr] = {}

        synchronized(this) {
            check(!running) { "loop already running" }
            running = true
        }

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
                checkZero(uv_ip4_addr(address, 88, sockaddr.ptr), "not an Inet4Address")
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
