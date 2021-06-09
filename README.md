# Scratch

Scratch is a networking library written in Kotlin (multiplatform). Scratch is non-blocking and uses an event loop and coroutines for I/O.

#### Features

 * Event Loop implementations:
   * Java: NIO
   * Native: libuv
 * Implements Clients and Servers
   * TCP Sockets
   * HTTP
   * WebSocket

#### Basic Usage

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
// the loop can be manually run on a provided thread using:
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

To connect with a client:

```kotlin
loop.async {
    val client = connect("localhost", 5555)
    while (true) {
        val buffer = "hello".createByteBufferList()
        client.drain(buffer)
    }
    // this will accept sockets one at a time
    // and echo data back
    for (socket in server.accept()) { 
        val buffer = ByteBufferList()
        while (socket.read(buffer)) {
            socket.write(buffer)
        }
    }
}
```

The above server has a problem: similar to POSIX accept loops, the accept loop is only handling one socket at a time. Let's give each incoming socket it's own coroutine:

```kotlin
loop.async {
    val server = listen(5555)
    // accept sockets asynchronously and echo data back
    server.acceptAsync {
        val buffer = ByteBufferList()
        // "this" is the AsyncSocket. 
        while (read(buffer)) {
            drain(buffer)
        }
    }
}
```
