package eu.kanade.tachiyomi.animeextension.zh.mugua

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MuguaParserTest {
    private val base = "https://example.com/tag.jsp?t=category"

    @Test
    fun listingReadsCardsAndPrefersLazyImages() {
        val html = """
            <a href="/watch.jsp?v=one">
              <img src="/placeholder.jpg" data-src="//images.example.com/cover.jpg">
              <h5 class="thumb-spot__title">Example title</h5>
            </a>
            <a href="/watch.jsp?v=one"><h5>Duplicate</h5></a>
            <a href="/watch.jsp?v=missing"><img src="/image.jpg"></a>
        """
        val result = MuguaParser.listing(html, base, 1)
        assertEquals(1, result.items.size)
        assertEquals("Example title", result.items.single().title)
        assertEquals("https://example.com/watch.jsp?v=one", result.items.single().url)
        assertEquals("https://images.example.com/cover.jpg", result.items.single().thumbnail)
    }

    @Test
    fun paginationKeepsOpaqueCursorsAndTagParameters() {
        val result = MuguaParser.listing(
            """<ul class="pagination"><li><a href="?t=category&amp;p=cursor-token">2</a></li></ul>""",
            base,
            1,
        )
        assertEquals("https://example.com/tag.jsp?t=category&p=cursor-token", result.nextPageUrl)
        assertEquals(result.nextPageUrl, result.numberedPages[2])
    }

    @Test
    fun paginationAcceptsExplicitNextIcon() {
        val result = MuguaParser.listing(
            """<ul class="pagination"><a href="?t=category&amp;p=next"><i class="icon-chevron-right"></i></a></ul>""",
            base,
            3,
        )
        assertEquals("https://example.com/tag.jsp?t=category&p=next", result.nextPageUrl)
    }

    @Test
    fun lastPageDoesNotTreatPreviousIconAsNext() {
        val result = MuguaParser.listing(
            """
                <ul class="pagination">
                  <a href="?t=category&amp;p=previous"><i class="icon-chevron-left"></i></a>
                  <a href="?t=category&amp;p=first">1</a>
                  <a href="?t=category&amp;p=previous">44</a>
                </ul>
            """,
            base + "&p=last",
            45,
        )
        assertNull(result.nextPageUrl)
    }

    @Test
    fun homepageCategoryLinksAreNotPagination() {
        val result = MuguaParser.listing(
            """<a href="tag.jsp?t=category&amp;p=cursor">More items</a>""",
            "https://example.com/",
            1,
        )
        assertNull(result.nextPageUrl)
        assertEquals(emptyMap<Int, String>(), result.numberedPages)
    }

    @Test
    fun nextLinkToCurrentPageIsIgnored() {
        val result = MuguaParser.listing(
            """<a href="$base" rel="next">Next</a>""",
            base,
            1,
        )
        assertNull(result.nextPageUrl)
    }

    @Test
    fun videoDecodesCurrentXorPlayerFormat() {
        val code = """new DPlayer({video: {url: "https://cdn.example.com/list.m3u8?token=123%3D", type: "hls"}});"""
        val encoded = code.map { (it.code xor 0x80).toChar() }.joinToString("")
        assertEquals(
            "https://cdn.example.com/list.m3u8?token=123%3D",
            MuguaParser.videoUrl("""<script>eval(I("$encoded"));</script>""", base),
        )
    }

    @Test
    fun videoDecodesEscapedUnicodeXorLiteral() {
        val code = """new DPlayer({video:{url:'//cdn.example.com/list.m3u8'}});"""
        val encoded = code.map { it.code xor 0x80 }
            .joinToString("") { "\\u" + it.toString(16).padStart(4, '0') }
        assertEquals(
            "https://cdn.example.com/list.m3u8",
            MuguaParser.videoUrl("""<script>eval(I('$encoded'));</script>""", base),
        )
    }

    @Test
    fun videoAcceptsEscapedSignedUrlAndReorderedProperties() {
        val html = """<script>const player = {"video": {"type": "hls", "url": "https:\/\/cdn.example.com\/list.m3u8?token=abc\u0026v=2"}};</script>"""
        assertEquals("https://cdn.example.com/list.m3u8?token=abc&v=2", MuguaParser.videoUrl(html, base))
    }

    @Test
    fun videoSupportsNativeSourceAndRelativePaths() {
        val html = """<video><source src="/streams/list.m3u8?token=abc&amp;v=2"></video>"""
        assertEquals("https://example.com/streams/list.m3u8?token=abc&v=2", MuguaParser.videoUrl(html, base))
    }

    @Test
    fun videoRejectsMissingNonHlsAndUnsafeUrls() {
        for (url in listOf("https://example.com/image.jpg", "javascript:alert(1)", "data:text/plain,test")) {
            val html = """<script>const player = {video: {url: "$url"}};</script>"""
            assertNull(MuguaParser.videoUrl(html, base))
        }
        assertNull(MuguaParser.videoUrl("<html>Unavailable</html>", base))
    }

    @Test
    fun detailsUsesCurrentHeadingAndOptionalCover() {
        val result = MuguaParser.details(
            """<h1 class="title">Example title</h1><meta property="og:image" content="/cover.jpg">""",
            base,
        )
        assertEquals("Example title", result?.title)
        assertEquals("https://example.com/cover.jpg", result?.thumbnail)
        assertNull(MuguaParser.details("<h1 class='logo'>Site</h1>", base))
    }
}
