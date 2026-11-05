package eu.kanade.tachiyomi.animeextension.all.x1hub

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import java.io.IOException
import org.jsoup.parser.Parser as HtmlParser

internal object Parser {
    data class Item(val url: String, val title: String, val thumbnail: String?)
    data class Listing(val items: List<Item>, val next: String?)
    data class Details(val title: String, val thumbnail: String?, val genres: List<String>, val description: String)
    data class Stream(val url: String, val label: String)

    fun listing(html: String, pageUrl: String): Listing {
        val document = Jsoup.parse(html, pageUrl)
        // An empty search renders a separate recommendation list, not an empty video list.
        if (document.selectFirst(".list_videos h2")?.text()?.contains("couldn't find any matches", ignoreCase = true) == true) {
            return Listing(emptyList(), null)
        }
        val list = document.selectFirst(".list-videos")
            ?: throw IOException("找不到影片列表，請在 WebView 確認網站是否可用。")
        val items = list.select(".item > a[href]").mapNotNull { link ->
            val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            if (url.host != pageUrl.toHttpUrl().host || !url.encodedPath.startsWith("/videos/")) return@mapNotNull null
            val title = link.selectFirst(".title")?.text().orEmpty().ifBlank { link.attr("title") }
            if (title.isBlank()) return@mapNotNull null
            val image = link.selectFirst("img")
            val thumbnail = listOf("data-original", "data-src", "src").firstNotNullOfOrNull { key ->
                image?.absUrl(key)?.toHttpUrlOrNull()?.toString()
            }
            Item(url.toString(), title, thumbnail)
        }.distinctBy { it.url }
        if (items.isEmpty() && list.selectFirst(".empty-content") == null) {
            throw IOException("影片列表格式已變更，請更新來源或稍後重試。")
        }
        val next = document.select(".pagination li.next:not(.disabled) a[href]")
            .firstOrNull { it.attr("data-block-id").let { block -> block.isEmpty() || block.startsWith("list_videos_") } }
            ?.let { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.startsWith("#")) {
                    link.absUrl("href")
                } else {
                    val block = link.attr("data-block-id").takeIf { it.startsWith("list_videos_") }
                        ?: throw IOException("找不到下一頁參數。")
                    val builder = pageUrl.toHttpUrl().newBuilder().fragment(null)
                        .setQueryParameter("mode", "async")
                        .setQueryParameter("function", "get_block")
                        .setQueryParameter("block_id", block)
                    link.attr("data-parameters").split(';').forEach { parameter ->
                        val parts = parameter.split(':', limit = 2)
                        if (parts.size == 2) {
                            parts[0].split('+').forEach { key -> builder.setQueryParameter(key, parts[1]) }
                        }
                    }
                    builder.build().toString()
                }
            }?.takeIf { it != pageUrl && it.toHttpUrlOrNull()?.host == pageUrl.toHttpUrl().host }
        return Listing(items, next)
    }

    fun details(html: String, pageUrl: String): Details {
        val document = Jsoup.parse(html, pageUrl)
        val title = document.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() }
            ?: throw IOException("找不到影片詳情，影片可能已移除。")
        val thumbnail = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { pageUrl.toHttpUrl().resolve(it)?.toString() }
        val genres = document.select(".info a[href*=/categories/], .info a[href*=/tags/]")
            .map { it.text() }.filter { it.isNotBlank() }.distinct()
        val description = document.select(".info .item").map { it.text() }
            .filter { it.isNotBlank() }.joinToString("\n")
        return Details(title, thumbnail, genres, description)
    }

    fun streams(html: String, pageUrl: String): List<Stream> {
        val script = FLASHVARS.find(html)?.groupValues?.get(1)
            ?: throw IOException("找不到公開播放資訊，請在 WebView 確認影片是否可用。")
        val fields = FIELD.findAll(script).associate { it.groupValues[1] to unescape(it.groupValues[3]) }
        val streams = fields.keys.filter { VIDEO_KEY.matches(it) }.mapNotNull { key ->
            if (fields["${key}_redirect"] == "1") return@mapNotNull null
            val raw = fields.getValue(key)
            val decoded = if (raw.startsWith("function/")) {
                decodeUrl(raw, fields["license_code"].orEmpty())
            } else {
                raw
            }
            val url = pageUrl.toHttpUrl().resolve(decoded) ?: return@mapNotNull null
            if (!url.encodedPath.contains("/get_file/") && !MEDIA_PATH.containsMatchIn(url.encodedPath)) return@mapNotNull null
            Stream(url.toString(), fields["${key}_text"].orEmpty().ifBlank { "MP4" })
        }.distinctBy { it.url }.sortedByDescending { RESOLUTION.find(it.label)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        if (streams.isEmpty()) throw IOException("沒有公開可用的畫質，影片可能需要登入或已移除。")
        return streams
    }

    // KVS function/0 permutes the first 32 hash characters; the suffix and query must stay intact.
    internal fun decodeUrl(value: String, license: String): String {
        if (!value.startsWith("function/0/")) throw IOException("不支援的 KVS 播放連結格式。")
        val url = value.removePrefix("function/0/").toHttpUrlOrNull()
            ?: throw IOException("無效的播放網址。")
        val digits = license.replace("$", "")
        if (digits.length !in 14..16 || digits.any { it !in '0'..'9' }) throw IOException("無效的 KVS 播放資訊。")
        val normalized = digits.replace('0', '1')
        val center = normalized.length / 2
        val difference = kotlin.math.abs(normalized.take(center + 1).toLong() - normalized.drop(center).toLong())
        val seed = (difference * 4).toString().take(center + 1)
        val token = seed.flatMapIndexed { index, digit ->
            (0..3).map { offset -> (digits[index + offset].digitToInt() + digit.digitToInt()) % 10 }
        }
        val hash = url.encodedPathSegments.getOrNull(2).orEmpty()
        if (token.size < 32 || url.encodedPathSegments.firstOrNull() != "get_file" || hash.length < 32) {
            throw IOException("KVS 播放資訊不完整。")
        }
        val chars = hash.take(32).toCharArray()
        var sum = 0
        for (index in 31 downTo 0) {
            sum += token[index]
            val other = (index + sum) % 32
            val previous = chars[index]
            chars[index] = chars[other]
            chars[other] = previous
        }
        return url.newBuilder().setEncodedPathSegment(2, chars.concatToString() + hash.drop(32)).build().toString()
    }

    private fun unescape(value: String): String = HtmlParser.unescapeEntities(
        ESCAPE.replace(value) { match ->
            when (val escaped = match.groupValues[1]) {
                "n" -> "\n"

                "r" -> "\r"

                "t" -> "\t"

                else -> if (escaped.startsWith("u") || escaped.startsWith("x")) {
                    escaped.drop(1).toInt(16).toChar().toString()
                } else {
                    escaped
                }
            }
        },
        false,
    )

    private val FLASHVARS = Regex("""\bvar\s+flashvars\s*=\s*\{([\s\S]*?)\}\s*;""")
    private val FIELD = Regex("""\b(\w+)\s*:\s*(['"])((?:\\.|(?!\2)[^\\])*)\2""")
    private val VIDEO_KEY = Regex("video_(?:url|alt_url[0-9]*)")
    private val MEDIA_PATH = Regex("""\.(?:mp4|m3u8)(?:/|$)""", RegexOption.IGNORE_CASE)
    private val RESOLUTION = Regex("([0-9]+)p")
    private val ESCAPE = Regex("""\\(u[0-9a-fA-F]{4}|x[0-9a-fA-F]{2}|[\s\S])""")
}
