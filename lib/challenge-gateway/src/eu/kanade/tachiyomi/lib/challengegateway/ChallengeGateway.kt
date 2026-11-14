package eu.kanade.tachiyomi.lib.challengegateway

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Versioned transport shared by sources; website parsing remains in each extension. */
class ChallengeGateway(
    endpoint: String,
    private val apiKey: String,
    private val site: String,
    client: OkHttpClient = OkHttpClient(),
    private val pauseBeforeRetry: suspend () -> Unit = { delay(350) },
) {
    private val base = validateEndpoint(endpoint)
    private val transport = client.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .callTimeout(110, TimeUnit.SECONDS)
        .build()

    init {
        require(apiKey.length >= 32 && apiKey.none { it.isISOControl() }) { "請填入有效的網關服務金鑰。" }
        require(site.matches(Regex("[a-z0-9-]+")))
    }

    suspend fun page(url: String): Response {
        val request = request("page").url(
            path("page").newBuilder().addQueryParameter("url", url).build(),
        ).build()
        val response = execute(request)
        val source = try {
            (response.header("X-Source-Url") ?: throw IOException("網關缺少來源頁面資訊。")).toHttpUrl()
        } catch (error: Exception) {
            response.close()
            throw IOException("網關回傳的來源頁面資訊無效。", error)
        }
        // Callers parse relative links using the source URL; never expose API credentials.
        return response.newBuilder()
            .request(Request.Builder().url(source).build())
            .networkResponse(null).cacheResponse(null).priorResponse(null)
            .build()
    }

    suspend fun assets(urls: List<String>, kind: String, referer: String): List<String> {
        if (urls.isEmpty()) return emptyList()
        require(kind == "image" || kind == "media")
        require(urls.size <= 100)
        val json = buildJsonObject {
            put("kind", kind)
            put("referer", referer)
            put("urls", JsonArray(urls.map(::JsonPrimitive)))
        }
        val body = json.toString().toRequestBody("application/json".toMediaType())
        return execute(request("tickets").post(body).build()).use { response ->
            try {
                val result = Json.parseToJsonElement(response.body.string()).jsonObject.getValue("urls").jsonArray
                    .map { element ->
                        val value = element.jsonPrimitive.content
                        val url = base.resolve(value) ?: throw IOException("無效的資源網址。")
                        if (!sameOrigin(url) || url.encodedPath != "/v1/sites/$site/asset" || url.queryParameter("ticket").isNullOrBlank()) {
                            throw IOException("網關回傳了非預期的資源網址。")
                        }
                        url.toString()
                    }
                if (result.size != urls.size) throw IOException("網關回傳的資源數量不符。")
                result
            } catch (error: Exception) {
                throw IOException("網關資源回應無效，請更新服務並重試。", error)
            }
        }
    }

    fun ownsAsset(url: String): Boolean = runCatching {
        val parsed = url.toHttpUrl()
        sameOrigin(parsed) && parsed.encodedPath == "/v1/sites/$site/asset"
    }.getOrDefault(false)

    private fun sameOrigin(url: HttpUrl): Boolean = url.scheme == base.scheme && url.host == base.host && url.port == base.port

    private fun path(operation: String): HttpUrl = base.newBuilder().addPathSegments("v1/sites/$site/$operation").build()

    private fun request(operation: String): Request.Builder = Request.Builder()
        .url(path(operation))
        .header("Authorization", "Bearer $apiKey")
        .header("Cache-Control", "no-store")

    private suspend fun execute(request: Request): Response {
        val response = try {
            exchange(transport, request)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            val failure = GatewayConnectionFailure(error)
            val body = request.body
            val replayable = request.method == "GET" || (
                request.method == "POST" && request.url.encodedPath == "/v1/sites/$site/tickets" &&
                    body != null && !body.isOneShot() && !body.isDuplex()
                )
            if (!replayable || !failure.recoverable) throw failure.exception(error)
            pauseBeforeRetry()
            currentCoroutineContext().ensureActive()
            // A retry must not reuse another stale idle connection. Its pool closes every
            // connection on release; other concurrent gateway calls retain their own pool.
            val fresh = transport.newBuilder().connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)).build()
            try {
                exchange(fresh, request)
            } catch (retryError: IOException) {
                currentCoroutineContext().ensureActive()
                throw GatewayConnectionFailure(retryError).exception(retryError)
            }
        }
        if (response.isSuccessful) return response
        val status = response.code
        val retry = response.header("Retry-After")
        response.close()
        throw IOException(
            when (status) {
                401 -> "網關服務金鑰無效，或資源連結已過期。"
                429 -> "網關／來源限流，請${retry?.toLongOrNull()?.let { "於 $it 秒後" } ?: "稍後"}再試。"
                in 300..399 -> "網關位址發生重新導向，請直接填入最終服務位址。"
                else -> "網關回應 HTTP $status，請稍後重試或檢查服務狀態。"
            },
        )
    }

    private suspend fun exchange(client: OkHttpClient, request: Request): Response =
        suspendCancellableCoroutine { continuation ->
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

    companion object {
        fun validateEndpoint(value: String): HttpUrl {
            val url = value.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") {
                "服務位址只能包含協定、主機及連接埠。"
            }
            val host = url.host
            val labels = host.split('.')
            val parts = labels.mapNotNull(String::toIntOrNull)
            val privateIp = labels.size == 4 && parts.size == 4 && parts.all { it in 0..255 } && (
                parts[0] == 10 || parts[0] == 127 ||
                    (parts[0] == 192 && parts[1] == 168) ||
                    (parts[0] == 172 && parts[1] in 16..31) ||
                    (parts[0] == 100 && parts[1] in 64..127)
                )
            require(url.isHttps || privateIp || host == "localhost" || host == "::1") {
                "公開服務請使用 HTTPS；HTTP 僅供私有網路位址。"
            }
            return url
        }
    }
}
