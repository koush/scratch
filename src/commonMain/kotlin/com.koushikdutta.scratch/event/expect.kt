package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.AsyncServerSocket
import com.koushikdutta.scratch.AsyncSocket

expect open class InetAddress
expect class Inet4Address : InetAddress
expect class Inet6Address : InetAddress
expect class InetSocketAddress(addr: InetAddress, port: Int)

internal expect fun nanoTime(): Long
internal expect fun milliTime(): Long

expect class AsyncEventLoop(): AsyncScheduler<AsyncEventLoop> {
    fun run()
    suspend fun getAllByName(host: String): Array<InetAddress>
    suspend fun connect(socketAddress: InetSocketAddress): AsyncNetworkSocket
    suspend fun listen(port: Int = 0, address: InetAddress? = null, backlog: Int = 5): AsyncNetworkServerSocket
    // todo: should be suspend
    fun stop()

    companion object {
        fun parseInet4Address(address: String): Inet4Address
        fun parseInet6Address(address: String): Inet6Address
        fun getInterfaceAddresses(): Array<InetAddress>
        val default: AsyncEventLoop
    }
}

expect class AsyncNetworkSocket: AsyncSocket
expect class AsyncNetworkServerSocket : AsyncServerSocket<AsyncNetworkSocket> {
    val localPort: Int
}
