package eu.kanade.tachiyomi.animeextension.all.x1hub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

/** Resolve KVS's website redirect before handing media to the app's separate HTTP stack. */
internal class MediaResolver(private val http: HttpClient) {
    data class Media(val url: String, val headers: Headers)

    suspend fun resolve(url: String, headers: Headers): Media {
        val probeHeaders = headers.newBuilder()
            .removeAll("If-Range")
            .set("Range", "bytes=0-11")
            .set("Accept-Encoding", "identity")
            .build()
        return http.get(url, probeHeaders, CacheControl.FORCE_NETWORK).use { response ->
            val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
            if (response.code !in listOf(200, 206) || !contentType.equals("video/mp4", ignoreCase = true)) {
                throw IOException("影片入口未回傳 MP4，請重新開啟影片；若仍失敗，請在 WebView 完成網站驗證。")
            }
            if (response.code == 206 && !response.header("Content-Range").orEmpty().startsWith("bytes 0-")) {
                throw IOException("影片伺服器回傳了錯誤的位元組範圍，請重新開啟影片。")
            }
            // Some servers ignore Range. Read only the signature and close, never buffer the movie.
            val prefix = withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                response.body.source().readByteArray(12)
            }
            currentCoroutineContext().ensureActive()
            if (!prefix.copyOfRange(4, 8).contentEquals(byteArrayOf(0x66, 0x74, 0x79, 0x70))) {
                throw IOException("影片入口回傳的內容不是有效 MP4，請重新開啟影片。")
            }
            val playbackHeaders = headers.newBuilder()
                .removeAll("Range")
                .removeAll("If-Range")
                .apply {
                    if (response.request.url.host != url.toHttpUrl().host) {
                        removeAll("Cookie")
                        removeAll("Authorization")
                        removeAll("Proxy-Authorization")
                    }
                    response.request.header("User-Agent")?.let { set("User-Agent", it) }
                }
                .build()
            Media(response.request.url.toString(), playbackHeaders)
        }
    }
}
