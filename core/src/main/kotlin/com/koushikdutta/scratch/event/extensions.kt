package com.koushikdutta.scratch.event

import com.koushikdutta.scratch.async.async
import kotlinx.coroutines.ExperimentalCoroutinesApi

suspend fun AsyncEventLoop.getByName(host: String): InetAddress {
    return getAllByName(host)[0]
}

suspend fun AsyncNetworkSocket.connect(host: String, port: Int) {
    connect(InetSocketAddress(loop.getByName(host), port))
}

suspend fun AsyncEventLoop.connect(host: String, port: Int): AsyncNetworkSocket {
    return connect(InetSocketAddress(getByName(host), port))
}

@OptIn(ExperimentalCoroutinesApi::class)
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
    return ret.getCompleted()
}

fun AsyncEventLoop.runUnit(block: suspend AsyncEventLoop.() -> Unit) {
    run(block)
}

suspend fun AsyncEventLoop.connect(socketAddress: InetSocketAddress): AsyncNetworkSocket {
    val ret = createSocket()
    val deferred = async {
        ret.connect(socketAddress)
        ret
    }

    try {
        return deferred.await()
    }
    catch (throwable: Throwable) {
        ret.close()
        throw throwable
    }
}
