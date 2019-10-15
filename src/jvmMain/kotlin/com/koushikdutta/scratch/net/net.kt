package com.koushikdutta.scratch.net


import com.koushikdutta.scratch.*
import com.koushikdutta.scratch.buffers.*
import com.koushikdutta.scratch.external.Log
import java.io.Closeable
import java.io.IOException
import java.net.*
import java.nio.channels.*
import java.nio.channels.spi.SelectorProvider
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.*

private suspend fun Executor.await() {
    suspendCoroutine<Unit> {
        this.execute {
            it.resume(Unit)
        }
    }
}

typealias AsyncServerRunnable = () -> Unit

private fun closeQuietly(vararg closeables: Closeable?) {
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

private fun milliTime(): Long {
    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
}

class AsyncNetworkServerSocket internal constructor(val server: AsyncNetworkContext, val localPort: Int, private val channel: ServerSocketChannel) : AsyncServerSocket {
    private val key: SelectionKey = channel.register(server.mSelector!!.selector, SelectionKey.OP_ACCEPT)

    init {
        key.attach(this)
    }

    override suspend fun await() {
        server.await()
    }

    private var closed = false
    override suspend fun close() {
        closeQuietly(channel)
        try {
            key.cancel()
        }
        catch (e: Exception) {
        }
        if (!closed) {
            closed = true
            queue.end()
        }
    }

    val backlog: Int = 65535
    private var isAccepting = true
    private val queue = object : AsyncDequeueIterator<AsyncNetworkSocket>() {
        override fun popped(value: AsyncNetworkSocket) {
            if (!isAccepting && size < backlog) {
                isAccepting = true
                key.interestOps(SelectionKey.OP_ACCEPT)
            }
        }

        override fun add(value: AsyncNetworkSocket) {
            if (isAccepting && size >= backlog) {
                isAccepting = false
                key.interestOps(0)
            }

            super.add(value)
        }
    }

    override fun accept(): AsyncIterable<AsyncNetworkSocket> {
        return queue
    }

    internal fun accepted(server: AsyncNetworkContext, selector: SelectorWrapper, channel: SocketChannel) {
        queue.add(AsyncNetworkSocket(server, channel, channel.register(selector.selector, SelectionKey.OP_READ)))
    }
}

class AsyncNetworkSocket internal constructor(val server: AsyncNetworkContext, private val channel: SocketChannel, private val key: SelectionKey) : AsyncSocket {
    val localPort = channel.socket().localPort
    val remoteAddress: SocketAddress? = channel.socket().remoteSocketAddress
    private val inputBuffer = ByteBufferList()
    private var closed = false
    private val allocator = Allocator()
    private val input = object : NonBlockingWritePipe() {
        override fun writable() {
            key.interestOps(SelectionKey.OP_READ or key.interestOps())
        }
    }
    private val output = object : BlockingWritePipe() {
        override fun write(buffer: Buffers) {
            val buffers = buffer.readAll()
            channel.write(buffers)
            buffer.addAll(*buffers)
            if (buffer.hasRemaining())
                key.interestOps(SelectionKey.OP_WRITE or key.interestOps())
        }
    }

    init {
        key.attach(this)
    }

    override suspend fun await() {
        server.await()
    }

    fun closeInternal() {
        closeQuietly(channel)
        try {
            key.cancel()
        }
        catch (e: Exception) {
        }

        if (!closed) {
            closed = true
            input.end()
        }
    }

    override suspend fun close() {
        closeInternal()
    }

    internal fun readable(): Int {
        var total = 0
        val b = allocator.allocate()
        // keep track of the max mount read during this read cycle
        // so we can be quicker about allocations during the next
        // time this socket reads.
        var read: Long
        try {
            read = channel.read(b).toLong()

            // clean close
            if (read < 0) {
                closed = true
                input.end()
            }
        } catch (e: Exception) {
            // transport failure caused close
            closed = true
            read = -1
            input.end(e)
        }

        if (closed) {
            closeInternal()
        }
        else {
            total += read.toInt()
        }

        if (read > 0) {
            allocator.track(read)
            b.flip()
            inputBuffer.add(b)
            if (!input.write(inputBuffer))
                key.interestOps(SelectionKey.OP_READ.inv() and key.interestOps())
        }
        else {
            ByteBufferList.reclaim(b)
        }
        return total
    }

    internal fun writable() {
        key.interestOps(SelectionKey.OP_WRITE.inv() and key.interestOps())
        output.writable()
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        return input.read(buffer)
    }

    override suspend fun write(buffer: ReadableBuffers) {
        output.write(buffer)
    }
}


class AsyncNetworkContext constructor(name: String? = null) {
    internal var mSelector: SelectorWrapper? = null

    val isRunning: Boolean
        get() = mSelector != null

    private val mName: String

    private var killed: Boolean = false

    private var postCounter = 0
    private var mQueue = PriorityQueue(1, Scheduler.INSTANCE)

    var affinity: Thread? = null
        internal set

    val isAffinityThread: Boolean
        get() = affinity === Thread.currentThread()

    val isAffinityThreadOrStopped: Boolean
        get() {
            val affinity = this.affinity
            return affinity == null || affinity === Thread.currentThread()
        }

    init {
        if (name == null)
            mName = "AsyncServer"
        else
            mName = name
    }

    fun kill() {
        synchronized(this) {
            killed = true
        }
        stop(false)
    }

    fun postDelayed(delay: Long, runnable: AsyncServerRunnable): Cancellable {
        return synchronized(this) {
            if (killed)
                return@synchronized SimpleCancellable.CANCELLED

            // Calculate when to run this queue item:
            // If there is a delay (non-zero), add it to the current time
            // When delay is zero, ensure that this follows all other
            // zero-delay queue items. This is done by setting the
            // "time" to the queue size. This will make sure it is before
            // all time-delayed queue items (for all real world scenarios)
            // as it will always be less than the current time and also remain
            // behind all other immediately run queue items.
            val time: Long
            if (delay > 0)
                time = milliTime() + delay
            else if (delay == 0L)
                time = postCounter++.toLong()
            else if (mQueue.size > 0)
                time = Math.min(0, mQueue.peek().time - 1)
            else
                time = 0
            val s = Scheduled(this, runnable, time)
            mQueue.add(s)
            // start the server up if necessary
            if (mSelector == null)
                run()
            if (!isAffinityThread) {
                wakeup(mSelector)
            }
            s
        }
    }

    fun postImmediate(runnable: AsyncServerRunnable): Cancellable? {
        if (Thread.currentThread() === affinity) {
            runnable()
            return null
        }
        return postDelayed(-1, runnable)
    }

    fun post(runnable: AsyncServerRunnable): Cancellable {
        return postDelayed(0, runnable)
    }

    private class Scheduled(var server: AsyncNetworkContext, var runnable: AsyncServerRunnable, var time: Long) : Cancellable, Runnable {

        internal var cancelled: Boolean = false

        override fun run() {
            this.runnable()
        }

        override val isDone: Boolean
            get() {
                return synchronized(server) {
                    !cancelled && !server.mQueue.contains(this)
                }
            }

        override val isCancelled: Boolean
            get() {
                return cancelled
            }

        override fun cancel(): Boolean {
            return synchronized(server) {
                cancelled = server.mQueue.remove(this)
                cancelled
            }
        }
    }

    private class Scheduler private constructor() : Comparator<Scheduled> {
        override fun compare(s1: Scheduled, s2: Scheduled): Int {
            // keep the smaller ones at the head, so they get tossed out quicker
            if (s1.time == s2.time)
                return 0
            return if (s1.time > s2.time) 1 else -1
        }

        companion object {
            var INSTANCE = Scheduler()
        }
    }

    @JvmOverloads
    fun stop(wait: Boolean = true) {
        //        Log.i(LOGTAG, "****AsyncServer is shutting down.****");
        var currentSelector: SelectorWrapper?
        var semaphore: Semaphore? = null
        var isAffinityThread: Boolean = false
        synchronized(this) {
            isAffinityThread = this.isAffinityThread
            currentSelector = mSelector
            if (currentSelector == null)
                return@synchronized
            semaphore = Semaphore(0)

            // post a shutdown and wait
            mQueue.add(Scheduled(this, {
                shutdownEverything(currentSelector)
                semaphore!!.release()
            }, 0))
            currentSelector!!.wakeupOnce()

            // force any existing connections to die
            shutdownKeys(currentSelector)

            mQueue = PriorityQueue(1, Scheduler.INSTANCE)
            mSelector = null
            affinity = null
        }
        try {
            if (!isAffinityThread && wait)
                semaphore!!.acquire()
        } catch (e: Exception) {
        }

    }

    protected var dataReceived = 0
    protected fun onDataReceived(transmitted: Int) {
        dataReceived += transmitted
    }

    protected var dataSent = 0
    protected fun onDataSent(transmitted: Int) {
        dataSent += transmitted
    }

    suspend fun listen(): AsyncNetworkServerSocket {
        return listen(null, 0)
    }

    suspend fun listen(port: Int): AsyncNetworkServerSocket {
        return listen(null, port)
    }

    suspend fun listen(host: InetAddress): AsyncNetworkServerSocket {
        return listen(host, 0)
    }

    suspend fun listen(host: InetAddress?, port: Int): AsyncNetworkServerSocket {
        await()
        var closeableServer: ServerSocketChannel? = null
        try {
            closeableServer = ServerSocketChannel.open()
            val server = closeableServer
            server.configureBlocking(false)
            val isa = if (host == null)
                InetSocketAddress(port)
            else
                InetSocketAddress(host, port)
            server!!.socket().bind(isa)

            val ret = AsyncNetworkServerSocket(this, server.socket().localPort, closeableServer!!)
            return ret
        } catch (e: IOException) {
            closeQuietly(closeableServer)
            throw e
        }
    }

    suspend fun connect(host: String, port: Int): AsyncNetworkSocket {
        return connect(InetSocketAddress(getByName(host), port))
    }

    suspend fun connect(address: InetSocketAddress): AsyncNetworkSocket {
        var ckey: SelectionKey? = null
        var socket: SocketChannel? = null
        try {
            socket = SocketChannel.open()
            socket!!.configureBlocking(false)

            ckey = socket.register(mSelector!!.selector, SelectionKey.OP_CONNECT)
            suspendCoroutine<Unit> {
                ckey.attach(it)
                socket.connect(address)
            }

            if (!socket.finishConnect())
                throw IOException("socket failed to connect")

            return AsyncNetworkSocket(this, socket, ckey)
        }
        catch (e: Throwable) {
            ckey?.cancel()
            closeQuietly(socket)
            throw e
        }
    }


    fun <T> async(block: suspend AsyncNetworkContext.() -> T): AsyncResultHolder<T> {
        val ret = AsyncResultHolder<T>()
        postImmediate {
            block.startCoroutine(this, Continuation(EmptyCoroutineContext) { result ->
                ret.setComplete(result)
            })
        }
        return ret
    }

    suspend fun sleep(milliseconds: Long) {
        suspendCoroutine<Unit> {
            postDelayed(milliseconds) {
                it.resume(Unit)
            }
        }
    }

    suspend fun post() {
        suspendCoroutine<Unit> {
            post {
                it.resume(Unit)
            }
        }
    }

    suspend fun await() {
        if (isAffinityThread)
            return
        post()
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

    suspend fun getByName(host: String): InetAddress {
        return getAllByName(host)[0]
    }

    private fun run() {
        var selector: SelectorWrapper? = null
        var queue: PriorityQueue<Scheduled>? = null
        val exit = synchronized(this) {
            if (mSelector == null) {
                try {
                    mSelector = SelectorWrapper(SelectorProvider.provider().openSelector())
                    selector = mSelector!!
                    queue = mQueue
                } catch (e: IOException) {
                    throw RuntimeException("unable to create selector?", e)
                }

                affinity = object : Thread(mName) {
                    override fun run() {
                        try {
                            threadServer.set(this@AsyncNetworkContext)
                            run(this@AsyncNetworkContext, selector!!, queue!!)
                        } finally {
                            threadServer.remove()
                        }
                    }
                }

                affinity!!.start()
                // kicked off the new thread, let's bail.
                return@synchronized true
            }

            // this is a reentrant call
            selector = mSelector!!
            queue = mQueue

            // fall through to outside of the synchronization scope
            // to allow the thread to run without locking.
            false
        }

        if (exit)
            return

        try {
            runLoop(selector!!, queue!!)
        } catch (e: AsyncSelectorException) {
            Log.i(LOGTAG, "Selector closed", e)
            try {
                // StreamUtility.closeQuiety is throwing ArrayStoreException?
                selector!!.selector.close()
            }
            catch (ex: Exception) {
            }
        }

    }

    private class AsyncSelectorException(e: Exception) : IOException(e)

    fun dump() {
        post {
            if (mSelector == null) {
                Log.i(LOGTAG, "Server dump not possible. No selector?")
                return@post
            }
            Log.i(LOGTAG, "Key Count: " + mSelector!!.keys().size)

            for (key in mSelector!!.keys()) {
                Log.i(LOGTAG, "Key: $key")
            }
        }
    }

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

    private fun runLoop(selector: SelectorWrapper, queue: PriorityQueue<Scheduled>) {
        //        Log.i(LOGTAG, "Keys: " + selector.keys().size());
        var needsSelect = true

        // run the queue to populate the selector with keys
        val wait = lockAndRunQueue(this, queue)
        try {
            val exit = synchronized(this) {
                // select now to see if anything is ready immediately. this
                // also clears the canceled key queue.
                val readyNow = selector.selectNow()
                if (readyNow == 0) {
                    // if there is nothing to select now, make sure we don't have an empty key set
                    // which means it would be time to turn this thread off.
                    if (selector.keys().isEmpty() && wait == QUEUE_EMPTY) {
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
                    selector.select()
                }
                else {
                    // nothing to select immediately but there's something pending so let's block that duration and wait.
                    selector.select(wait)
                }
            }
        } catch (e: Exception) {
            throw AsyncSelectorException(e)
        }

        // process whatever keys are ready
        val readyKeys = selector.selector.selectedKeys()
        for (key in readyKeys) {
            try {
                if (key.isAcceptable) {
                    val channel = key.channel() as ServerSocketChannel
                    val socket = key.attachment() as AsyncNetworkServerSocket

                    var sc: SocketChannel? = null
                    try {
                        sc = channel.accept()
                        if (sc == null)
                            continue
                        sc.configureBlocking(false)
                        socket.accepted(this, selector, sc)
                    } catch (e: IOException) {
                        closeQuietly(sc)
                    }
                }
                else if (key.isReadable) {
                    val socket = key.attachment() as AsyncNetworkSocket
                    val transmitted = socket.readable()
                    onDataReceived(transmitted)
                }
                else if (key.isWritable) {
                    val socket = key.attachment() as AsyncNetworkSocket
                    socket.writable()
                }
                else if (key.isConnectable) {
                    val continuation = key.attachment() as Continuation<Unit>
                    key.interestOps(SelectionKey.OP_READ)
                    continuation.resume(Unit)
                }
                else {
                    Log.i(LOGTAG, "wtf")
                    throw RuntimeException("Unknown key state.")
                }
            } catch (ex: CancelledKeyException) {
            }
        }

        // need to clear or the events seem to show up again
        readyKeys.clear()
    }


    companion object {
        val LOGTAG = "NIO"

        var default = AsyncNetworkContext()
            internal set

        private val synchronousWorkers = newSynchronousWorkers("AsyncServer-worker-")
        private fun wakeup(selector: SelectorWrapper?) {
            synchronousWorkers.execute {
                try {
                    selector!!.wakeupOnce()
                } catch (e: Exception) {
                    Log.i(LOGTAG, "Selector Exception? L Preview?")
                }
            }
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

        private val threadServer = ThreadLocal<AsyncNetworkContext>()

        val currentThreadServer: AsyncNetworkContext?
            get() = threadServer.get()

        private fun run(server: AsyncNetworkContext, selector: SelectorWrapper, queue: PriorityQueue<Scheduled>) {
            // at this point, this local queue and selector are owned
            // by this thread.
            // if a stop is called, the instance queue and selector
            // will be replaced and nulled respectively.
            // this will allow the old queue and selector to shut down
            // gracefully, while also allowing a new selector thread
            // to start up while the old one is still shutting down.
            while (true) {
                try {
                    server.runLoop(selector, queue)
                } catch (e: AsyncSelectorException) {
                    if (e.cause !is ClosedSelectorException)
                        Log.i(LOGTAG, "Selector exception, shutting down", e)
                    closeQuietly(selector)
                }

                // see if we keep looping, this must be in a synchronized block since the queue is accessed.
                val exit = synchronized(server) {
                    if (!selector.isOpen || (selector.keys().size  == 0 && queue.size == 0)) {
                        shutdownEverything(selector)
                        if (server.mSelector === selector) {
                            server.mQueue = PriorityQueue(1, Scheduler.INSTANCE)
                            server.mSelector = null
                            server.affinity = null
                        }

                        return@synchronized true
                    }
                    false
                }

                if (exit)
                    return
            }
        }

        private fun shutdownKeys(selector: SelectorWrapper?) {
            try {
                for (key in selector!!.keys()) {
                    closeQuietly(key.channel())
                    try {
                        key.cancel()
                    } catch (e: Exception) {
                    }

                }
            } catch (ex: Exception) {
            }

        }

        private fun shutdownEverything(selector: SelectorWrapper?) {
            shutdownKeys(selector)
            // SHUT. DOWN. EVERYTHING.
            closeQuietly(selector)
        }

        private val QUEUE_EMPTY = java.lang.Long.MAX_VALUE
        private fun lockAndRunQueue(server: AsyncNetworkContext, queue: PriorityQueue<Scheduled>): Long {
            var wait = QUEUE_EMPTY

            // find the first item we can actually run
            while (true) {
                var run: Scheduled? = null

                synchronized(server) {
                    val now = milliTime()

                    if (queue.size > 0) {
                        val s = queue.remove()
                        if (s.time <= now) {
                            run = s
                        }
                        else {
                            wait = s.time - now
                            queue.add(s)
                        }
                    }
                }

                if (run == null)
                    break

                run!!.run()
            }

            server.postCounter = 0
            return wait
        }
    }
}
