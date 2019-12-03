package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.AsyncServerSocket
import com.koushikdutta.scratch.AsyncSocket
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.ReadableBuffers
import com.koushikdutta.scratch.buffers.WritableBuffers

expect open class InetAddress
expect class Inet4Address : InetAddress
expect class Inet6Address : InetAddress
expect class InetSocketAddress(addr: InetAddress, port: Int) {
    constructor(port: Int)

    fun getPort(): Int
    fun getAddress(): InetAddress
}

internal expect fun nanoTime(): Long
internal expect fun milliTime(): Long

expect class AsyncEventLoop(): AsyncScheduler<AsyncEventLoop> {
    fun run()
    suspend fun getAllByName(host: String): Array<InetAddress>
    suspend fun connect(socketAddress: InetSocketAddress): AsyncNetworkSocket
    suspend fun createDatagram(port: Int = 0, address: InetAddress? = null, reuseAddress: Boolean = false): AsyncDatagramSocket
    suspend fun listen(port: Int = 0, address: InetAddress? = null, backlog: Int = 5): AsyncNetworkServerSocket
    // todo: should be suspend
    fun stop(wait: Boolean = false)

    companion object {
        fun parseInet4Address(address: String): Inet4Address
        fun parseInet6Address(address: String): Inet6Address
        fun getInterfaceAddresses(): Array<InetAddress>
        val default: AsyncEventLoop
    }
}

expect class AsyncNetworkSocket: AsyncSocket {
    val localPort: Int
}
expect class AsyncNetworkServerSocket : AsyncServerSocket<AsyncNetworkSocket> {
    val localPort: Int
    val localAddress: InetAddress
}

expect class AsyncDatagramSocket: AsyncSocket {
    val localPort: Int
    suspend fun receivePacket(buffer: WritableBuffers): InetSocketAddress
    suspend fun sendPacket(socketAddress: InetSocketAddress, buffer: ReadableBuffers)
    suspend fun connect(socketAddress: InetSocketAddress)
    suspend fun disconnect()
}
