package com.koushikdutta.scratch.event

enum class InetAddressFamily {
    INET4,
    INET6,
}

expect open class InetAddress
expect class Inet4Address: InetAddress
expect class Inet6Address: InetAddress
expect class InetSocketAddress