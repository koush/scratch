import com.koushikdutta.scratch.AsyncReader
import com.koushikdutta.scratch.http.client.AsyncHttpClientSession
import com.koushikdutta.scratch.http.client.middleware.AsyncHttpClientMiddleware
import com.koushikdutta.scratch.http.http2.Http2ExchangeCodec
import com.koushikdutta.scratch.http.http2.Http2Stream


class AsyncHttp2TransportMiddleware: AsyncHttpClientMiddleware() {
    override suspend fun exchangeMessages(session: AsyncHttpClientSession): Boolean {
        if ("h2" != session.protocol)
            return false

        session.response = Http2ExchangeCodec.createResponse(session.socket as Http2Stream)
        session.socketReader = AsyncReader(session.socket!!::read)
        return true
    }
}

