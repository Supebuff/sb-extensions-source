package eu.kanade.tachiyomi.animeextension.zh.mugua

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import keiyoushi.utils.addListPreference
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

class Mugua : Source() {

    override val name = "Mugua"

    override val baseUrl = "https://91quanji.com"

    override val lang = "zh"

    override val supportsLatest = true

    private val pageUrls = mutableMapOf<String, MutableMap<Int, String>>()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchListing("$baseUrl/", page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val tag = preferences.getString(PREF_LATEST_CATEGORY_KEY, DEFAULT_LATEST_CATEGORY) ?: DEFAULT_LATEST_CATEGORY
        return fetchListing(tagListingUrl(tag), page)
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val listingUrl = if (query.isNotBlank()) {
            "$baseUrl/search.jsp".toHttpUrl().newBuilder()
                .addQueryParameter("keyword", query)
                .build()
                .toString()
        } else {
            val tag = filters.filterIsInstance<TagFilter>().firstOrNull { it.selectedTag().isNotBlank() }
                ?.selectedTag()
                ?: ""
            tagListingUrl(tag)
        }

        return fetchListing(listingUrl, page)
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        TagFilter("分類", CATEGORIES),
        TagFilter("廠商", MAKERS),
    )

    private suspend fun fetchListing(listingUrl: String, page: Int): AnimesPage {
        val url = synchronized(pageUrls) {
            if (page == 1) {
                listingUrl
            } else {
                pageUrls[listingUrl]?.get(page)
            }
        } ?: return AnimesPage(emptyList(), false)

        val response = client.newCall(GET(url, headers)).execute()
        return parseListing(response, listingUrl, page)
    }

    private fun parseListing(response: Response, listingUrl: String, page: Int): AnimesPage {
        val document = response.asJsoup()
        val items = document.select("a[href*='watch.jsp?v=']").mapNotNull { link ->
            val href = link.absUrl("href").ifBlank { link.attr("href") }
            val title = link.selectFirst(".thumb-spot__title, h5")?.text()?.trim().orEmpty()
            if (href.isBlank() || title.isBlank()) return@mapNotNull null

            val image = link.selectFirst("img")
            val thumbnail = image?.let {
                listOf("src", "data-src", "data-original", "data-lazy-src")
                    .firstNotNullOfOrNull { attribute ->
                        it.absUrl(attribute).takeIf(String::isNotBlank)
                    }
            }

            SAnime.create().apply {
                this.title = title
                setUrlWithoutDomain(href)
                thumbnail_url = thumbnail
                fetch_type = FetchType.Episodes
            }
        }.distinctBy { it.url }

        val nextPage = page + 1
        var hasNextPage = false
        val links = document.select("a[href*='p=']")
        synchronized(pageUrls) {
            val pages = pageUrls.getOrPut(listingUrl) { mutableMapOf(1 to listingUrl) }
            links.forEach { link ->
                val numberedPage = link.text().trim().toIntOrNull()
                val href = link.absUrl("href")
                if (numberedPage != null && href.isNotBlank()) {
                    pages[numberedPage] = href
                }
            }

            val nextLink = links.firstOrNull { it.text().trim().toIntOrNull() == nextPage }
                ?: links.lastOrNull { it.text().isBlank() }
            nextLink?.absUrl("href")?.takeIf(String::isNotBlank)?.let {
                pages[nextPage] = it
                hasNextPage = true
            }
        }

        return AnimesPage(items, hasNextPage)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime = SAnime.create().apply {
        title = anime.title
        url = anime.url
        thumbnail_url = anime.thumbnail_url
        description = "單集 720p HLS 播放。"
        fetch_type = FetchType.Episodes
        initialized = true
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "單集"
            episode_number = 1.0f
            url = anime.url
        },
    )

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(hosterName = "720p", hosterUrl = episode.url),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> = try {
        val pageUrl = absoluteUrl(hoster.hosterUrl)
        val html = client.newCall(GET(pageUrl, headers)).execute().use { it.body.string() }
        val videoUrl = extractVideoUrl(html) ?: return emptyList()
        val videoHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .build()

        listOf(
            Video(
                videoUrl = videoUrl,
                videoTitle = "720p",
                headers = videoHeaders,
                preferred = true,
            ),
        )
    } catch (_: Exception) {
        emptyList()
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_LATEST_CATEGORY_KEY,
            default = DEFAULT_LATEST_CATEGORY,
            title = "最新來源分類",
            summary = "%s",
            entries = CATEGORIES.map { it.first },
            entryValues = CATEGORIES.map { it.second },
        )
    }

    private fun tagListingUrl(tag: String): String = if (tag.isBlank()) {
        "$baseUrl/"
    } else {
        "$baseUrl/tag.jsp".toHttpUrl().newBuilder()
            .addQueryParameter("t", tag)
            .build()
            .toString()
    }

    private fun absoluteUrl(url: String): String = if (url.startsWith("http")) {
        url
    } else {
        "$baseUrl/${url.trimStart('/')}"
    }

    private fun extractVideoUrl(html: String): String? = OBFUSCATED_SCRIPT_REGEX.findAll(html)
        .firstNotNullOfOrNull { match ->
            val decoded = decodeXor128(match.groupValues[1])
            PLAYER_VIDEO_URL_REGEX.find(decoded)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        }

    private fun decodeXor128(value: String): String = buildString(value.length) {
        value.forEach { append((it.code xor 0x80).toChar()) }
    }

    private class TagFilter(
        name: String,
        private val tags: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, tags.map { it.first }.toTypedArray()) {
        fun selectedTag(): String = tags[state].second
    }

    companion object {
        private const val PREF_LATEST_CATEGORY_KEY = "latest_category"
        private const val DEFAULT_LATEST_CATEGORY = ""

        private val OBFUSCATED_SCRIPT_REGEX =
            """eval\s*\(\s*I\(\s*["']([\s\S]*?)["']\s*\)\s*\)""".toRegex()
        private val PLAYER_VIDEO_URL_REGEX =
            """video\s*:\s*\{\s*url\s*:\s*["']([^"']+\.m3u8(?:\?[^"']*)?)["']""".toRegex(RegexOption.IGNORE_CASE)

        private val CATEGORIES = arrayOf(
            "全部" to "",
            "国产精品" to "5y9kg97rdzxe",
            "国产自拍" to "649e2zxgw10p",
        )

        private val MAKERS = arrayOf(
            "全部" to "",
            "天美" to "z8y32yqgdemj",
            "其他" to "0598gp923vod",
            "蜜桃" to "7wxkgwqgm60p",
            "精東" to "onmvrndgxzw3",
            "91" to "poy4gx4rqwxv",
            "麻豆" to "e1mqglprj3v8",
            "台灣 Swag" to "k1o721d2zp8l",
            "星空無限" to "qyl9gmx236dp",
        )
    }
}
