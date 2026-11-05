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
import extensions.utils.Source
import extensions.utils.get
import keiyoushi.utils.addListPreference
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

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
        TagFilter("分類／廠商", CATEGORIES + MAKERS.drop(1)),
    )

    private suspend fun fetchListing(listingUrl: String, page: Int): AnimesPage {
        val url = synchronized(pageUrls) {
            if (page == 1) {
                listingUrl
            } else {
                pageUrls[listingUrl]?.get(page)
            }
        } ?: return AnimesPage(emptyList(), false)

        val listing = client.get(url, headers).use { response ->
            MuguaParser.listing(response.body.string(), response.request.url.toString(), page)
        }
        val items = listing.items.map { item ->
            SAnime.create().apply {
                title = item.title
                setUrlWithoutDomain(item.url)
                thumbnail_url = item.thumbnail
                fetch_type = FetchType.Episodes
            }
        }
        synchronized(pageUrls) {
            if (page == 1) pageUrls.remove(listingUrl)
            val pages = pageUrls.getOrPut(listingUrl) { mutableMapOf(1 to listingUrl) }
            pages.putAll(listing.numberedPages)
            if (listing.nextPageUrl != null) {
                pages[page + 1] = listing.nextPageUrl
            } else {
                pages.remove(page + 1)
            }
        }
        return AnimesPage(items, listing.nextPageUrl != null)
    }

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val details = client.get(absoluteUrl(anime.url), headers).use { response ->
            MuguaParser.details(response.body.string(), response.request.url.toString())
        }
        return SAnime.create().apply {
            title = details?.title ?: anime.title
            url = anime.url
            thumbnail_url = details?.thumbnail ?: anime.thumbnail_url
            description = "單集 HLS 播放。"
            fetch_type = FetchType.Episodes
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            name = "單集"
            episode_number = 1.0f
            url = anime.url
        },
    )

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(hosterName = "HLS", hosterUrl = episode.url),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> = resolveVideos(hoster.hosterUrl)

    override suspend fun getVideoList(episode: SEpisode): List<Video> = resolveVideos(episode.url)

    private suspend fun resolveVideos(url: String): List<Video> {
        val (pageUrl, videoUrl) = client.get(absoluteUrl(url), headers, cache = CacheControl.FORCE_NETWORK).use { response ->
            val pageUrl = response.request.url.toString()
            val videoUrl = MuguaParser.videoUrl(response.body.string(), pageUrl)
                ?: throw IOException("找不到 HLS 播放連結，請稍後重試或在 WebView 確認頁面。")
            pageUrl to videoUrl
        }
        val videoHeaders = headers.newBuilder()
            .set("Referer", pageUrl)
            .build()

        return listOf(
            Video(
                videoUrl = videoUrl,
                videoTitle = "HLS",
                headers = videoHeaders,
                preferred = true,
            ),
        )
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

    private fun absoluteUrl(url: String): String = "$baseUrl/".toHttpUrl().resolve(url)?.toString()
        ?: throw IOException("無效的頁面網址")

    private class TagFilter(
        name: String,
        private val tags: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, tags.map { it.first }.toTypedArray()) {
        fun selectedTag(): String = tags[state].second
    }

    companion object {
        private const val PREF_LATEST_CATEGORY_KEY = "latest_category"
        private const val DEFAULT_LATEST_CATEGORY = ""

        private val CATEGORIES = arrayOf(
            "首頁" to "",
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
