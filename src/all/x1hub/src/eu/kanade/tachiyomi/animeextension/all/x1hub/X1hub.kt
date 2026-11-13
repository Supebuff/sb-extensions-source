package eu.kanade.tachiyomi.animeextension.all.x1hub

import android.content.Context
import android.text.InputType
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
import eu.kanade.tachiyomi.lib.challengegateway.ChallengeGateway
import extensions.utils.Source
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addEditTextPreference
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

class X1hub : Source() {
    override val name = "X1hub"
    override val baseUrl = "https://www.x1hub.com"
    override val lang = "all"
    override val supportsLatest = true

    private val policy = RequestPolicy(intervalMillis = {
        preferences.getString(PREF_INTERVAL, "500")?.toLongOrNull()?.coerceIn(500L, 2000L) ?: 500L
    })
    private val http by lazy { HttpClient(client, policy) }
    // Independent transport: host Cloudflare interceptors/cookies must not touch the gateway.
    private val gatewayTransport by lazy { OkHttpClient() }
    private fun gateway(): ChallengeGateway? {
        val endpoint = preferences.getString(PREF_GATEWAY, "").orEmpty().trim()
        if (endpoint.isEmpty()) return null
        return try {
            ChallengeGateway(endpoint, preferences.getString(PREF_GATEWAY_KEY, "").orEmpty(), "x1hub", gatewayTransport)
        } catch (error: IllegalArgumentException) {
            throw IOException("請確認 Scrapling 服務位址及金鑰設定。", error)
        }
    }

    private suspend fun page(url: String, cache: CacheControl = CacheControl.Builder().maxAge(5, java.util.concurrent.TimeUnit.MINUTES).build()) =
        gateway()?.page(url) ?: http.get(url, headers, cache)

