package com.koushikdutta.scratch

import com.koushikdutta.scratch.buffers.*
import com.koushikdutta.scratch.uv.*
import kotlinx.cinterop.*
import platform.posix.sockaddr_in
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class RemoteSocketAddress(override val address: String, port: Int) : SocketAddress(address, port)
open class SocketAddress(open val address: String?, val port: Int)

private fun connectCallback(connect: CPointer<uv_connect_t>?, status: Int) {
    val socket = AllocedHandle(connect!!.pointed.handle!!.reinterpret<uv_tcp_t>().pointed)
    nativeHeap.free(connect.rawValue)

    val resume = socket.freeData<Continuation<AsyncSocket>>()!!

    if (status != 0) {
        socket.free()
        resume.resumeWithException(Exception("connect error: $status"))
        return
    }

    val uvSocket = UvSocket(socket.struct.reinterpret())
    resume.resume(uvSocket)
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

class UvSocket(socket: uv_stream_t): AsyncSocket {
    val nio = object : NonBlockingWritePipe() {
        override fun writable() {
            uv_read_start(socket.ptr, allocBufferPtr, readCallbackPtr)
        }
    }
    val socket: AllocedHandle<uv_stream_t> = AllocedHandle(socket)
    init {
        socket.data = StableRef.create(this).asCPointer()
        uv_read_start(socket.ptr, allocBufferPtr, readCallbackPtr)
    }

    override suspend fun await() {
    }

    val writeAlloc = Alloced<uv_write_t>(nativeHeap.alloc())
    val bufsAlloc = AllocedPtr<uv_buf_t>().ensure<uv_buf_t>(10)
    var pinnedBuffer: ByteBuffer? = null
    var pinned: Pinned<ByteArray>? = null
    override suspend fun read(buffer: WritableBuffers): Boolean {
        return nio.read(buffer)
    }

    var writeBuffers: Array<ByteBuffer>? = null
    var pinnedWrites: List<Pinned<ByteArray>>? = null
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

    override suspend fun close() {
        socket.free()
    }
}

class AllocedPtr<T: CStructVar>() {
    var array: CArrayPointer<T>? = null

    var length: Int = 0

    fun free() {
        if (array == null)
            return
        val a = array!!
        array = null
        length = 0
        nativeHeap.free(a)
    }

    inline fun <reified T2: T> ensure(length: Int): AllocedPtr<T> {
        if (this.length >= length)
            return this
        free()
        array = nativeHeap.allocArray<T2>(length).reinterpret()
        this.length = length
        return this
    }
}

private fun <R> freeStableRef(data: COpaquePointer?): R? {
    if (data == null)
        return null
    val ref = data.asStableRef<Any>()
    val ret = ref.get()
    ref.dispose()
    return ret as R
}

class AllocedHandle<T: CStructVar>(value: T? = null, destructor: T.() -> Unit = {}): Alloced<T>(value, destructor) {
    private fun <R> freeData(value: T): R? {
        val handle: uv_handle_t = value.reinterpret()
        val data = handle.data
        handle.data = null
        return freeStableRef(data)
    }
    fun <R> freeData(): R? {
        return freeData(struct)
    }
    override fun freePointer(value: T) {
        freeData<Any>(value)
        uv_close(value.ptr.reinterpret(), closeCallbackPtr)
    }
}

open class Alloced<T: CStructVar>(var value: T? = null, private val destructor: T.() -> Unit = {}) {
    val struct: T
        get() = value!!
    val ptr: CPointer<T>
        get() = struct.ptr

    fun free() {
        if (value == null)
            return
        val v = value!!
        value = null
        try {
            destructor(v)
        }
        finally {
            freePointer(v)
        }
    }
    open fun freePointer(value: T) {
        nativeHeap.free(value.ptr)
    }
}

class AsyncEventLoop {
    private var loop = Alloced<uv_loop_t>()

    suspend fun connect(address: RemoteSocketAddress): AsyncSocket = suspendCoroutine {
        memScoped {
            val addr = alloc<sockaddr_in>()
            uv_ip4_addr(address.address, address.port, addr.ptr)

            val socket = AllocedHandle(nativeHeap.alloc<uv_tcp_t>())
            val connect = Alloced(nativeHeap.alloc<uv_connect_t>())

            socket.struct.data = StableRef.create(it).asCPointer()
            try {
                val initErr = uv_tcp_init(loop.ptr, socket.ptr)
                if (initErr != 0)
                    throw Exception("tcp init failed $initErr")

                connect.value = nativeHeap.alloc<uv_connect_t>()
                val connectErr = uv_tcp_connect(connect.ptr, socket.ptr, addr.ptr.reinterpret(), connectCallbackPtr)
                if (connectErr != 0)
                    throw Exception("tcp connect failed $connectErr")
            }
            catch (exception: Exception) {
                socket.free()
                connect.free()
                throw exception
            }
        }
    }

    fun run(block: AsyncEventLoop.() -> Unit) {
        if (synchronized(this) {
            if (loop.value != null)
                return@synchronized true
            loop.value = nativeHeap.alloc()
            val ret = uv_loop_init(loop.ptr)
            if (ret != 0) {
                nativeHeap.free(loop.ptr)
                throw Exception("loop init failed $ret")
            }
            false
        }) return

        try {
            block()
            uv_run(loop.ptr, UV_RUN_DEFAULT);
        }
        catch (exception: Exception) {
            println("loop exception")
            println(exception)
            throw exception
        }
        finally {
            loop.free()
        }
    }
}