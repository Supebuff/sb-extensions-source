package eu.kanade.tachiyomi.animeextension.zh.mugua

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal object MuguaParser {
    data class Item(val url: String, val title: String, val thumbnail: String?)
    data class Listing(
        val items: List<Item>,
        val numberedPages: Map<Int, String>,
        val nextPageUrl: String?,
    )

    fun listing(html: String, pageUrl: String, page: Int): Listing {
        val document = Jsoup.parse(html, pageUrl)
        val items = document.select("a[href*='watch.jsp?v=']").mapNotNull { link ->
            val url = httpUrl(link.absUrl("href"), pageUrl) ?: return@mapNotNull null
            val title = link.selectFirst(".thumb-spot__title, h5")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            val image = link.selectFirst("img")
            val thumbnail = listOf("data-src", "data-original", "data-lazy-src", "src")
                .firstNotNullOfOrNull { attribute ->
                    image?.absUrl(attribute)?.let { httpUrl(it, pageUrl) }
                }
            Item(url, title, thumbnail)
        }.distinctBy { it.url }

        val pagination = document.select(".pagination a[href], a[rel~=next]")
        val numberedPages = pagination.mapNotNull { link ->
            val number = link.text().trim().toIntOrNull() ?: return@mapNotNull null
            val url = httpUrl(link.absUrl("href"), pageUrl) ?: return@mapNotNull null
            number to url
        }.toMap()
        val next = numberedPages[page + 1] ?: pagination.firstOrNull {
            it.hasAttr("rel") && "next" in it.attr("rel").split(" ") ||
                it.selectFirst("i.icon-chevron-right") != null
        }?.absUrl("href")?.let { httpUrl(it, pageUrl) }

        return Listing(items, numberedPages, next?.takeUnless { it == pageUrl })
    }

    fun videoUrl(html: String, pageUrl: String): String? {
        val document = Jsoup.parse(html, pageUrl)
        for (script in document.select("script:not([src])")) {
            val source = script.data()
            for (match in OBFUSCATED_SCRIPT.findAll(source)) {
                val literal = unescape(match.groupValues[2]) ?: continue
                val decoded = buildString(literal.length) {
                    literal.forEach { append((it.code xor 0x80).toChar()) }
                }
                playerUrl(decoded, pageUrl)?.let { return it }
            }
            playerUrl(source, pageUrl)?.let { return it }
        }
        return document.select("video[src], video source[src]").firstNotNullOfOrNull {
            hlsUrl(it.absUrl("src"), pageUrl)
        }
    }

    fun details(html: String, pageUrl: String): Item? {
        val document: Document = Jsoup.parse(html, pageUrl)
        val title = document.selectFirst("h1.title")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return null
        val image = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { httpUrl(it, pageUrl) }
        return Item(pageUrl, title, image)
    }

    private fun playerUrl(source: String, pageUrl: String): String? =
        PLAYER_VIDEO_URL.findAll(source).firstNotNullOfOrNull { match ->
            unescape(match.groupValues[2])?.let { hlsUrl(it, pageUrl) }
        }

    private fun hlsUrl(url: String, pageUrl: String): String? =
        httpUrl(url, pageUrl)?.toHttpUrlOrNull()
            ?.takeIf { it.encodedPath.endsWith(".m3u8", ignoreCase = true) }
            ?.toString()

    private fun httpUrl(url: String, pageUrl: String): String? =
        url.takeIf { it.isNotBlank() }?.let { pageUrl.toHttpUrlOrNull()?.resolve(it)?.toString() }

    private fun unescape(value: String): String? =
        runCatching { StringEscapeUtils.unescapeEcmaScript(value) }.getOrNull()

    private val OBFUSCATED_SCRIPT =
        """eval\s*\(\s*I\(\s*(["'])((?:\\.|(?!\1)[\s\S])*?)\1\s*\)\s*\)""".toRegex()
    private val PLAYER_VIDEO_URL =
        """["']?video["']?\s*:\s*\{[^{}]*?["']?url["']?\s*:\s*(["'])((?:\\.|(?!\1)[\s\S])*?)\1""".toRegex(RegexOption.IGNORE_CASE)
}
