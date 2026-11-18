package eu.kanade.tachiyomi.animeextension.all.x1hub

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class VideoPage(html: String, val url: String) {
    val details = parse { Parser.details(html, url) }
    val streams = parse { Parser.streams(html, url) }

    private inline fun <T> parse(read: () -> T): Result<T> = try {
        Result.success(read())
    } catch (error: IOException) {
        Result.failure(error)
    }
}

/** Shares parsed pages only within one gateway configuration. */
internal class VideoPages(
    private val now: () -> Long = System::nanoTime,
    private val fetch: suspend (String) -> VideoPage,
) {
    private data class Entry(val page: VideoPage, val fetchedAt: Long)
    private data class Fetch(val page: VideoPage, val reused: Boolean)

    private val lock = Any()
    private val pending = mutableMapOf<String, CompletableDeferred<VideoPage?>>()
    private val recent = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > 8
    }

    suspend fun details(url: String): VideoPage = load(url, fresh = true).page

    suspend fun playback(url: String): VideoPage {
        val result = load(url, fresh = false)
        // An overlapping details fetch can succeed even when playable data is missing.
        return if (result.reused && result.page.streams.isFailure) load(url, fresh = true).page else result.page
    }

    private suspend fun load(url: String, fresh: Boolean): Fetch {
        while (true) {
            currentCoroutineContext().ensureActive()
            var owner = false
            val request = synchronized(lock) {
                pending[url] ?: run {
                    val cached = recent[url]
                    if (!fresh && cached != null && now() - cached.fetchedAt < MAX_AGE_NANOS) {
                        return Fetch(cached.page, reused = true)
                    }
                    recent.remove(url)
                    owner = true
                    CompletableDeferred<VideoPage?>().also { pending[url] = it }
                }
            }
            if (!owner) {
                // Cancellation of the owner does not cancel the remaining callers.
                val page = request.await() ?: continue
                return Fetch(page, reused = true)
            }

            try {
                val page = fetch(url)
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (page.streams.isSuccess) recent[url] = Entry(page, now())
                    pending.remove(url)
                }
                request.complete(page)
                return Fetch(page, reused = false)
            } catch (error: Throwable) {
                synchronized(lock) { pending.remove(url) }
                if (error is CancellationException) request.complete(null) else request.completeExceptionally(error)
                throw error
            }
        }
    }

    private companion object {
        val MAX_AGE_NANOS = TimeUnit.SECONDS.toNanos(20)
    }
}
