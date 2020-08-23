package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.async.async

suspend fun AsyncEventLoop.getByName(host: String): InetAddress {
    return getAllByName(host)[0]
}

suspend fun AsyncEventLoop.connect(host: String, port: Int): AsyncNetworkSocket {
    return connect(InetSocketAddress(getByName(host), port))
}

fun <T> AsyncEventLoop.run(block: suspend AsyncEventLoop.() -> T): T {
    val ret = async {
        try {
            block()
        }
        finally {
            stop()
        }
    }

    run()
    return ret.getOrThrow()
}

fun AsyncEventLoop.runUnit(block: suspend AsyncEventLoop.() -> Unit) {
    run(block)
}

suspend fun AsyncEventLoop.connect(socketAddress: InetSocketAddress): AsyncNetworkSocket {
    val ret = createSocket()
    try {
        ret.connect(socketAddress)
        return ret
    }
    catch (throwable: Throwable) {
        ret.close()
        throw throwable
    }
}
