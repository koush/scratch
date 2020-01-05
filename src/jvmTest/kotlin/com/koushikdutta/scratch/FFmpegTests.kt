package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.atomic.AtomicBoolean
import com.koushikdutta.scratch.buffers.ByteBufferList
import com.koushikdutta.scratch.buffers.WritableBuffers
import com.koushikdutta.scratch.collections.getFirst
import com.koushikdutta.scratch.collections.parseUrlEncoded
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.NamedThreadFactory
import com.koushikdutta.scratch.event.await
import com.koushikdutta.scratch.http.AsyncHttpResponse
import com.koushikdutta.scratch.http.OK
import com.koushikdutta.scratch.http.body.Utf8StringBody
import com.koushikdutta.scratch.http.client.AsyncHttpClient
import com.koushikdutta.scratch.http.server.AsyncHttpRouter
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.http.server.get
import com.koushikdutta.scratch.http.server.randomAccessInput
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.stream.createAsyncRead
import com.koushikdutta.scratch.uri.URI
import org.junit.Test
import java.io.File
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


class PauseRandomAccess(val input: AsyncRandomAccessInput) : AsyncRandomAccessInput by input {
    val paused = AtomicBoolean(false)
    val pause = Yielder()

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        pause.resume()
    }

    override suspend fun read(buffer: WritableBuffers): Boolean {
        if (paused.getAndSet(false)) {
            println("pause")
            pause.yield()
        }

        return input.read(buffer)
    }

    override suspend fun readPosition(position: Long, length: Long, buffer: WritableBuffers): Boolean {
        if (paused.getAndSet(false)) {
            println("pause")
            pause.yield()
        }

        return input.readPosition(position, length, buffer)
    }
}

class FFmpegSegmenterSession(val startSegment: Int, val segmentDuration: Int, val sessionId: String) {
    var running = false
    var currentSegment = startSegment
    val pauseRandomAccess = Deferred<PauseRandomAccess>()
    var randomAccess: PauseRandomAccess? = null
    var unusedCount = 0
}

class FFmpegStreamer private constructor(val input: String, val duration: Long, val segmentLengthSeconds: Int = 8, val outputPath: File) {
    companion object {
        val executor = NamedThreadFactory.newSynchronousWorkers("ffmpeg")

        fun openFFmpegStream(input: String, segmentLength: Int, outputPath: File) = Promise {
            executor.await()
            val process = Runtime.getRuntime().exec(arrayOf("ffmpeg", "-i", input))
            val read = process.errorStream.createAsyncRead()
            val stderr = readAllString(read)
            val durationSearch = Regex("Duration: (.*?):(.*?):(.*?)\\.(.*?),")
            val match = durationSearch.find(stderr)
            if (match == null)
                throw IOException("invalid media, duration not found")

            val duration = TimeUnit.HOURS.toMillis(match.groupValues[1].toLong()) +
                TimeUnit.MINUTES.toMillis(match.groupValues[2].toLong()) +
                TimeUnit.SECONDS.toMillis(match.groupValues[3].toLong()) +
                match.groupValues[4].toDouble().times(1000).toLong()
            val ret = FFmpegStreamer(input, duration, segmentLength, outputPath)
            ret
        }
    }

    val loop = AsyncEventLoop()
    val router = AsyncHttpRouter()
    val server = AsyncHttpServer(router::handle)
    val client = AsyncHttpClient(loop)
    var port = 0
    var proxiedInput: String? = null
    init {
        val uri = URI.create(input)
        val filename = File(uri.path).name
        router.get("/stop") { _, _ ->
            loop.stop()
            AsyncHttpResponse.OK()
        }

        router.get("/output.m3u8") { _, _ ->
            val builder = StringBuilder()
            builder.append("#EXTM3U\n")
            builder.append("#EXT-X-VERSION:3\n")
//                builder.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
            //                builder.append("#EXT-X-PLAYLIST-TYPE:VOD\n");
            builder.append("#EXT-X-TARGETDURATION:" + (segmentLengthSeconds + 1).toString() + "\n")
            builder.append("#EXT-X-MEDIA-SEQUENCE:0\n")
            builder.append("#EXT-X-ALLOW-CACHE:YES\n")

            var durationSeconds = (duration / 1000L).toInt()
            var segment = 0
            while (durationSeconds > 0) {
                val thisDuration = Math.min(segmentLengthSeconds, durationSeconds)
//                if (segment != 0)
//                    builder.append("#EXT-X-DISCONTINUITY\n");
                builder.append("#EXTINF:$thisDuration,\n")
                val padded = segment.toString().padStart(4, '0')
                builder.append("output$padded.ts\n")
                durationSeconds -= segmentLengthSeconds
                segment++
            }
            builder.append("#EXT-X-ENDLIST\n");

            AsyncHttpResponse.OK(body = Utf8StringBody(builder.toString()))
        }

        router.randomAccessInput("/$filename") { request, _ ->
//            client.randomAccess(input)
            val ret = PauseRandomAccess(loop.openFile(File(input)))

            val query = parseUrlEncoded(request.uri.query)
            val sessionId = query.getFirst("sessionId")
            if (sessionId != null) {
                sessions[sessionId]?.pauseRandomAccess?.resolve(ret)
                sessions[sessionId]?.randomAccess = ret
            }

            ret
        }

        router.randomAccessInput("/output(\\d\\d\\d\\d).ts") { request, match ->
            val currentSegment = match.groupValues[1].toInt()
            println("requested $currentSegment")

            val file = getSegment(currentSegment).await()

            println("serving $currentSegment")
            loop.openFile(file)
        }

        loop.async {
            val socket = listen(5000)
            port = socket.localPort
            proxiedInput = "http://localhost:$port/$filename"
            println(proxiedInput)
            server.listen(socket)
        }
    }

