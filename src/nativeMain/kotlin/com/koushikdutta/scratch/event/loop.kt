package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.NonBlockingWritePipe
import com.koushikdutta.scratch.buffers.ByteBuffer
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.buffers.allocateByteBuffer
import com.koushikdutta.scratch.synchronized
import com.koushikdutta.scratch.uv.*
import kotlinx.cinterop.*
import platform.posix.sockaddr_in
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

fun EventLoopClosedException(): Exception {
    return Exception("Event Loop Closed")
}

private fun asyncCallback(asyncHandle: CPointer<uv_async_t>?) {
    val loop = asyncHandle!!.pointed.data!!.asStableRef<AsyncEventLoop>().get()
    loop.onIdle()
}

private val asyncCallbackPtr = staticCFunction(::asyncCallback)

private fun timerCallback(timerHandle: CPointer<uv_timer_t>?) {
    val loop = timerHandle!!.pointed.data!!.asStableRef<AsyncEventLoop>().get()
    loop.onIdle()
    loop.stop()
}

private val timerCallbackPtr = staticCFunction(::timerCallback)

private fun connectCallback(connect: CPointer<uv_connect_t>?, status: Int) {
    val socket = AllocedHandle(connect!!.pointed.handle!!.reinterpret<uv_tcp_t>().pointed)
    nativeHeap.free(connect.rawValue)

    val cdata = socket.freeData<ContinuationData<AsyncSocket, AsyncEventLoop>>()!!

    if (status != 0) {
        cdata.data.handles.remove(socket.ptr)
        socket.free()
        cdata.resume.resumeWithException(Exception("connect error: $status"))
        return
    }

    val uvSocket = UvSocket(socket.struct.reinterpret())
    cdata.data.handles[socket.ptr] = { uvSocket.closeInternal() }
    cdata.resume.resume(uvSocket)
}

private val connectCallbackPtr = staticCFunction(::connectCallback)

fun closeCallback(handle: CPointer<uv_handle_t>?) {
    nativeHeap.free(handle!!.pointed)
}

val closeCallbackPtr = staticCFunction(::closeCallback)

fun readCallback(handle: CPointer<uv_stream_s>?, size: Long, buf: CPointer<uv_buf_t>?) {
    val socket = handle!!.pointed.data!!.asStableRef<UvSocket>().get()
    socket.pinned!!.unpin()
    socket.pinned = null
    val buffer = socket.pinnedBuffer!!
    socket.pinnedBuffer = null

    if (size < 0 || socket.nio.hasEnded) {
        // todo: recycle buffer
        if (!socket.nio.hasEnded) {
            if (size.toInt() == UV_EOF)
                socket.nio.end()
            else
                socket.nio.end(Exception("read error: $size"))
        }
        return
    }

    val length = size.toInt()
    buffer.limit(length)
    if (!socket.nio.write(buffer))
        uv_read_stop(socket.socket.ptr)
}

val readCallbackPtr = staticCFunction(::readCallback)


fun writeCallback(ptr: CPointer<uv_write_t>?, status: Int) {
    // todo: unpin
    val write = ptr!!.pointed
    val resume = freeStableRef<Continuation<Unit>>(write.data)!!
    write.data = null

    if (status != 0) {
        resume.resumeWithException(Exception("write error: $status"))
        return
    }

    resume.resume(Unit)
}

private val writeCallbackPtr = staticCFunction(::writeCallback)

fun allocBufferCallback(handle: CPointer<uv_handle_t>?, size: ULong, buf: CPointer<uv_buf_t>?) {
    val socket = handle!!.pointed.data!!.asStableRef<UvSocket>().get()
    if (socket.pinned != null)
        throw Exception("pending allocation?")

    val buffer = allocateByteBuffer(size.toInt())
    val pinned = buffer.array().pin()
    socket.pinnedBuffer = buffer
    socket.pinned = pinned

    val pointed = buf!!.pointed
    pointed.len = buffer.remaining().toULong()
    pointed.base = pinned.addressOf(0)
}

val allocBufferPtr = staticCFunction(::allocBufferCallback)

class UvSocket(socket: uv_stream_t) : AsyncSocket {
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
        return nio.read(buffer)
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
        return suspendCoroutine {
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

class AsyncEventLoop(private val idle: AsyncEventLoop.() -> Unit = {}) {
    suspend fun connect(address: RemoteSocketAddress) = suspendCoroutine<AsyncSocket> {
        val self = this
        memScoped {
            val addr = alloc<sockaddr_in>()
            uv_ip4_addr(address.address, address.port, addr.ptr)

            val socket = AllocedHandle(nativeHeap.alloc<uv_tcp_t>())
            val connect = Alloced(nativeHeap.alloc<uv_connect_t>())
            socket.struct.data = StableRef.create(ContinuationData(it, self)).asCPointer()
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
        idle()
    }

    fun scheduleWakeup(timeout: Long) {
        uv_timer_stop(timer.ptr)
        uv_timer_start(timer.ptr, timerCallbackPtr, timeout.toULong(), 0)
    }

    fun stop() {
        if (!running)
            return

        uv_timer_stop(timer.ptr)

        val copy = HashMap(handles)
        handles.clear()
        for (handle in copy) {
            println("destructor1")
            uv_close(handle.key.reinterpret(), closeCallbackPtr)
            println("destructor2")
            handle.value()
        }
    }

    private val loop = Alloced<uv_loop_t>(nativeHeap.alloc())
    private val wakeup = Alloced<uv_async_t>(nativeHeap.alloc())
    private val timer = Alloced<uv_timer_t>(nativeHeap.alloc())
    private var running = false
    private val thisRef = StableRef.create(this).asCPointer()
    internal val handles = mutableMapOf<CPointer<*>, () -> Unit>()

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
            uv_run(loop.ptr, UV_RUN_DEFAULT)
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

private data class ContinuationData<C, T>(val resume: Continuation<C>, val data: T)