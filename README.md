# Scratch

Scratch is a I/O library written in Kotlin (multiplatform). Scratch is non-blocking and uses an event loop and coroutines for I/O.

#### Features

 * Event Loop implementations:
   * Java: NIO
   * Native: libuv
 * Implements Clients and Servers
   * TCP Sockets
   * HTTP/1 and HTTP/2
   * WebSockets

## Basic Socket Usage

Usage will look similar to the blocking POSIX socket APIs, but blocking calls (read/write/accept) suspend rather than wait on a thread. An AsyncSocket is roughly:

```kotlin
interface AsyncSocket {
   // returns false when the stream ends
   suspend fun read(buffer: WritableBuffers): Boolean
   suspend fun write(buffer: ReadableBuffers)
   suspend fun close()
}
```

To get started, first, create your main event loop:

```kotlin
val loop = AsyncEventLoop()
// this is a helper to create a thread for you.
loop.startThread()
// the loop can also be manually run on a given thread using:
// loop.run()
```

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

The above server has a problem: similar to POSIX accept loops, the example above is only handling one socket at a time. Typically every client socket would get its own thread, but Scratch can put each incoming socket in its own coroutine:

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

## HTTP Example

We can also create an echo server in HTTP.

```kotlin
val server = AsyncHttpServer {
   // parse the raw request body bytes as a string
   val body = it.parse().readString()
   StatusCode.OK(body = Utf8StringBody(body))
}
server.listen(5555)
```

And the HTTP client.

```kotlin
val client = AsyncHttpClient()
val response = client(Methods.GET("http://localhost:5555",
   body = Utf8StringBody("hello world")))
println("from server: " + response.parse().readString())
```
