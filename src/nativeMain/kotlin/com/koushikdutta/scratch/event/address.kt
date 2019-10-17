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

        return addressString.stringFromUtf8()
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

actual class InetSocketAddress actual constructor(addr: InetAddress, port: Int) {
    val address: InetAddress = addr
    val port: Int = port
}
