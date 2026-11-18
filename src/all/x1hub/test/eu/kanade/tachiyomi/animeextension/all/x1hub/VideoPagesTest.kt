package eu.kanade.tachiyomi.animeextension.all.x1hub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class VideoPagesTest {
    @Test
    fun playbackReusesDetailsUntilExpiryAndDetailsRefreshReplacesThePage() = runBlocking {
        var now = 0L
        var loads = 0
        val pages = VideoPages(now = { now }) { videoPage(++loads) }
        val initial = pages.details(url)

        now = TimeUnit.SECONDS.toNanos(19)
        assertSame(initial, pages.playback(url))
        assertEquals(1, loads)
        now = TimeUnit.SECONDS.toNanos(20)
        val newer = pages.playback(url)
        assertNotSame(initial, newer)
        assertEquals(2, loads)

        val refreshed = pages.details(url)
        assertNotSame(newer, refreshed)
        assertSame(refreshed, pages.playback(url))
        assertEquals(3, loads)
    }

    @Test
    fun detailsAndPlaybackShareTheSamePendingFetch() = runBlocking {
        val response = CompletableDeferred<VideoPage>()
        var loads = 0
        val pages = VideoPages {
            loads++
            response.await()
        }
        val details = async(start = CoroutineStart.UNDISPATCHED) { pages.details(url) }
        val playback = async(start = CoroutineStart.UNDISPATCHED) { pages.playback(url) }
        val result = videoPage(1)
        response.complete(result)

        assertSame(result, details.await())
        assertSame(result, playback.await())
        assertEquals(1, loads)
        assertEquals(redirectedUrl, result.url)
        assertEquals("https://site.test/get_file/1/video.mp4", result.streams.getOrThrow().single().url)
    }

    @Test
    fun cancelledDetailsOwnerLetsInterestedPlaybackFetchAgain() = runBlocking {
        var loads = 0
        val result = videoPage(2)
        val pages = VideoPages {
            if (++loads == 1) awaitCancellation()
            result
        }
        val details = async(start = CoroutineStart.UNDISPATCHED) { pages.details(url) }
        val playback = async(start = CoroutineStart.UNDISPATCHED) { pages.playback(url) }
        details.cancelAndJoin()

        assertSame(result, playback.await())
        assertEquals(2, loads)
    }

    @Test
    fun waitingPlaybackFetchesFreshIfDetailsLacksPlayableData() = runBlocking {
        var loads = 0
        val response = CompletableDeferred<VideoPage>()
        val fresh = videoPage(2)
        val pages = VideoPages {
            if (++loads == 1) response.await() else fresh
        }
        val details = async(start = CoroutineStart.UNDISPATCHED) { pages.details(url) }
        val playback = async(start = CoroutineStart.UNDISPATCHED) { pages.playback(url) }
        response.complete(VideoPage("<h1>Detail only</h1>", redirectedUrl))

        assertEquals("Detail only", details.await().details.getOrThrow().title)
        assertSame(fresh, playback.await())
        assertEquals(2, loads)
    }

    @Test
    fun failedRefreshDiscardsThePreviousPageAndNextPlaybackCanRecover() = runBlocking {
        var loads = 0
        val pages = VideoPages {
            val load = ++loads
            if (load == 2) throw IOException("Synthetic gateway failure")
            videoPage(load)
        }
        val previous = pages.details(url)
        try {
            pages.details(url)
            throw AssertionError("Expected refresh to fail")
        } catch (error: IOException) {
            assertEquals("Synthetic gateway failure", error.message)
        }

        assertNotSame(previous, pages.playback(url))
        assertEquals(3, loads)
    }

    @Test
    fun differentVideoAndGatewaySessionDoNotSharePlaybackData() = runBlocking {
        var loads = 0
        val first = VideoPages { videoPage(++loads) }
        val second = VideoPages { videoPage(++loads) }
        val initial = first.details(url)

        assertNotSame(initial, first.playback("https://site.test/videos/other/"))
        assertNotSame(initial, second.playback(url))
        assertSame(initial, first.playback(url))
        assertTrue(initial.streams.isSuccess)
        assertEquals(3, loads)
    }

    private fun videoPage(number: Int) = VideoPage(
        """
        <h1>Sample video $number</h1>
        <script>var flashvars = {video_url:'/get_file/$number/video.mp4', video_url_text:'720p'};</script>
        """.trimIndent(),
        redirectedUrl,
    )

    private val url = "https://site.test/videos/sample/"
    private val redirectedUrl = "https://site.test/videos/canonical/"
}
