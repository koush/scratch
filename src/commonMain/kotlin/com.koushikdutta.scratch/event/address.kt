package com.koushikdutta.scratch.event

class RemoteSocketAddress(override val address: String, port: Int) : SocketAddress(address, port)
open class SocketAddress(open val address: String?, val port: Int)