    private suspend fun thumbnails(urls: List<String?>, referer: String): List<String?> {
        val service = gateway() ?: return urls
        val originals = urls.filterNotNull().distinct()
        val converted = originals.zip(service.assets(originals, "image", referer)).toMap()
        return urls.map { converted[it] }
    }
    private val pages = object : LinkedHashMap<String, MutableMap<Int, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableMap<Int, String>>?): Boolean = size > 16
    }

    override fun headersBuilder() = super.headersBuilder().set("Referer", "$baseUrl/")

    override suspend fun getPopularAnime(page: Int): AnimesPage = listing("$baseUrl/most-popular/", page)

    override suspend fun getLatestUpdates(page: Int): AnimesPage = listing("$baseUrl/latest-updates/", page)

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val url = if (query.isNotBlank()) {
            "$baseUrl/search/".toHttpUrl().newBuilder().addQueryParameter("q", query).build().toString()
        } else {
            val category = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.slug().orEmpty()
            if (category.isEmpty()) "$baseUrl/latest-updates/" else "$baseUrl/categories/$category/"
        }
        return listing(url, page)
    }

    private suspend fun listing(firstPage: String, page: Int): AnimesPage {
        val url = synchronized(pages) {
            if (page == 1) firstPage else pages[firstPage]?.get(page)
        } ?: throw IOException("分頁記錄已過期，請重新整理列表後再試。")
        val result = page(url).use { response ->
            Parser.listing(response.body.string(), response.request.url.toString())
        }
        synchronized(pages) {
            if (page == 1) pages.remove(firstPage)
            val listingPages = pages.getOrPut(firstPage) { mutableMapOf() }
            if (result.next != null) listingPages[page + 1] = result.next else listingPages.remove(page + 1)
        }
        val covers = thumbnails(result.items.map { it.thumbnail }, url)
        return AnimesPage(
            result.items.mapIndexed { index, item ->
                SAnime.create().apply {
                    title = item.title
                    setUrlWithoutDomain(item.url)
                    thumbnail_url = covers[index]
                    fetch_type = FetchType.Episodes
                }
            },
            result.next != null,
        )
    }

    override fun animeDetailsRequest(anime: SAnime): Request = Request.Builder().url(absoluteUrl(anime.url)).headers(headers).build()

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val details = page(absoluteUrl(anime.url)).use { response ->
            Parser.details(response.body.string(), response.request.url.toString())
        }
        val cover = thumbnails(listOf(details.thumbnail), absoluteUrl(anime.url)).first()
        return SAnime.create().apply {
            url = anime.url
            title = details.title
            thumbnail_url = cover ?: anime.thumbnail_url
            genre = details.genres.joinToString(", ")
            description = details.description
            status = SAnime.COMPLETED
            fetch_type = FetchType.Episodes
            initialized = true
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = listOf(
        SEpisode.create().apply {
            url = anime.url
            name = "完整影片"
            episode_number = 1.0f
        },
    )

    override fun videoListRequest(episode: SEpisode): Request = Request.Builder().url(absoluteUrl(episode.url)).headers(headers).build()

    override fun getAnimeUrl(anime: SAnime): String = absoluteUrl(anime.url)

    override fun getEpisodeUrl(episode: SEpisode): String = absoluteUrl(episode.url)

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = listOf(
        Hoster(hosterName = "X1hub", hosterUrl = episode.url),
    )

    override suspend fun getVideoList(hoster: Hoster): List<Video> = videos(hoster.hosterUrl)

    override suspend fun getVideoList(episode: SEpisode): List<Video> = videos(episode.url)

    override suspend fun resolveVideo(video: Video): Video {
        if (gateway()?.ownsAsset(video.videoUrl) == true) return video
        val media = MediaResolver(http).resolve(video.videoUrl, video.headers ?: headers)
        return video.copy(videoUrl = media.url, headers = media.headers)
    }

    private suspend fun videos(url: String): List<Video> = page(absoluteUrl(url), CacheControl.FORCE_NETWORK).use { response ->
        val pageUrl = response.request.url.toString()
        val videoHeaders = headers.newBuilder().set("Referer", pageUrl).apply {
            response.requestOnWire.header("User-Agent")?.let { set("User-Agent", it) }
        }.build()
        val streams = Parser.streams(response.body.string(), pageUrl)
        val service = gateway()
        val urls = service?.assets(streams.map { it.url }, "media", pageUrl) ?: streams.map { it.url }
        streams.mapIndexed { index, stream ->
            Video(videoUrl = urls[index], videoTitle = stream.label, headers = if (service == null) videoHeaders else Headers.EMPTY, preferred = index == 0)
        }
    }

    override fun getFilterList(): AnimeFilterList = AnimeFilterList(
        AnimeFilter.Header("常用分類僅於搜尋文字留空時套用"),
        CategoryFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        featureInfo(
            screen,
            "功能列表",
            "• 熱門、最新、關鍵字搜尋及分頁。\n" +
                "• 常用分類：搜尋文字留空時套用。\n" +
                "• 影片詳情、分類／標籤及單集播放。\n" +
                "• 公開 MP4 畫質：開播時重新取得連結，排除登入跳轉。\n" +
                "• 選定畫質後解析最終媒體網址，減少播放經過網站驗證入口。\n" +
                "• 網站頁面請求間隔與限流冷卻。\n" +
                "• 選用 Scrapling 服務取得頁面、縮圖及影片；電腦需在線。",
        )
        screen.addListPreference(
            key = PREF_INTERVAL,
            default = "500",
            title = "同一主機請求間隔",
            summary = "%s；更改後套用至後續頁面請求。",
            entries = listOf("0.5 秒（預設）", "1 秒", "2 秒"),
            entryValues = listOf("500", "1000", "2000"),
        )
        screen.addEditTextPreference(
            key = PREF_GATEWAY,
            default = "",
            title = "Scrapling 服務位址",
            summary = "留空使用手機直連；設定後頁面、縮圖及影片經服務取得，電腦需保持在線。",
            dialogMessage = "例如 http://192.168.0.70:8787；行動數據需使用可連到電腦的私有網路或 HTTPS 位址。",
            validate = { it.isBlank() || runCatching { ChallengeGateway.validateEndpoint(it) }.isSuccess },
            validationMessage = { "請填入 HTTPS 或私有網路的完整服務位址，不含路徑。" },
        )
        screen.addEditTextPreference(
            key = PREF_GATEWAY_KEY,
            default = "",
            title = "Scrapling 服務金鑰",
            summary = "填入此電腦網關的專用金鑰；不會傳給來源網站。",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            validate = { it.isEmpty() || (it.length >= 32 && it.none(Char::isISOControl)) },
            validationMessage = { "服務金鑰至少需要 32 個字元。" },
        )
        featureInfo(
            screen,
            "使用提示",
            "遇到網站限流時遵守 Retry-After；未提供時由 30 秒起延長，最長 15 分鐘。" +
                "圖片載入、影片緩衝、外部播放器與下載由 App 控制。" +
                "本站含多種語言的成人內容；不支援相簿、帳號管理或付費／登入限定畫質。",
        )
    }

    private fun featureInfo(screen: PreferenceScreen, title: String, summary: String) {
        // The v16 compile stubs omit these AndroidX members supplied by AniZen at runtime.
        val row = Preference::class.java.getConstructor(Context::class.java).newInstance(screen.context)
        row.title = title
        row.summary = summary
        Preference::class.java.getMethod("setSelectable", Boolean::class.javaPrimitiveType).invoke(row, false)
        screen.addPreference(row)
    }

    private fun absoluteUrl(url: String): String = "$baseUrl/".toHttpUrl().resolve(url)?.toString()
        ?: throw IOException("無效的頁面網址。")

    private class CategoryFilter : AnimeFilter.Select<String>("常用分類", CATEGORIES.map { it.first }.toTypedArray()) {
        fun slug(): String = CATEGORIES[state].second
    }

    companion object {
        private const val PREF_INTERVAL = "request_interval_ms"
        private const val PREF_GATEWAY = "scrapling_gateway_url"
        private const val PREF_GATEWAY_KEY = "scrapling_gateway_key"
        private val CATEGORIES = arrayOf(
            "全部（最新）" to "",
            "中文" to "chinese",
            "日本" to "japanese",
            "亞洲" to "asian",
            "素人" to "amateur",
            "Cosplay" to "cosplay",
            "動畫" to "hentai",
            "Webcam" to "webcam",
            "ASMR" to "asmr",
        )
    }
}
