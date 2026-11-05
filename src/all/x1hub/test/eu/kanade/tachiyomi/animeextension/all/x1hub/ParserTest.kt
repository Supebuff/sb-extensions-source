package eu.kanade.tachiyomi.animeextension.all.x1hub

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ParserTest {
    private val base = "https://www.x1hub.com"
    private val card = """
        <div class="list-videos"><div class="item"><a href="/videos/123/sample/" title="Sample">
        <img src="data:image/gif;base64,placeholder" data-original="/covers/sample.jpg">
        <strong class="title">Sample &amp; title</strong></a></div></div>
    """

    @Test
    fun listingReadsLazyImagesAndIgnoresAdvertisementsAndAlbums() {
        val html = card.replace(
            "</div></div>",
            """</div><div class="item"><a href="https://ads.example/videos/1/ad/" title="Ad"></a></div>
            <div class="item"><a href="/albums/456/album/" title="Album"></a></div></div>""",
        )
        val result = Parser.listing(html, "$base/latest-updates/")
        assertEquals(1, result.items.size)
        assertEquals("Sample & title", result.items.single().title)
        assertEquals("$base/videos/123/sample/", result.items.single().url)
        assertEquals("$base/covers/sample.jpg", result.items.single().thumbnail)
        assertNull(result.next)
    }

    @Test
    fun listingDeduplicatesAndDoesNotReturnPlaceholderImages() {
        val html = card.replace("data-original=\"/covers/sample.jpg\"", "")
            .replace("</div></div>", "</div><div class=\"item\"><a href=\"/videos/123/sample/\" title=\"Duplicate\"></a></div></div>")
        val result = Parser.listing(html, base)
        assertEquals(1, result.items.size)
        assertNull(result.items.single().thumbnail)
    }

    @Test
    fun ordinaryPaginationUsesTheActualNextLink() {
        val html = card + """<div class="pagination"><li class="next"><a href="/latest-updates/2/">Next</a></li></div>"""
        assertEquals("$base/latest-updates/2/", Parser.listing(html, "$base/latest-updates/").next)
    }

    @Test
    fun searchPaginationExpandsCombinedCountersAndEncodesUnicode() {
        val html = card + """
            <div class="pagination"><li class="next"><a href="#search"
              data-block-id="list_videos_videos_list_search_result"
              data-parameters="q:中文 &amp; text;category_ids:;sort_by:post_date;from_videos+from_albums:2">Next</a></li></div>
        """
        val next = Parser.listing(html, "$base/search/?q=old").next!!.toHttpUrl()
        assertEquals("async", next.queryParameter("mode"))
        assertEquals("get_block", next.queryParameter("function"))
        assertEquals("list_videos_videos_list_search_result", next.queryParameter("block_id"))
        assertEquals("中文 & text", next.queryParameter("q"))
        assertEquals("2", next.queryParameter("from_videos"))
        assertEquals("2", next.queryParameter("from_albums"))
        assertNull(next.fragment)
    }

    @Test
    fun ajaxPaginationUpdatesTheCounterWithoutDuplicatingParameters() {
        val html = card + """<div class="pagination"><li class="next"><a href="#videos"
            data-block-id="list_videos_common_videos_list" data-parameters="sort_by:post_date;from:3">Next</a></li></div>"""
        val current = "$base/categories/chinese/?mode=async&function=get_block&block_id=list_videos_common_videos_list&from=2"
        val next = Parser.listing(html, current).next!!.toHttpUrl()
        assertEquals(listOf("3"), next.queryParameterValues("from"))
        assertEquals("/categories/chinese/", next.encodedPath)
    }

    @Test
    fun lastPageAndExplicitNoResultsHaveNoNextPage() {
        assertNull(Parser.listing(card + """<div class="pagination"><li class="prev"><a href="/latest-updates/1/">Prev</a></li></div>""", base).next)
        assertTrue(Parser.listing("""<div class="list-videos"><div class="empty-content">There is no data in this list.</div></div>""", base).items.isEmpty())
    }

    @Test
    fun missingAndChangedListsAreErrorsRatherThanEmptySuccess() {
        assertThrows(IOException::class.java) { Parser.listing("<html>Error</html>", base) }
        assertThrows(IOException::class.java) { Parser.listing("<div class='list-videos'><div class='changed'>New layout</div></div>", base) }
    }

    @Test
    fun albumPaginationDoesNotExtendAnEmptyVideoSearch() {
        val html = """<div class="list-videos"><div class="empty-content">No videos</div></div>
            <div class="pagination"><li class="next"><a href="#albums"
            data-block-id="list_albums_albums_list_search_result" data-parameters="from_albums:2">Next</a></li></div>"""
        assertNull(Parser.listing(html, "$base/search/?q=sample").next)
    }

    @Test
    fun noMatchMessageDoesNotReturnTheSitesRecommendedVideos() {
        val html = """<div class="list_videos"><h2>We couldn't find any matches for your search. Please try using different keywords.</h2></div>""" + card
        val result = Parser.listing(html, "$base/search/?q=unmatched")
        assertTrue(result.items.isEmpty())
        assertNull(result.next)
    }

    @Test
    fun detailsKeepMetadataAndResolveTheCover() {
        val details = Parser.details(
            """<h1>Sample title</h1><meta property="og:image" content="/preview.jpg">
            <div class="info"><div class="item">Duration: <em>10:00</em></div>
            <div class="item">Categories: <a href="/categories/asian/">Asian</a></div>
            <div class="item">Tags: <a href="/tags/sample/">Sample</a></div></div>""",
            "$base/videos/123/sample/",
        )
        assertEquals("Sample title", details.title)
        assertEquals("$base/preview.jpg", details.thumbnail)
        assertEquals(listOf("Asian", "Sample"), details.genres)
        assertTrue(details.description.contains("Duration: 10:00"))
        assertThrows(IOException::class.java) { Parser.details("<html></html>", base) }
    }

    @Test
    fun kvsDecodingKeepsHashSuffixAndQuery() {
        val decoded = Parser.decodeUrl(
            "function/0/$base/get_file/25/0123456789abcdef0123456789abcdefTAIL/1000/123/123.mp4/?token=a%2Bb",
            "\$361701411933162",
        )
        // Fixed independently computed permutation, not a decode/encode round trip.
        assertEquals("$base/get_file/25/7a215b64806d57ae103bed9829cf43cfTAIL/1000/123/123.mp4/?token=a%2Bb", decoded)
    }

    @Test
    fun streamsExcludeLoginAndAdvertisingAndSortPlayableQualities() {
        val html = """
            <script>var flashvars = {
              video_url: 'https:\/\/cdn.example\/get_file\/1\/hash\/sample.mp4\/?token=a\x26b=2', video_url_text: '480p',
              video_alt_url: '//cdn.example/sample_720p.mp4', video_alt_url_text: '720p',
              video_alt_url2: '$base/?login', video_alt_url2_text: '1080p', video_alt_url2_redirect: '1',
              video_alt_url3: 'https://ads.example/ad.html', video_alt_url3_text: 'Ad',
              preview_url: 'https://cdn.example/not-a-video.jpg'
            };</script>
        """
        val streams = Parser.streams(html, "$base/videos/123/sample/")
        assertEquals(listOf("720p", "480p"), streams.map { it.label })
        assertEquals("https://cdn.example/sample_720p.mp4", streams[0].url)
        assertTrue(streams[1].url.endsWith("?token=a&b=2"))
        assertFalse(streams.any { it.url.contains("login") })
    }

    @Test
    fun encodedStreamUsesLicenseAndExcludesRedirectEvenWithMp4Suffix() {
        val html = """var flashvars = {
            license_code: '${'$'}361701411933162',
            video_url: 'function/0/$base/get_file/25/0123456789abcdef0123456789abcdefTAIL/1000/123/123.mp4/',
            video_url_text: '480p',
            video_alt_url: '/login.mp4', video_alt_url_redirect: '1'
        };"""
        assertEquals(1, Parser.streams(html, base).size)
    }

    @Test
    fun unavailableAndMalformedPlayersFailClearly() {
        assertThrows(IOException::class.java) { Parser.streams("<html>Removed</html>", base) }
        assertThrows(IOException::class.java) { Parser.streams("var flashvars = {video_url: '/?login', video_url_redirect: '1'};", base) }
        assertThrows(IOException::class.java) { Parser.decodeUrl("function/1/https://cdn.example/test.mp4", "") }
        assertThrows(IOException::class.java) { Parser.decodeUrl("function/0/$base/get_file/1/short/a.mp4", "\$123456789012345") }
        assertThrows(IOException::class.java) { Parser.decodeUrl("function/0/$base/get_file/1/01234567890123456789012345678901/a.mp4", "invalid") }
    }
}
