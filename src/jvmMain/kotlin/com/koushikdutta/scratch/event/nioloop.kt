@file:Suppress("BlockingMethodInNonBlockingContext")

package com.koushikdutta.scratch.event


import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.external.Log
import com.sun.org.apache.xpath.internal.operations.Bool
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.nio.channels.*
import java.nio.channels.spi.SelectorProvider
import java.nio.file.OpenOption
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

actual typealias InetAddress = java.net.InetAddress
actual typealias Inet4Address = java.net.Inet4Address
actual typealias Inet6Address = java.net.Inet6Address
actual typealias InetSocketAddress = java.net.InetSocketAddress

internal actual fun milliTime(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
internal actual fun nanoTime(): Long = System.nanoTime()

private suspend fun Executor.await() {
    suspendCoroutine<Unit> {
        this.execute {
            it.resume(Unit)
        }
    }
}

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
    internal val mSelector: SelectorWrapper = SelectorWrapper(SelectorProvider.provider().openSelector())

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
            mSelector.wakeupOnce()

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
            ckey = socket.register(mSelector.selector, SelectionKey.OP_READ)
            return AsyncDatagramSocket(this, socket, ckey)
        }
        catch (e: Exception) {
            ckey?.cancel()
            closeQuietly(socket)
            throw e
        }
    }

    suspend fun connect(socketAddress: InetSocketAddress): AsyncNetworkSocket {
        await()

        var ckey: SelectionKey? = null
        var socket: SocketChannel? = null
        try {
            socket = SocketChannel.open()
            socket!!.configureBlocking(false)

            ckey = socket.register(mSelector.selector, SelectionKey.OP_CONNECT)
            suspendCoroutine<Unit> {
                ckey.attach(it)
                socket.connect(socketAddress)
            }

            // for some reason this seems necessary? (see testSocketsALot)
            // must post, or it seems to just... hang? no more incoming connections.
            // attempting to log stuff to diagnose issue in itself solves the problem.
            // ie, slowing down how quickly the sockets are connected addresses some underlying race condition.
            // but, given that the test itself is entirely single threaded and non-concurrent,
            // it leads me to believe the underlying problem is in the SUN NIO implementation.
//            post()

            if (!socket.finishConnect())
                throw IOException("socket failed to connect")


            return AsyncNetworkSocket(this, socket, ckey)
        }
        catch (e: Exception) {
            ckey?.cancel()
            closeQuietly(socket)
            throw e
        }
    }

    suspend fun openFile(file: File, vararg openOptions: OpenOption, defaultReadLength: Int = 16384): AsyncRandomAccessStorage {
        await()
        return NIOFileFactory.instance.open(this, file, defaultReadLength, *openOptions)
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
                throw IllegalStateException("AsyncNetworkContext is already running.")
        }

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
            if (!mSelector.isOpen) {
                shutdownKeys()
                break
            }
        }
    }

    private fun runLoop() {
//                Log.i(LOGTAG, "Keys: " + mSelector.keys().size)
        var needsSelect = true

        // run the queue to populate the selector with keys
        val wait = lockAndRunQueue()
        try {
            val exit = synchronized(this) {
                // select now to see if anything is ready immediately. this
                // also clears the canceled key queue.
                val readyNow = mSelector.selectNow()
                if (readyNow == 0) {
                    // if there is nothing to select now, make sure we don't have an empty key set
                    // which means it would be time to turn this thread off.
                    if (mSelector.keys().isEmpty() && wait == QUEUE_EMPTY) {
                        //                    Log.i(LOGTAG, "Shutting down. keys: " + selector.keys().size() + " keepRunning: " + keepRunning);
                        return@synchronized true
                    }
                }
                else {
                    needsSelect = false
                }
                false
            }
            if (exit)
                return

            if (needsSelect) {
                if (wait == QUEUE_EMPTY) {
                    // wait until woken up
                    mSelector.select()
                }
                else {
                    // nothing to select immediately but there's something pending so let's block that duration and wait.
                    mSelector.select(wait)
                }
            }
        } catch (e: Exception) {
            // can ignore these exceptions, they spawn from wakeups
            throw AsyncSelectorException(e)
        }

        // process whatever keys are ready
        val readyKeys = mSelector.selector.selectedKeys()
        for (key in readyKeys) {
            try {
                if (key.isAcceptable) {
                    val socket = key.attachment() as AsyncNetworkServerSocket
                    socket.accepted()
                }
                else if (key.isReadable) {
                    val socket = key.attachment() as NIOChannel
                    val transmitted = socket.readable()
                }
                else if (key.isWritable) {
                    val socket = key.attachment() as NIOChannel
                    socket.writable()
                }
                else if (key.isConnectable) {
                    val continuation = key.attachment() as Continuation<Unit>
                    key.interestOps(SelectionKey.OP_READ)
                    continuation.resume(Unit)
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

    private fun shutdownKeys() {
        try {
            for (key in mSelector.keys()) {
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
                mSelector.wakeupOnce()
            } catch (e: Exception) {
                Log.i(LOGTAG, "Selector Exception? L Preview?")
            }
        }
    }

    companion object {
        val LOGTAG = "NIO"

        val default = AsyncEventLoop()

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

        private fun newSynchronousWorkers(prefix: String): ExecutorService {
            val tf = NamedThreadFactory(prefix)
            return ThreadPoolExecutor(1, 4, 10L,
                    TimeUnit.SECONDS, LinkedBlockingQueue(), tf)
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

private class NamedThreadFactory internal constructor(private val namePrefix: String) : ThreadFactory {
    private val group: ThreadGroup
    private val threadNumber = AtomicInteger(1)

    init {
        val s = System.getSecurityManager()
        group = if (s != null)
            s.threadGroup
        else
            Thread.currentThread().threadGroup
    }

    override fun newThread(r: Runnable): Thread {
        val t = Thread(group, r,
            namePrefix + threadNumber.getAndIncrement(), 0)
        if (t.isDaemon) t.isDaemon = false
        if (t.priority != Thread.NORM_PRIORITY) {
            t.priority = Thread.NORM_PRIORITY
        }
        return t
    }
}
