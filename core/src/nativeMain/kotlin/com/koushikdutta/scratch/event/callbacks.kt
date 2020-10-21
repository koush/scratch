package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.IOException
import com.koushikdutta.scratch.buffers.allocateByteBuffer
import com.koushikdutta.scratch.uv.*
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException


private fun asyncCallback(asyncHandle: CPointer<uv_async_t>?) {
    val loop = asyncHandle!!.pointed.data!!.asStableRef<AsyncEventLoop>().get()
    loop.onIdle()
}

internal val asyncCallbackPtr = staticCFunction(::asyncCallback)

private fun timerCallback(timerHandle: CPointer<uv_timer_t>?) {
    val loop = timerHandle!!.pointed.data!!.asStableRef<AsyncEventLoop>().get()
    loop.onIdle()
}

internal val timerCallbackPtr = staticCFunction(::timerCallback)

private fun connectCallback(connect: CPointer<uv_connect_t>?, status: Int) {
//    val socket = connect!!.pointed.handle!!.reinterpret<uv_tcp_t>().pointed
    val resume = freeStableRef<Continuation<Unit>>(connect!!.pointed.data)!!
    if (status == 0)
        resume.resume(Unit)
    else
        resume.resumeWithException(IOException("connect error: $status"))
}

internal val connectCallbackPtr = staticCFunction(::connectCallback)

fun closeCallback(handle: CPointer<uv_handle_t>?) {
    nativeHeap.free(handle!!.pointed)
}

internal val closeCallbackPtr = staticCFunction(::closeCallback)

fun readCallback(handle: CPointer<uv_stream_s>?, size: Long, buf: CPointer<uv_buf_t>?) {
    val socket = handle!!.pointed.data!!.asStableRef<UvSocket>().get()
    socket.pinned!!.unpin()
    socket.pinned = null
    val buffer = socket.pinnedBuffer!!
    socket.pinnedBuffer = null

    if (size < 0 || socket.nio.hasEnded) {
        if (size.toInt() == UV_EOF)
            socket.nio.end()
        else
            socket.nio.end(IOException("read error: $size"))
        return
    }

    val length = size.toInt()
    buffer.limit(length)
    socket.buffers.add(buffer)
    if (!socket.nio.write(socket.buffers))
        uv_read_stop(socket.socket.ptr)
}

internal val readCallbackPtr = staticCFunction(::readCallback)


fun writeCallback(ptr: CPointer<uv_write_t>?, status: Int) {
    // todo: unpin
    val write = ptr!!.pointed
    val resume = freeStableRef<Continuation<Unit>>(write.data)!!
    write.data = null

    if (status != 0) {
        resume.resumeWithException(IOException("write error: $status"))
        return
    }

    resume.resume(Unit)
}

internal val writeCallbackPtr = staticCFunction(::writeCallback)

fun allocBufferCallback(handle: CPointer<uv_handle_t>?, size: ULong, buf: CPointer<uv_buf_t>?) {
    val socket = handle!!.pointed.data!!.asStableRef<UvSocket>().get()
    if (socket.pinned != null)
        throw IOException("pending allocation?")

    val buffer = socket.buffers.obtain(size.toInt())
    val pinned = buffer.array().pin()
    socket.pinnedBuffer = buffer
    socket.pinned = pinned

    val pointed = buf!!.pointed
    pointed.len = buffer.remaining().toULong()
    pointed.base = pinned.addressOf(0)
}

internal val allocBufferPtr = staticCFunction(::allocBufferCallback)

fun addrInfoCallback(req: CPointer<uv_getaddrinfo_t>?, status: Int, res: CPointer<addrinfo>?) {
    val getAddrInfo = req!!.pointed
    val resume = freeStableRef<Continuation<Array<InetAddress>>>(getAddrInfo.data)!!
    getAddrInfo.data = null
    nativeHeap.free(req.pointed)

    val list = arrayListOf<InetAddress>()

    var curPtr = res
    while (curPtr != null) {
        val cur = curPtr.pointed
        if (cur.ai_socktype == SOCK_STREAM) {
            val sockaddr = cur.ai_addr!!.pointed.readValue()

            if (cur.ai_family == AF_INET) {
                list.add(Inet4Address(sockaddr))
            } else if (cur.ai_family == AF_INET6) {
                list.add(Inet6Address(sockaddr))
            }
        }
        curPtr = cur.ai_next
    }

    uv_freeaddrinfo(res)
    resume.resume(list.toTypedArray())
}

internal val addrInfoCallbackPtr = staticCFunction(::addrInfoCallback)

fun listenCallback(stream: CPointer<uv_stream_t>?, status: Int) {
    val socket = stream!!.pointed.data!!.asStableRef<UvServerSocket>().get()
    if (status != 0)
        socket.queue.end(IOException("listen failed: $status"))
    else
        socket.queue.add(socket)
}

internal val listenCallbackPtr = staticCFunction(::listenCallback)

internal fun readAddrInfoBytes(addr: CPointer<sockaddr>): ByteArray {
    return addr.readBytes(addr.pointed.sa_len.toInt())
}