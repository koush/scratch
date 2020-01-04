package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.NamedThreadFactory
import com.koushikdutta.scratch.event.await
import com.koushikdutta.scratch.http.server.AsyncHttpRouter
import com.koushikdutta.scratch.http.server.AsyncHttpServer
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.stream.createAsyncRead
import java.io.File
import java.lang.IllegalStateException
import java.util.concurrent.TimeUnit


class FFmpegStreamer private constructor(val input: String, val duration: Long, val segmentLengthSeconds: Int = 8) {
    companion object {
        val executor = NamedThreadFactory.newSynchronousWorkers("ffmpeg")

        suspend fun openFFmpegStream(input: String, segmentLength: Int): FFmpegStreamer {
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
            val ret = FFmpegStreamer(input, duration)
            ret.loop.await()
            return ret
        }
    }

    val loop = AsyncEventLoop()
    val router = AsyncHttpRouter()
    val server = AsyncHttpServer(router::handle)
    var port = 0
    init {
        Thread({
            try {
                loop.run()
            }
            catch (throwable: Throwable) {
            }
        }, "ffmpeg-loop")
        .start()

        loop.async {
            val socket = listen(0)
            port = socket.localPort
            server.listen(socket)
        }

//        router.add()
    }

    val segmentCount: Int
        get() = (duration / (segmentLengthSeconds.toLong() * 1000L)).toInt() + 1

    val jobs = mutableMapOf<Int, Promise<File>>()
    fun getSegment(segment: Int): Promise<File> {
        if (segment < 0 || segment > segmentCount)
            throw IllegalArgumentException("invalid segment number")

        val found = jobs.get(segment)
        if (found != null)
            return found


        throw IllegalStateException()
    }
}

class FFmpegTests {
//    @Test
    fun testFFmpeg() {
        val loop = AsyncEventLoop()
        println("sup")

//val server = AsyncHttpServer {
//    println("got request")
//    AsyncHttpResponse.OK(body = Utf8StringBody("hello"))
//}
//

        loop.async {
            val ff = FFmpegStreamer.openFFmpegStream("/tmp/Sintel.2010.720p.mkv", 8)
        }

        loop.run()

    }
}