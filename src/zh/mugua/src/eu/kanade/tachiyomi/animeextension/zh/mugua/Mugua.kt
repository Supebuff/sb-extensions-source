package eu.kanade.tachiyomi.animeextension.zh.mugua

import android.content.Context
import androidx.preference.Preference
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSwitchPreference
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException

class Mugua : Source() {

    override val name = "Mugua"

    override val baseUrl = "https://91quanji.com"

    override val lang = "zh"

    override val supportsLatest = true

    private val pageUrls = mutableMapOf<String, MutableMap<Int, String>>()
    private val coverCache by lazy { MuguaCoverCache(File(context.noBackupFilesDir, "mugua-covers")) }
    private val requestPolicy = MuguaRequestPolicy(
        intervalMillis = {
            preferences.getString(PREF_REQUEST_INTERVAL, "500")?.toLongOrNull()
                ?.coerceIn(500L, 2000L) ?: 500L
        },
    )
    private val http by lazy { MuguaHttpClient(client, requestPolicy) }
    private val coverLoader by lazy { MuguaCoverLoader(coverCache, http, requestPolicy, headers) }

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

        val listing = http.get(url, headers).use { response ->
            MuguaParser.listing(response.body.string(), response.request.url.toString(), page)
        }
        val items = coroutineScope {
            listing.items.map { item ->
                async {
                    val cover = compatibleThumbnail(item.thumbnail)
                    SAnime.create().apply {
                        title = item.title
                        setUrlWithoutDomain(item.url)
                        thumbnail_url = cover
                        fetch_type = FetchType.Episodes
                    }
                }
            }.awaitAll()
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
        val details = http.get(absoluteUrl(anime.url), headers).use { response ->
            MuguaParser.details(response.body.string(), response.request.url.toString())
        }
        val cover = compatibleThumbnail(details?.thumbnail ?: anime.thumbnail_url)
        return SAnime.create().apply {
            title = details?.title ?: anime.title
            url = anime.url
            thumbnail_url = cover
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
        val (pageUrl, videoUrl) = http.get(absoluteUrl(url), headers, cache = CacheControl.FORCE_NETWORK).use { response ->
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
        addFeatureInfo(
            screen,
            "功能列表",
            "• 分類與搜尋：分類／廠商篩選、分頁與最新分類設定。\n" +
                "• 請求節流：同一主機依設定間隔發出請求。\n" +
                "• 限流冷卻：遵守網站指定的等待時間；未指定時由 30 秒起逐次延長，期間停止新請求。\n" +
                "• 縮圖保護：最多同時下載 2 張；失敗後暫停重抓 5 分鐘。\n" +
                "• 本機縮圖：重用快取，最多保留約 64 MB。\n" +
                "• HLS 播放：開播時重新取得播放連結。",
        )
        screen.addListPreference(
            key = PREF_REQUEST_INTERVAL,
            default = "500",
            title = "同一主機請求間隔",
            summary = "%s；間隔越長，首次載入越慢。更改後套用至後續請求。",
            entries = listOf("0.5 秒（預設）", "1 秒", "2 秒"),
            entryValues = listOf("500", "1000", "2000"),
        )
        screen.addSwitchPreference(
            key = PREF_LOCAL_COVERS,
            default = true,
            title = "本機縮圖相容模式",
            summary = "以網站需要的標頭下載縮圖並快取，套用節流與失敗暫停。首次載入較慢；切換後請重新整理列表。",
        )
        screen.addListPreference(
            key = PREF_LATEST_CATEGORY_KEY,
            default = DEFAULT_LATEST_CATEGORY,
            title = "最新來源分類",
            summary = "%s",
            entries = CATEGORIES.map { it.first },
            entryValues = CATEGORIES.map { it.second },
        )
        addFeatureInfo(
            screen,
            "保護範圍與使用提示",
            "套用於列表、搜尋、詳情、播放頁與本機模式的縮圖。關閉本機縮圖模式後，遠端圖片由 App 載入。" +
                "影片緩衝及下載由 App 控制。缺少縮圖時可等待冷卻結束後重新整理；節流無法保證網站不限制存取。",
        )
    }

    private suspend fun compatibleThumbnail(url: String?): String? =
        coverLoader.load(url, preferences.getBoolean(PREF_LOCAL_COVERS, true))

    private fun addFeatureInfo(screen: PreferenceScreen, title: String, summary: String) {
        // v16 stubs omit Preference(Context) and setSelectable; AniZen provides AndroidX at runtime.
        val row = Preference::class.java.getConstructor(Context::class.java).newInstance(screen.context)
        row.title = title
        row.summary = summary
        Preference::class.java.getMethod("setSelectable", Boolean::class.javaPrimitiveType).invoke(row, false)
        screen.addPreference(row)
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
        private const val PREF_LOCAL_COVERS = "local_cover_compatibility"
        private const val PREF_REQUEST_INTERVAL = "request_interval_ms"
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
