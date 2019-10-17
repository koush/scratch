package com.koushikdutta.scratch.event

suspend fun AsyncEventLoop.getByName(host: String): InetAddress {
    return getAllByName(host)[0]
}

suspend fun AsyncEventLoop.connect(host: String, port: Int): AsyncNetworkSocket {
    return connect(InetSocketAddress(getByName(host), port))
}

//suspend fun AsyncEventLoop.listen(): AsyncNetworkServerSocket {
//    return listen(0, null)
//}
//
//suspend fun AsyncEventLoop.listen(port: Int): AsyncNetworkServerSocket {
//    return listen(port, null)
//}
//
//suspend fun AsyncEventLoop.listen(host: InetAddress): AsyncNetworkServerSocket {
//    return listen(0, host)
//}
