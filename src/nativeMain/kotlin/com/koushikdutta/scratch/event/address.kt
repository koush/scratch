package com.koushikdutta.scratch.event

actual open class InetAddress(internal val bytes: ByteArray)

actual class Inet4Address internal constructor(bytes: ByteArray): InetAddress(bytes)
actual class Inet6Address internal constructor(bytes: ByteArray): InetAddress(bytes)

actual class InetSocketAddress {
    val address: InetAddress?
    val hostName: String?
    val port: Int

    constructor(hostName: String, port: Int) {
        this.hostName = hostName
        this.address = null
        this.port = port
    }
    constructor(address: InetAddress, port: Int) {
        this.address = address
        this.hostName = null
        this.port = port
    }
}
