package eu.kanade.tachiyomi.animeextension.all.x1hub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class NetworkTest {
    private val clients = mutableListOf<OkHttpClient>()
    private var now = 0L
    private val policy = RequestPolicy(nowMillis = { now })
    private val headers = Headers.headersOf("Referer", "https://site.test/")

    @After
    fun closeClients() {
        clients.forEach {
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
    }

    @Test
    fun cooldownBlocksNewRequestsWithoutStatusRetries() = runBlocking {
        val calls = AtomicInteger()
        val http = http { chain ->
            calls.incrementAndGet()
            response(chain, if (chain.request().url.host == "site.test") 429 else 200, retryAfter = "60")
        }
        expectIOException { http.get("https://site.test/", headers) }
        expectIOException { http.get("https://site.test/other", headers) }
        assertEquals(1, calls.get())
        http.get("https://other.test/", headers).close()
        assertEquals(2, calls.get())
    }

    @Test
    fun redirectDestinationsObserveTheirOwnCooldown() = runBlocking {
        val calls = AtomicInteger()
        policy.response("other.test", 429, "60")
        val http = http { chain ->
            calls.incrementAndGet()
            response(chain, 302, location = "https://other.test/watch/")
        }
        expectIOException { http.get("https://site.test/", headers) }
        assertEquals(1, calls.get())
    }

    @Test
    fun redirectsPreserveRefererAndFreshPlaybackCachePolicy() = runBlocking {
        val seen = mutableListOf<String>()
        val http = http { chain ->
            seen += chain.request().url.encodedPath
            assertEquals("https://site.test/", chain.request().header("Referer"))
            assertEquals("no-cache", chain.request().header("Cache-Control"))
            response(chain, if (seen.size == 1) 302 else 200, location = "/watch/")
        }
        http.get("https://site.test/start/", headers, CacheControl.FORCE_NETWORK).use {
            assertEquals("/watch/", it.request.url.encodedPath)
        }
        assertEquals(listOf("/start/", "/watch/"), seen)
    }

    @Test
    fun crossHostRedirectDoesNotLeakExplicitCredentials() = runBlocking {
        val http = http { chain ->
            if (chain.request().url.host == "site.test") {
                response(chain, 302, location = "https://other.test/watch/")
            } else {
                assertNull(chain.request().header("Authorization"))
                assertNull(chain.request().header("Cookie"))
                assertNull(chain.request().header("Proxy-Authorization"))
                response(chain, 200)
            }
        }
        http.get("https://site.test/", headers.newBuilder().add("Authorization", "test").add("Cookie", "session=test").build()).close()
    }

    @Test
    fun redirectRetainsTheUserAgentSelectedAtTheWireLayer() = runBlocking {
        val http = http { chain ->
            if (chain.request().url.host == "site.test") {
                val wire = response(chain, 302, location = "https://cdn.test/media").newBuilder()
                    .request(chain.request().newBuilder().header("User-Agent", "verified-UA").build())
                    .body("".toResponseBody()).build()
                response(chain, 302, location = "https://cdn.test/media").newBuilder().networkResponse(wire).build()
            } else {
                assertEquals("verified-UA", chain.request().header("User-Agent"))
                assertNull(chain.request().header("Cookie"))
                response(chain, 200)
            }
        }
        http.get("https://site.test/", headers.newBuilder().set("User-Agent", "old-UA").set("Cookie", "site=private").build()).close()
    }

    @Test
    fun cancellationDuringSpacingSendsNoRequest() = runBlocking {
        policy.beforeRequest("site.test")
        val waiting = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val http = HttpClient(
            client { chain ->
                calls.incrementAndGet()
                response(chain, 200)
            },
            policy,
        ) {
            waiting.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val job = launch { http.get("https://site.test/", headers).close() }
        waiting.await()
        job.cancelAndJoin()
        assertEquals(0, calls.get())
        now = 500
        assertEquals(0L, policy.beforeRequest("site.test"))
    }

    @Test
    fun cancellationCancelsTheInflightOkHttpCall() = runBlocking {
        val entered = CompletableDeferred<okhttp3.Call>()
        val release = CountDownLatch(1)
        val http = http { chain ->
            entered.complete(chain.call())
            check(release.await(5, TimeUnit.SECONDS))
            response(chain, 200)
        }
        val job = launch { http.get("https://site.test/", headers).close() }
        try {
            val call = entered.await()
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun redirectLoopsAreBoundedAndHttpErrorsAreSurfaced() = runBlocking {
        val calls = AtomicInteger()
        val http = http { chain ->
            calls.incrementAndGet()
            response(chain, 302, location = "/loop/")
        }
        expectIOException { http.get("https://site.test/", headers) }
        assertEquals(11, calls.get())
        expectIOException { http { chain -> response(chain, 403) }.get("https://error.test/", headers) }
    }

    private fun client(handler: (Interceptor.Chain) -> Response): OkHttpClient = OkHttpClient.Builder().addInterceptor { handler(it) }.build().also { clients += it }

    private fun http(handler: (Interceptor.Chain) -> Response) = HttpClient(client(handler), policy) { now += it }

    private fun response(
        chain: Interceptor.Chain,
        code: Int,
        retryAfter: String? = null,
        location: String? = null,
    ): Response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(code).message("test").body("".toResponseBody()).apply {
            retryAfter?.let { header("Retry-After", it) }
            location?.let { header("Location", it) }
        }.build()

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Expected, including the cooldown subtype.
        }
    }
}
