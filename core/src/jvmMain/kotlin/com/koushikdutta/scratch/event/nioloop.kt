@file:Suppress("BlockingMethodInNonBlockingContext")

package com.koushikdutta.scratch.event


import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.event.NamedThreadFactory.Companion.newSynchronousWorkers
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.nio.channels.*
import java.nio.channels.spi.SelectorProvider
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

actual typealias InetAddress = java.net.InetAddress
actual typealias Inet4Address = java.net.Inet4Address
actual typealias Inet6Address = java.net.Inet6Address
actual typealias InetSocketAddress = java.net.InetSocketAddress

internal actual fun milliTime(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
internal actual fun nanoTime(): Long = System.nanoTime()

internal fun closeQuietly(vararg closeables: Closeable?) {
    for (closeable in closeables) {
        try {
            closeable?.close()
        } catch (e: Exception) {
            // http://stackoverflow.com/a/156525/9636

            // also, catch all exceptions because some implementations throw random crap
            // like ArrayStoreException
        }

    }
}

actual typealias AsyncEventLoop = NIOEventLoop

open class NIOEventLoop: AsyncScheduler<AsyncEventLoop>() {
    internal lateinit var selector: SelectorWrapper
    private val initialized = AtomicBoolean(false)

    var affinity: Thread? = null
        internal set

    override val isAffinityThread: Boolean
        get() = affinity === Thread.currentThread()

    fun stop() {
        stop(false)
    }

    fun stop(wait: Boolean) {
        //        Log.i(LOGTAG, "****AsyncServer is shutting down.****");
        var semaphore: Semaphore? = null
        var isAffinityThread = false
        synchronized(this) {
            isAffinityThread = this.isAffinityThread
            semaphore = Semaphore(0)

            // post a shutdown and wait
            scheduleShutdown {
                shutdownKeys()
                semaphore!!.release()
                throw NIOLoopShutdownException()
            }
            selector.wakeupOnce()

            // force any existing connections to die
            shutdownKeys()
        }
        try {
            if (!isAffinityThread && wait)
                semaphore!!.acquire()
        } catch (e: Exception) {
        }
    }

    suspend fun listen(port: Int = 0, address: InetAddress? = null, backlog: Int = 5): AsyncNetworkServerSocket {
        await()

        var closeableServer: ServerSocketChannel? = null
        try {
            closeableServer = ServerSocketChannel.open()
            val server = closeableServer
            server.configureBlocking(false)
            val isa = if (address == null)
                InetSocketAddress(port)
            else
                InetSocketAddress(address, port)
            server!!.socket().bind(isa, backlog)

            return AsyncNetworkServerSocket(this, closeableServer!!)
        } catch (e: IOException) {
            closeQuietly(closeableServer)
            throw e
        }
    }

    suspend fun AsyncServer.listen(port: Int = 0, address: InetAddress? = null, backlog: Int = 5) =
        listen(this@NIOEventLoop.listen(port, address, backlog))

    suspend fun createDatagram(port: Int = 0, address: InetAddress? = null, reuseAddress: Boolean = false): AsyncDatagramSocket {
        await()

        var ckey: SelectionKey? = null
        var socket: DatagramChannel? = null
        try {
            socket = DatagramChannel.open()
            socket!!.configureBlocking(false)
            val inetSocketAddress: InetSocketAddress
            if (address == null)
                inetSocketAddress = InetSocketAddress(port)
            else
                inetSocketAddress = InetSocketAddress(address, port)
            if (reuseAddress)
                socket.socket().reuseAddress = true
            socket.socket().bind(inetSocketAddress)
            ckey = socket.register(selector.selector, SelectionKey.OP_READ)
            return AsyncDatagramSocket(this, socket, ckey)
        }
        catch (e: Exception) {
            ckey?.cancel()
            closeQuietly(socket)
            throw e
        }
    }

    suspend fun createSocket(): AsyncNetworkSocket {
        await()

        var ckey: SelectionKey? = null
        var socket: SocketChannel? = null
        try {
            socket = SocketChannel.open()
            socket!!.configureBlocking(false)
            ckey = socket.register(selector.selector, 0)
            return AsyncNetworkSocket(this, socket, ckey)
        }
        catch (e: Exception) {
            ckey?.cancel()
            closeQuietly(socket)
            throw e
        }
    }

    inner class AsyncFile(val file: File): AsyncSliceable {
        constructor(filename: String): this(File(filename))
        constructor(parent: String, child: String): this(File(parent, child))

        override suspend fun size() = file.length()

        override suspend fun slice(position: Long, length: Long): AsyncInput {
            val storage = openFile(file)
            val read = storage.seekRead(position, length)
            return object : AsyncInput, AsyncResource by storage {
                override suspend fun read(buffer: WritableBuffers) = read(buffer)
            }
        }
    }

    suspend fun openFile(file: File, write: Boolean = false, defaultReadLength: Int = 16384): AsyncRandomAccessStorage {
        await()
        return NIOFileFactory.instance.open(this, file, defaultReadLength, write)
    }

    suspend fun listFiles(directory: File): Array<File> {
        return NIOFileFactory.instance.listFiles(this, directory)
    }

    suspend fun getAllByName(host: String): Array<InetAddress> {
        synchronousResolverWorkers.await()
        val result = InetAddress.getAllByName(host)
        Arrays.sort(result, ipSorter)
        if (result == null || result.isEmpty())
            throw IOException("no addresses for host")
        await()
        return result
    }

    fun run() {
        synchronized(this) {
            if (affinity == null)
                affinity = Thread.currentThread()
            else
                throw IllegalStateException("AsyncEventLoop is already running.")
        }

        if (initialized.compareAndSet(false, true))
            selector = SelectorWrapper(SelectorProvider.provider().openSelector())

        // at this point, this local queue and selector are owned
        // by this thread.
        // if a stop is called, the instance queue and selector
        // will be replaced and nulled respectively.
        // this will allow the old queue and selector to shut down
        // gracefully, while also allowing a new selector thread
        // to start up while the old one is still shutting down.
        while (true) {
            try {
                runLoop()
            }
            catch (e: AsyncSelectorException) {
                // these are wakeup exceptions
            }
            catch (e: NIOLoopShutdownException) {
                break
            }

            // check to see if the selector was killed somehow?
            if (!selector.isOpen) {
                shutdownKeys()
                break
            }
        }
    }

    private fun runSelector() {
        // process whatever keys are ready
        val readyKeys = selector.selectedKeys()
        for (key in readyKeys) {
            try {
                if (key.isAcceptable) {
                    val socket = key.attachment() as AsyncNetworkServerSocket
                    socket.accepted()
                }
                else if (key.isReadable) {
                    val socket = key.attachment() as NIOChannel
                    socket.readable()
                }
                else if (key.isWritable) {
                    val socket = key.attachment() as NIOChannel
                    socket.writable()
                }
                else if (key.isConnectable) {
                    val continuation = key.attachment() as Continuation<Boolean>?
                    key.interestOps(0)
                    key.attach(null)
                    // continuation may not fire synchronously.
                    continuation?.resume(true)
                }
                else {
                    throw AssertionError("Unknown key state")
                }
            } catch (ex: CancelledKeyException) {
            }
        }

        // need to clear or the events seem to show up again
        readyKeys.clear()
    }

    private fun runLoop() {
        val wait = lockAndRunQueue()
        try {
            if (wait == QUEUE_EMPTY) {
                // wait until woken up
                selector.select()
            }
            else if (wait == QUEUE_NEXT_LOOP) {
                //
                selector.selectNow()
            }
            else {
                // nothing to select immediately but there's something pending so let's block that duration and wait.
                selector.select(wait)
            }
        } catch (e: Exception) {
            // can ignore these exceptions, they spawn from wakeups
            throw AsyncSelectorException(e)
        }

        runSelector()
    }

    private fun shutdownKeys() {
        try {
            for (key in selector.keys()) {
                closeQuietly(key.channel())
                try {
                    key.cancel()
                } catch (e: Exception) {
                }

            }
        } catch (ex: Exception) {
        }
    }

    override fun wakeup() {
        if (isAffinityThread)
            return
        synchronousWorkers.execute {
            try {
                selector.wakeupOnce()
            } catch (e: Exception) {
            }
        }
    }

    companion object {
        val default = AsyncEventLoop()

        init {
            val defaultThread = Thread {
                default.run()
            }
            defaultThread.name = "DefaultScratchLoop"
            defaultThread.start()
        }

        private val synchronousWorkers = newSynchronousWorkers("AsyncServer-worker-")

        fun parseInet4Address(address: String): Inet4Address {
            // necessary to prevent dns lookup
            require(Character.digit(address[0], 16) != -1) { "not an Inet4Address" }
            return InetAddress.getByName(address) as Inet4Address
        }
        fun parseInet6Address(address: String): Inet6Address {
            // necessary to prevent dns lookup
            require(address.contains(':')) { "not an Inet6Address" }
            return InetAddress.getByName(address) as Inet6Address
        }
        fun getInterfaceAddresses(): Array<InetAddress> {
            val ret = arrayListOf<InetAddress>()
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                for (ia in ni.interfaceAddresses) {
                    if (ia.address != null)
                        ret.add(ia.address)
                }
            }
            return ret.toTypedArray()
        }

        private val ipSorter = Comparator<InetAddress> { lhs, rhs ->
            if (lhs is Inet4Address && rhs is Inet4Address)
                return@Comparator 0
            if (lhs is Inet6Address && rhs is Inet6Address)
                return@Comparator 0
            if (lhs is Inet4Address && rhs is Inet6Address) -1 else 1
        }

        private val synchronousResolverWorkers = newSynchronousWorkers("AsyncServer-resolver-")
    }
}


private class AsyncSelectorException(e: Exception) : IOException(e)
private class NIOLoopShutdownException: Exception()

fun AsyncEventLoop.startThread(name: String = "NIOLoop"): Promise<Unit> {
    val deferred = Deferred<Unit>()
    Thread({
        try {
            run()
        }
        catch (throwable: Throwable) {
            deferred.reject(throwable)
        }
    }, name).start()

    post {
        deferred.resolve(Unit)
    }
    return deferred.promise
}