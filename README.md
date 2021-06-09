# Scratch

Scratch is a I/O library written in Kotlin (multiplatform). Scratch is non-blocking and uses an event loop and coroutines for I/O.

#### Features

 * Event Loop implementations:
   * Java: NIO
   * Native: libuv
 * Implements Clients and Servers
   * [TCP](#socket-client-and-server)/UDP Sockets
   * [HTTP/1](#http-client-and-server) and HTTP/2
   * [WebSockets](#websocket-client-and-server)
 * [TLS](#tls)
   * Java: SSLEngine
   * Native: openssl

## Socket Client and Server

Usage will look similar to the blocking POSIX socket APIs, but blocking calls (read/write/accept) suspend rather than wait on a thread. An AsyncSocket is roughly:

```kotlin
interface AsyncSocket {
   // returns false when the stream ends
   suspend fun read(buffer: WritableBuffers): Boolean
   suspend fun write(buffer: ReadableBuffers)
   suspend fun close()
}
```

### Event Loop
To get started, first, create your main event loop:

```kotlin
val loop = AsyncEventLoop()
// this is a helper to create a thread for you.
loop.startThread()
// the loop can also be manually run on a given thread using:
// loop.run()
```

### Socket Server
Then use the loop to start a coroutine and create an echo server:

```kotlin
loop.async {
    val server = listen(5555)
    // accept sockets one at a time and echo data back
    for (socket in server.accept()) { 
        val buffer = ByteBufferList()
        while (socket.read(buffer)) {
            // similar to POSIX write, the socket.write method may
            // only write part of the buffer if it is large transfer. 
            // socket.drain ensures everything is written.
            socket.drain(buffer)
        }
    }
}
```

### Socket Client
To connect with a client and send data every 1 second:

```kotlin
loop.async {
    val client = connect("localhost", 5555)
    while (true) {
        val buffer = "hello".createByteBufferList()
        client.drain(buffer)
        // the loop provides a nonblocking sleep
        sleep(1000)
    }
}
```

The above server has a problem: similar to POSIX accept loops, the example above is only handling one socket at a time. Typically every client socket would get its own thread, but Scratch can put each incoming socket in its own coroutine (all subsequent examples have removed the loop boilerplate):

```kotlin
val server = listen(5555)
// accept sockets asynchronously and echo data back
server.acceptAsync {
   val buffer = ByteBufferList()
   // "this" is the AsyncSocket. 
   while (read(buffer)) {
      drain(buffer)
   }
}
```

## Http Client and Server

### Http Server

```kotlin
// this http server will have no routing.
// requests at any path will echo.
val server = AsyncHttpServer {
   // parse the raw request body bytes as a string
   val body = it.parse().readString()
   // create and return the response.
   StatusCode.OK(body = Utf8StringBody(body))
}
server.listen(5555)
```

### Http Client

```kotlin
val client = AsyncHttpClient()
val response = client(Methods.GET("http://localhost:5555",
   body = Utf8StringBody("hello world")))
println("from server: " + response.parse().readString())
```

## WebSocket Client and Server

### WebSocket Server

```kotlin
val router = AsyncHttpRouter()
// handle websocket requests at https://localhost:5555/websocket
router.webSocket("/websocket").acceptAsync {
   for (message in messages) {
       if (message.isText)
           send(message.text)
   }
}
val server = AsyncHttpServer(router::handle)
server.listen(5555).await()
```

### WebSocket Client

```kotlin
val client = AsyncHttpClient()
val websocket = client.connectWebSocket("http://localhost:5555/websocket")
while (true) {
   websocket.send("hello")
   sleep(1000)
}
```

## TLS

Creating and using self-signed certificates is similar to the previous Socket Server and Client example:

### TLS Server

```kotlin
// Create an SSLContext
val serverContext = createTLSContext()
// helper to generate a self signed certificate
val keypairCert = createSelfSignedCertificate("TestServer")
// initialize the SSLContext with the private key and public certificate.
serverContext.init(keypairCert.first, keypairCert.second)
// socket server as seen in the socket example, but the incoming
// clients are upgraded to a TLS connection.
val server = server.listenTls(5555, context = serverContext)

server.acceptAsync {
   val buffer = ByteBufferList()
   // "this" is the AsyncTlsSocket. 
   while (read(buffer)) {
      drain(buffer)
   }
}
```

### TLS Client

The TLS client will need to use the same certificate as the server, and is assumed
provided in the sample below (ie, keypairCert).

```kotlin
val clientContext = createTLSContext()
// initialize the SSLContext with ONLY the public certificate
clientContext.init(keypairCert.second)
// connect to the TLS Server using the self-signed certificate context
val client = connectTls("localhost", 5555, context = clientContext)

while (true) {
   val buffer = "hello".createByteBufferList()
   client.drain(buffer)
   sleep(1000)
}
```
