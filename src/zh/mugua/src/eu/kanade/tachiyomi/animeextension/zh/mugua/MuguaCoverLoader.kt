package eu.kanade.tachiyomi.animeextension.zh.mugua

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Headers
import java.io.IOException

internal class MuguaCoverLoader(
    private val cache: MuguaCoverCache,
    private val http: MuguaHttpClient,
    private val policy: MuguaRequestPolicy,
    private val headers: Headers,
) {
    private val slots = Semaphore(2)
    // Bounded striped locks coalesce concurrent loads of the same URL.
    private val locks = Array(32) { Mutex() }

    suspend fun load(url: String?, localMode: Boolean): String? {
        if (url == null) return null
        val original = try {
            withContext(Dispatchers.IO) { cache.originalUrl(url) }
        } catch (_: IOException) {
            return null
        }
        if (!localMode || original.startsWith("file://")) return original
        return slots.withPermit {
            locks[(original.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                try {
                    val existing = withContext(Dispatchers.IO) { cache.find(original) }
                    if (existing != null) return@withLock existing
                    if (!policy.canFetchCover(original)) return@withLock null
                    http.get(original, headers).use { response ->
                        withContext(Dispatchers.IO) { cache.put(original, response.body.byteStream()) }
                    }.also { policy.coverSucceeded(original) }
                } catch (_: MuguaCooldownException) {
                    null
                } catch (_: IOException) {
                    policy.coverFailed(original)
                    // A remote URL would let the app immediately retry outside our policy.
                    null
                }
            }
        }
    }
}
