package eu.kanade.tachiyomi.animeextension.zh.mugua

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MuguaNetworkTest {
    @get:Rule val temporary = TemporaryFolder()
    private val clients = mutableListOf<OkHttpClient>()
    private var now = 0L
    private val policy = MuguaRequestPolicy({ 0L }, { now })
    private val headers = Headers.headersOf("Referer", "https://site.test/")
    private val image = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aQ2kAAAAASUVORK5CYII=",
    )

    @After
    fun closeClients() {
        clients.forEach { it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }
    }

    @Test
    fun hostCooldownStopsNetworkCallsWithoutAutomaticStatusRetries() = runBlocking {
        val calls = AtomicInteger()
        val http = http { chain ->
            calls.incrementAndGet()
            response(chain, if (chain.request().url.host == "site.test") 429 else 200, retryAfter = "60")
        }
        expectIOException { http.get("https://site.test/", headers) }
        expectIOException { http.get("https://site.test/other", headers) }
        assertEquals(1, calls.get())
        http.get("https://images.test/", headers).close()
        assertEquals(2, calls.get())
    }

    @Test
    fun redirectsAlsoObserveDestinationHostCooldown() = runBlocking {
        val calls = AtomicInteger()
        policy.response("images.test", 429, "60")
        val http = http { chain ->
            calls.incrementAndGet()
            response(chain, 302, location = "https://images.test/cover.jpg")
        }
        expectIOException { http.get("https://site.test/cover", headers) }
        assertEquals(1, calls.get())
    }

    @Test
    fun followsRelativeRedirectsAndPreservesPlaybackRequestHeadersAndCachePolicy() = runBlocking {
        val seen = mutableListOf<String>()
        val http = http { chain ->
            seen += chain.request().url.encodedPath
            assertEquals("https://site.test/", chain.request().header("Referer"))
            assertEquals("no-cache", chain.request().header("Cache-Control"))
            response(chain, if (seen.size == 1) 302 else 200, location = "/watch")
        }
        http.get("https://site.test/start", headers, CacheControl.FORCE_NETWORK).use {
            assertEquals("/watch", it.request.url.encodedPath)
        }
        assertEquals(listOf("/start", "/watch"), seen)
    }

    @Test
    fun cancellationDuringSpacingSendsNoRequestAndDoesNotReserveFutureSlots() = runBlocking {
        val spacing = MuguaRequestPolicy({ 500L }, { now })
        spacing.beforeRequest("site.test")
        val waiting = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val client = client { chain -> calls.incrementAndGet(); response(chain, 200) }
        val http = MuguaHttpClient(client, spacing) { waiting.complete(Unit); never.await() }
        val job = launch { http.get("https://site.test/", headers).close() }
        waiting.await()
        job.cancelAndJoin()
        assertEquals(0, calls.get())
        now = 500
        assertEquals(0L, spacing.beforeRequest("site.test"))
    }

    @Test
    fun failedCoverReturnsNoRemoteFallbackAndRetriesOnlyAfterExpiry() = runBlocking {
        val calls = AtomicInteger()
        val loader = loader(http { chain -> calls.incrementAndGet(); response(chain, 403) })
        assertNull(loader.load("https://images.test/a.jpg", true))
        assertNull(loader.load("https://images.test/a.jpg", true))
        assertEquals(1, calls.get())
        now = MuguaRequestPolicy.COVER_FAILURE_MILLIS
        assertNull(loader.load("https://images.test/a.jpg", true))
        assertEquals(2, calls.get())
    }

    @Test
    fun concurrentFailuresOfTheSameCoverOnlySendOneRequest() = runBlocking {
        val calls = AtomicInteger()
        val loader = loader(http { chain -> calls.incrementAndGet(); response(chain, 403) })
        val results = List(8) { async { loader.load("https://images.test/a.jpg", true) } }.awaitAll()
        assertTrue(results.all { it == null })
        assertEquals(1, calls.get())
    }

    @Test
    fun cachedCoversRemainUsableDuringHostCooldownWithoutDownloadingAgain() = runBlocking {
        val calls = AtomicInteger()
        val loader = loader(http { chain -> calls.incrementAndGet(); response(chain, 200, image) })
        val first = loader.load("https://images.test/a.jpg", true)
        assertNotNull(first)
        policy.response("images.test", 429, "60")
        assertEquals(first, loader.load("https://images.test/a.jpg", true))
        assertNull(loader.load("https://images.test/b.jpg", true))
        assertEquals(1, calls.get())
    }

    @Test
    fun cooldownDoesNotAddAnExtraFiveMinuteFailureForEveryQueuedCover() = runBlocking {
        val calls = AtomicInteger()
        val loader = loader(http { chain -> calls.incrementAndGet(); response(chain, 200, image) })
        policy.response("images.test", 429, "60")
        assertNull(loader.load("https://images.test/a.jpg", true))
        assertEquals(0, calls.get())
        now = 60_000
        assertNotNull(loader.load("https://images.test/a.jpg", true))
        assertEquals(1, calls.get())
    }

    @Test
    fun invalidImagesAndConnectionFailuresAreSuppressedToo() = runBlocking {
        val calls = AtomicInteger()
        val loader = loader(http { chain ->
            calls.incrementAndGet()
            if (chain.request().url.encodedPath.contains("connection")) throw IOException("offline")
            response(chain, 200, "<html>denied</html>".toByteArray())
        })
        repeat(2) {
            assertNull(loader.load("https://images.test/html.jpg", true))
            assertNull(loader.load("https://images.test/connection.jpg", true))
        }
        assertEquals(2, calls.get())
    }

    @Test
    fun disabledLocalModeReturnsTheOriginalUrlWithoutDownloading() = runBlocking {
        val calls = AtomicInteger()
        val loader = loader(http { chain -> calls.incrementAndGet(); response(chain, 200, image) })
        val url = "https://images.test/a.jpg"
        assertEquals(url, loader.load(url, false))
        assertNull(loader.load(null, true))
        assertEquals(0, calls.get())
    }

    @Test
    fun cancelledCoverIsNotMarkedAsAFailedImage() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val spacing = MuguaRequestPolicy({ 500L }, { now })
        spacing.beforeRequest("images.test")
        val http = MuguaHttpClient(client { chain -> response(chain, 200, image) }, spacing) {
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val loader = MuguaCoverLoader(MuguaCoverCache(temporary.newFolder()), http, spacing, headers)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { loader.load("https://images.test/a.jpg", true) }
        started.await()
        job.cancelAndJoin()
        assertTrue(spacing.canFetchCover("https://images.test/a.jpg"))
    }

    @Test
    fun cancellingAnInflightRequestCancelsTheOkHttpCall() = runBlocking {
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
    fun limitsConcurrentCoverTransfersToTwo() = runBlocking {
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val loader = loader(http { chain ->
            val count = active.incrementAndGet()
            maximum.updateAndGet { maxOf(it, count) }
            entered.countDown()
            try {
                check(release.await(5, TimeUnit.SECONDS))
                response(chain, 200, image)
            } finally {
                active.decrementAndGet()
            }
        })
        val tasks = List(6) { index -> async { loader.load("https://images.test/$index.jpg", true) } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            assertEquals(2, maximum.get())
        } finally {
            release.countDown()
        }
        assertTrue(tasks.awaitAll().all { it != null })
        assertEquals(2, maximum.get())
    }

    private fun loader(http: MuguaHttpClient) = MuguaCoverLoader(MuguaCoverCache(temporary.newFolder()), http, policy, headers)

    private fun client(handler: (Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { handler(it) }.build().also { clients += it }

    private fun http(handler: (Interceptor.Chain) -> Response) = MuguaHttpClient(client(handler), policy)

    private fun response(
        chain: Interceptor.Chain,
        code: Int,
        body: ByteArray = ByteArray(0),
        retryAfter: String? = null,
        location: String? = null,
    ): Response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(code).message("test").body(body.toResponseBody()).apply {
            retryAfter?.let { header("Retry-After", it) }
            location?.let { header("Location", it) }
        }.build()

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Expected, including the host cooldown subtype.
        }
    }
}
