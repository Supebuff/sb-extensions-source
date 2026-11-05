package eu.kanade.tachiyomi.animeextension.all.x1hub

import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in smoke test; normal CI stays offline and does not depend on the live catalogue. */
class LiveSiteTest {
    @Test
    fun publicCatalogueAndMp4ByteRanges() = runBlocking<Unit> {
        assumeTrue("Set X1HUB_LIVE_TEST=1 to contact the website", System.getenv("X1HUB_LIVE_TEST") == "1")
        val base = "https://www.x1hub.com"
        val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
        val http = HttpClient(client, RequestPolicy())
        val headers = Headers.headersOf("User-Agent", "Mozilla/5.0", "Referer", "$base/")
        suspend fun listing(url: String): Parser.Listing = http.get(url, headers).use {
            Parser.listing(it.body.string(), it.request.url.toString())
        }
        try {
            var sample: Parser.Item? = null
            for (path in listOf("most-popular/", "latest-updates/", "search/?q=JAV", "categories/chinese/")) {
                val first = listing("$base/$path")
                assertFalse("Empty first page: $path", first.items.isEmpty())
                val second = listing(requireNotNull(first.next))
                assertFalse("Empty second page: $path", second.items.isEmpty())
                assertNotEquals("Pagination repeated the same page: $path", first.items.map { it.url }, second.items.map { it.url })
                if (path == "latest-updates/") sample = first.items.first()
                println("Live catalogue: ${path.substringBefore('?')}, ${first.items.size} + ${second.items.size} items")
            }
            assertTrue(listing("$base/search/?q=zxqv1234567890unmatched").items.isEmpty())
            val video = requireNotNull(sample)
            val html = http.get(video.url, headers).use { it.body.string() }
            assertTrue(Parser.details(html, video.url).title.isNotBlank())
            val streams = Parser.streams(html, video.url)
            assertTrue(streams.isNotEmpty())
            for (stream in streams.take(2)) {
                val media = MediaResolver(http).resolve(stream.url, headers.newBuilder().set("Referer", video.url).build())
                assertTrue(media.headers["Range"] == null)
                println("Live media resolution: ${stream.label}, redirect resolved=${media.url != stream.url}, MP4 prefix verified")
                val rangeHeaders = media.headers.newBuilder().set("Range", "bytes=0-1023").build()
                http.get(media.url, rangeHeaders).use { response ->
                    assertTrue("Expected byte-range response", response.code == 206)
                    assertTrue("Expected MP4 content", response.header("Content-Type").orEmpty().startsWith("video/"))
                    val bytes = response.body.source().readByteArray(1024)
                    assertTrue("Missing MP4 ftyp signature", bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray()))
                    println("Live media: ${stream.label}, HTTP ${response.code}, MP4 ftyp, 1024 bytes")
                }
            }
            video.thumbnail?.let { cover ->
                http.get(cover, headers).use { response ->
                    assertTrue("Expected image", response.header("Content-Type").orEmpty().startsWith("image/"))
                    assertTrue(response.body.source().readByteArray(16).isNotEmpty())
                    println("Live cover: HTTP ${response.code}, image content")
                }
            }
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
