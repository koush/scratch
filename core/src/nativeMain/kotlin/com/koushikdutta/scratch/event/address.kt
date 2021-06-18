package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.uv.uv_ip4_name
import com.koushikdutta.scratch.uv.uv_ip6_name
import kotlinx.cinterop.*
import platform.posix.sockaddr
import platform.posix.sockaddr_in

actual open class InetAddress internal constructor(internal val sockaddr: CValue<sockaddr>) {
    protected open fun getName(pinnedSockaddr: CPointer<sockaddr>, pinnedString: Pinned<ByteArray>, length: ULong) {
    }

    override fun toString(): String {
        val length = 64
        val addressString = ByteArray(length)
        memScoped {
            addressString.usePinned { pinnedString ->
                getName(sockaddr.ptr, pinnedString, length.toULong())
            }
        }

        return addressString.decodeToString()
    }
}
actual class Inet4Address internal constructor(sockaddr: CValue<sockaddr>) : InetAddress(sockaddr) {
    override fun getName(pinnedSockaddr: CPointer<sockaddr>, pinnedString: Pinned<ByteArray>, length: ULong) {
        uv_ip4_name(pinnedSockaddr.reinterpret(), pinnedString.addressOf(0), length)
    }
}

actual class Inet6Address internal constructor(sockaddr: CValue<sockaddr>) : InetAddress(sockaddr) {
    override fun getName(pinnedSockaddr: CPointer<sockaddr>, pinnedString: Pinned<ByteArray>, length: ULong) {
        uv_ip6_name(pinnedSockaddr.reinterpret(), pinnedString.addressOf(0), length)
    }
}

actual class InetSocketAddress actual constructor(private val addr: InetAddress, private val port: Int) {
    actual constructor(port: Int) : this(UvEventLoop.parseInet4Address("0.0.0.0"), port)

    actual fun getPort(): Int {
        return port
    }
    actual fun getAddress(): InetAddress {
        return addr
    }
}

actual fun getLoopbackAddress(): InetAddress {
    return UvEventLoop.parseInet4Address("127.0.0.1")
}