    private val outputCompletionThreshold = 3
    private val outputNearbyThreshold = 20

    val segmentCount: Int
        get() = (duration / (segmentLengthSeconds.toLong() * 1000L)).toInt() + 1

    private fun FFmpegSegmenterSession.resolve(found: Int) {
        val padded = found.toString().padStart(4, '0')
        val file = File(outputPath, "output$padded.ts")
        if (jobs[found] == null) {
            if (unusedCount++ >= outputCompletionThreshold)
                this.randomAccess?.pause()
            val deferred = Deferred<File>()
            jobs[found] = deferred
            deferred.resolve(file)
        }
        else {
            jobs[found]?.resolve(file)
        }
    }

    suspend fun startSegmenter(segmenter: FFmpegSegmenterSession) {
        try {
            executor.await()
            outputPath.mkdirs()
            val process = Runtime.getRuntime().exec(arrayOf("ffmpeg",
                    "-y",
                    "-ss", segmenter.segmentDuration.toString(),
                    "-i", "$proxiedInput?sessionId=${segmenter.sessionId}",
                    "-f", "segment", "-break_non_keyframes", "1", "-segment_time", "8", "-segment_start_number", segmenter.currentSegment.toString(),
                    "-vcodec", "copy",
                    "-acodec", "aac", "-ac", "2",
                    "${outputPath.absolutePath}/output%04d.ts"
            ))
            segmenter.pauseRandomAccess.promise.await()
            val reader = AsyncReader(process.errorStream.createAsyncRead())

            val regex = Regex("output(\\d\\d\\d\\d)\\.ts")
            val buffer = ByteBufferList()
            val pending = arrayListOf<Int>()
            while (reader.readScanUtf8String(buffer, "\n")) {
                val line = buffer.readUtf8String()
//            println(line)

                if (!line.contains("Opening"))
                    continue

                val match = regex.find(line)
                if (match == null)
                    continue

                while (pending.size > outputCompletionThreshold) {
                    val found = pending.removeAt(0)
                    println("resolved $found")
                    segmenter.resolve(found)
                }

                val currentSegment = match.groupValues[1].toInt()

                segmenter.currentSegment = currentSegment
                pending.add(currentSegment)
            }

            for (found in pending) {
                segmenter.resolve(found)
            }
            pending.clear()
        }
        catch (throwable: Throwable) {
            // fail/cancel segments?
            throw throwable
        }
        finally {
            segmenter.running = false
        }
    }

    val sessions = mutableMapOf<String, FFmpegSegmenterSession>()
    val jobs = mutableMapOf<Int, Deferred<File>>()

    fun getSegment(segment: Int): Promise<File> {
        if (segment < 0 || segment > segmentCount)
            throw IllegalArgumentException("invalid segment number")

        val found = jobs.get(segment)
        if (found != null)
            return found.promise

        jobs[segment] = Deferred()

        for (session in sessions.values) {
            val nearish = session.currentSegment - segment
            if (
                // about to complete
                nearish in 0 until outputNearbyThreshold && session.running ||
                // in progress
                (session.startSegment <= segment && segment <= session.currentSegment)) {

                session.unusedCount = nearish
                println("resuming")
                session.randomAccess?.resume()
                println("waiting for existing ${session.sessionId}")
                return jobs[segment]!!.promise
            }
            else {
                println("bad candidate ${session.currentSegment}")
            }
        }

        val sessionId = UUID.randomUUID().toString()
        val session = FFmpegSegmenterSession(segment, segment * segmentLengthSeconds, sessionId)
        sessions[sessionId] = session
        println("spinning up $sessionId for $segment")

        Promise {
            startSegmenter(session)
        }

        return jobs[segment]!!.promise
    }

    fun stop() {
        loop.stop()
    }
}

class FFmpegTests {
    @Test
    fun testFFmpeg() {
        val semaphore = Semaphore(0)
        val promise = FFmpegStreamer.openFFmpegStream("/tmp/Sintel.2010.720p.mkv", 8, File("/tmp/test"))
        .finally {
            semaphore.release()
        }
        semaphore.acquire()

        promise.getOrThrow().loop.run()
        println("done with loop")
    }
}