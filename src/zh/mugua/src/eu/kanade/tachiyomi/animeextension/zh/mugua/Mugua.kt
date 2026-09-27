package eu.kanade.tachiyomi.animeextension.zh.mugua

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.FetchType
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.network.GET
import extensions.utils.Source
import extensions.utils.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

class Mugua : Source() {

    override val name = "Mugua"

    override val baseUrl = "https://91quanji.com"

    override val lang = "zh"

    override val supportsLatest = false

    private val pageUrls = mutableMapOf<String, MutableMap<Int, String>>()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override suspend fun getPopularAnime(page: Int): AnimesPage = fetchListing("$baseUrl/", page)

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
            if (tag.isBlank()) {
                "$baseUrl/"
            } else {
                "$baseUrl/tag.jsp".toHttpUrl().newBuilder()
                    .addQueryParameter("t", tag)
                    .build()
                    .toString()
            }
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
        description = "此來源僅提供目錄瀏覽，不提供影片播放。"
        initialized = true
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = emptyList()

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = emptyList()

    override fun setupPreferenceScreen(screen: PreferenceScreen) = Unit

    private class TagFilter(
        name: String,
        private val tags: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, tags.map { it.first }.toTypedArray()) {
        fun selectedTag(): String = tags[state].second
    }

    companion object {
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
