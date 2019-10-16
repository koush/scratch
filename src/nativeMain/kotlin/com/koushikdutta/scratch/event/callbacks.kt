package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.buffers.allocateByteBuffer
import com.koushikdutta.scratch.uv.*
import kotlinx.cinterop.*
import platform.CoreServices.gestaltATSUBiDiCursorPositionFeature
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.addrinfo
import platform.posix.sockaddr
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
    val socket = AllocedHandle(connect!!.pointed.handle!!.reinterpret<uv_tcp_t>().pointed)
    nativeHeap.free(connect.rawValue)

    val cdata = socket.freeData<ContinuationData<AsyncSocket, AsyncEventLoop>>()!!

    if (status != 0) {
        cdata.data.handles.remove(socket.ptr)
        socket.free()
        cdata.resume.resumeWithException(Exception("connect error: $status"))
        return
    }

    val uvSocket = UvSocket(cdata.data, socket.struct.reinterpret())
    cdata.data.handles[socket.ptr] = { uvSocket.closeInternal() }
    cdata.resume.resume(uvSocket)
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

internal val readCallbackPtr = staticCFunction(::readCallback)


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

internal val writeCallbackPtr = staticCFunction(::writeCallback)

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
           val addr = cur.ai_addr!!.readBytes(cur.ai_addrlen.toInt())
           if (cur.ai_family == AF_INET)
               list.add(Inet4Address(addr))
           else
               list.add(Inet6Address(addr))
       }
       curPtr = cur.ai_next
   }

//    uv_freeaddrinfo(res)
   resume.resume(list.toTypedArray())
}
internal val addrInfoCallbackPtr = staticCFunction(::addrInfoCallback)