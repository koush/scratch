package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.NonBlockingWritePipe
import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.synchronized
import com.koushikdutta.scratch.uv.*
import kotlinx.cinterop.*
import platform.posix.sockaddr_in
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlin.system.getTimeMillis

actual fun milliTime(): Long {
    return getTimeMillis()
}

fun EventLoopClosedException(): Exception {
    return Exception("Event Loop Closed")
}

class UvSocket(val loop: AsyncEventLoop, socket: uv_stream_t) : AsyncSocket {
    internal val nio = object : NonBlockingWritePipe() {
        override fun writable() {
            uv_read_start(socket.ptr, allocBufferPtr, readCallbackPtr)
        }
    }
    internal val socket: AllocedHandle<uv_stream_t> = AllocedHandle(socket)

    init {
        socket.data = StableRef.create(this).asCPointer()
        uv_read_start(socket.ptr, allocBufferPtr, readCallbackPtr)
    }

    override suspend fun await() {
    }

    internal val writeAlloc = Alloced<uv_write_t>(nativeHeap.alloc())
    internal val bufsAlloc = AllocedArray<uv_buf_t>().ensure<uv_buf_t>(10)
    internal var pinnedBuffer: ByteBuffer? = null
    internal var pinned: Pinned<ByteArray>? = null
    override suspend fun read(buffer: WritableBuffers): Boolean {
        try {
            return nio.read(buffer)
        }
        finally {
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

    internal fun closeInternal() {
        if (!nio.hasEnded)
            nio.end(EventLoopClosedException())
    }

    override suspend fun close() {
        socket.free()
    }
}

class AsyncEventLoop : AsyncScheduler<AsyncEventLoop>() {
    override fun wakeup() {
        if (!running)
            return
        uv_async_send(wakeup.ptr)
    }

    internal suspend inline fun <T> safeSuspendCoroutine(crossinline block: (Continuation<T>) -> Unit): T {
        try {
            return suspendCoroutine(block)
        }
        finally {
            // this finally post block ensures that libuv callbacks are posted into the scheduler queue.
            // this ensures a clean C++/kotlin stack. otherwise, kotlin exceptions being thrown in C code go unhandled.
            // this is an analogue for the java selector pattern.
            post()
        }
    }

    suspend fun getAllByName(host: String): Array<InetAddress> = safeSuspendCoroutine {
        val self = this
        memScoped {
            val getAddrInfo = AllocedHandle(nativeHeap.alloc<uv_getaddrinfo_t>())
            getAddrInfo.struct.data = StableRef.create(it).asCPointer()
            uv_getaddrinfo(loop.ptr, getAddrInfo.ptr, addrInfoCallbackPtr, host, null, null)
        }
    }

    suspend fun connect(address: InetSocketAddress) = safeSuspendCoroutine<AsyncSocket> {
        val self = this
        memScoped {
            val addr = alloc<sockaddr_in>()
            uv_ip4_addr("127.0.0.1", address.port, addr.ptr)

            val socket = AllocedHandle(nativeHeap.alloc<uv_tcp_t>())
            val connect = Alloced(nativeHeap.alloc<uv_connect_t>())
            socket.struct.data = StableRef.create(ContinuationData(it, self)).asCPointer()
            // todo: Delete this is a test
            connect.struct.data = socket.struct.data
            handles[socket.ptr] = {}

            try {
                val initErr = uv_tcp_init(loop.ptr, socket.ptr)
                if (initErr != 0)
                    throw Exception("tcp init failed $initErr")

                connect.value = nativeHeap.alloc<uv_connect_t>()
                val connectErr = uv_tcp_connect(connect.ptr, socket.ptr, addr.ptr.reinterpret(), connectCallbackPtr)
                if (connectErr != 0)
                    throw Exception("tcp connect failed $connectErr")
            } catch (exception: Exception) {
                socket.free()
                connect.free()
                throw exception
            }
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

    private val loop = Alloced<uv_loop_t>(nativeHeap.alloc())
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
}

private fun checkZero(ret: Int, err: String) {
    if (ret == 0)
        return
    throw Exception("$err: $ret")
}

internal data class ContinuationData<C, T>(val resume: Continuation<C>, val data: T)