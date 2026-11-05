package eu.kanade.tachiyomi.animeextension.all.x1hub

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal class HttpClient(
    client: OkHttpClient,
    private val policy: RequestPolicy,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun get(
        url: String,
        headers: Headers,
        cache: CacheControl = CacheControl.Builder().maxAge(5, TimeUnit.MINUTES).build(),
    ): Response {
        var request = Request.Builder().url(url).headers(headers).cacheControl(cache).build()
        repeat(11) { redirects ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val pause = policy.beforeRequest(request.url.host)
                if (pause == 0L) break
                wait(pause)
            }
            val response = execute(request)
            val cooldown = policy.response(request.url.host, response.code, response.header("Retry-After"))
            if (cooldown > 0) {
                response.close()
                throw CooldownException(cooldown)
            }
            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val next = response.header("Location")?.let { request.url.resolve(it) }
                response.close()
                if (next == null || redirects == 10) throw IOException("網站重新導向無效或次數過多。")
                val builder = request.newBuilder().url(next)
                if (next.host != request.url.host) {
                    builder.removeHeader("Authorization").removeHeader("Cookie").removeHeader("Proxy-Authorization")
                }
                request = builder.build()
            } else {
                if (response.isSuccessful) return response
                val status = response.code
                response.close()
                throw IOException("網站回應 HTTP $status，請稍後再試。")
            }
        }
        throw IOException("網站重新導向次數過多。")
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, abandoned, _ -> abandoned.close() }
            }
        })
    }
}
