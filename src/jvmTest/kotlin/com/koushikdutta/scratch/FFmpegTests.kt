package com.koushikdutta.scratch

import com.koushikdutta.scratch.async.async
import com.koushikdutta.scratch.event.AsyncEventLoop
import com.koushikdutta.scratch.event.NamedThreadFactory
import com.koushikdutta.scratch.event.await
import com.koushikdutta.scratch.parser.readAllString
import com.koushikdutta.scratch.stream.createAsyncRead
import org.junit.Test
import java.util.concurrent.TimeUnit


class FFmpegStreamer private constructor(val input: String, val duration: Long) {
    companion object {
        val executor = NamedThreadFactory.newSynchronousWorkers("ffmpeg")

        suspend fun openFFmpegStream(input: String): FFmpegStreamer {
            executor.await()
            val process = Runtime.getRuntime().exec(arrayOf("ffmpeg", "-i", input))
            val read = process.errorStream.createAsyncRead()
            val stderr = readAllString(read)
            val durationSearch = Regex("Duration: (.*?):(.*?):(.*?)\\.(.*?),")
            val match = durationSearch.find(stderr)
            if (match == null)
                throw IOException("invalid media, duration not found")

            val duration = TimeUnit.HOURS.toMillis(match.groupValues.get(1).toLong()) +
                TimeUnit.MINUTES.toMillis(match.groupValues.get(2).toLong()) +
                TimeUnit.SECONDS.toMillis(match.groupValues.get(3).toLong()) +
                match.groupValues.get(4).toDouble().times(1000).toLong()
            return FFmpegStreamer(input, duration)
        }
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
            val ff = FFmpegStreamer.openFFmpegStream("/tmp/Sintel.2010.720p.mkv")
        }

        loop.run()

    }
}