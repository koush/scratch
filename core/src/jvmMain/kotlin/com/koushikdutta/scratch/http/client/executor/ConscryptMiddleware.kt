package com.koushikdutta.scratch.http.client.executor


//// todo: move this into conscrypt specific library
//open class ConscryptMiddleware(eventLoop: AsyncEventLoop, context: SSLContext = getConscryptSSLContext()) : AsyncTlsSocketMiddleware(eventLoop, context) {
//    private val protocols = arrayOf("h2", "http/1.1")
//
//    fun install(client: AsyncHttpClient) {
//        client.middlewares.add(0, this)
//    }
//
//    override fun configureEngine(engine: SSLEngine) {
//        super.configureEngine(engine)
//
//        Conscrypt.setApplicationProtocols(engine, protocols)
//    }
//
//    override suspend fun wrapSocket(session: AsyncHttpClientSession, socket: AsyncSocket, host: String, port: Int): AsyncSocket {
//        throw AssertionError("wrapSocket in ConscryptMiddleware should be unreachable")
//    }
//
//    override suspend fun createTransport(session: AsyncHttpClientSession, host: String, port: Int): AsyncHttpClientTransport {
//        val socket = connectInternal(session, host, port)
//        val tlsSocket = wrapForTlsSocket(session, socket, host, port)
//        if ("h2" == Conscrypt.getApplicationProtocol(tlsSocket.engine))
//            return AsyncHttpClientTransport(manageHttp2Connection(session, host, port, tlsSocket), protocol = Protocol.HTTP_2.toString())
//        return AsyncHttpClientTransport(tlsSocket)
//    }
//
//    companion object {
//        fun getConscryptSSLContext(): SSLContext {
//            val provider = Conscrypt.newProvider()
//            val context = SSLContext.getInstance("TLS", provider)
//            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm(), provider)
//            tmf.init(null as KeyStore?)
//            context.init(null, tmf.trustManagers, SecureRandom())
//            return context
//        }
//    }
//}