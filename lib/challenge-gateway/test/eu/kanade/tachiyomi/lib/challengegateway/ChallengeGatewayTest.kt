package eu.kanade.tachiyomi.lib.challengegateway

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ChallengeGatewayTest {
    private val key = "private-key-for-gateway-tests-only-123456"
    private val clients = mutableListOf<OkHttpClient>()

    @After
    fun closeClients() {
        clients.forEach {
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
    }

    @Test
    fun pagesKeepSourceUrlAndSendOnlyTheGatewayKey() = runBlocking {
        val service = service { chain ->
            val request = chain.request()
            assertEquals("Bearer $key", request.header("Authorization"))
            assertNull(request.header("Cookie"))
            assertEquals("https://site.test/latest/", request.url.queryParameter("url"))
            response(chain, "<html>fixture</html>").newBuilder()
                .header("X-Source-Url", "https://site.test/latest/").build()
        }
        service.page("https://site.test/latest/").use { response ->
            assertEquals("https://site.test/latest/", response.request.url.toString())
            assertNull(response.request.header("Authorization"))
            assertEquals("<html>fixture</html>", response.body.string())
        }
    }

    @Test
    fun ticketUrlsStayOnTheConfiguredGatewayWithoutEmbeddingTheKey() = runBlocking {
        val service = service { chain ->
            assertEquals("POST", chain.request().method)
            response(chain, """{"urls":["/v1/sites/x1hub/asset?ticket=signed"]}""")
        }
        val url = service.assets(listOf("https://site.test/contents/videos/1.jpg"), "image", "https://site.test/").single()
        assertEquals("http://192.168.1.2:8787/v1/sites/x1hub/asset?ticket=signed", url)
        assertFalse(url.contains(key))
        assertTrue(service.ownsAsset(url))
        assertFalse(service.ownsAsset("https://evil.test/v1/sites/x1hub/asset?ticket=signed"))
    }

    @Test
    fun rejectsCrossOriginTicketsAndMismatchedCounts() = runBlocking {
        for (body in listOf("""{"urls":["https://evil.test/v1/sites/x1hub/asset?ticket=x"]}""", """{"urls":[]}""")) {
            val service = service { response(it, body) }
            expectIOException { service.assets(listOf("https://site.test/get_file/1"), "media", "https://site.test/") }
        }
    }

    @Test
    fun redirectedGatewayCannotReceiveApiKeyAtAnotherHost() = runBlocking {
        var requests = 0
        val service = service { chain ->
            requests++
            response(chain, "", 302).newBuilder().header("Location", "https://evil.test/").build()
        }
        expectIOException { service.page("https://site.test/") }
        assertEquals(1, requests)
    }

    @Test
    fun errorsRemainErrorsRatherThanEmptyLists() = runBlocking {
        for (status in listOf(401, 429, 502, 503)) {
            val service = service { chain -> response(chain, "", status).newBuilder().header("Retry-After", "60").build() }
            expectIOException { service.page("https://site.test/") }
        }
    }

    @Test
    fun cancellationCancelsTheInflightNetworkCall() = runBlocking {
        val entered = CompletableDeferred<okhttp3.Call>()
        val release = CountDownLatch(1)
        val service = service { chain ->
            entered.complete(chain.call())
            check(release.await(5, TimeUnit.SECONDS))
            response(chain, "")
        }
        val job = launch { service.page("https://site.test/").close() }
        try {
            val call = entered.await()
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
        } finally {
            release.countDown()
        }
    }

    @Test
    fun endpointValidationAllowsPrivateHttpAndRequiresHttpsForPublicServers() {
        listOf("http://192.168.1.2:8787", "http://10.0.0.1", "http://100.64.0.1", "https://gateway.test").forEach {
            ChallengeGateway.validateEndpoint(it)
        }
        listOf("http://public.test", "https://user:password@gateway.test", "https://gateway.test/path", "https://gateway.test/?key=secret").forEach {
            assertTrue(runCatching { ChallengeGateway.validateEndpoint(it) }.isFailure)
        }
    }

    private fun service(block: (Interceptor.Chain) -> Response): ChallengeGateway {
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { block(it) }).build()
        clients += client
        return ChallengeGateway("http://192.168.1.2:8787", key, "x1hub", client)
    }

    private fun response(chain: Interceptor.Chain, body: String, status: Int = 200): Response = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
        .body(body.toResponseBody()).build()

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Expected failure, not a successful empty result.
        }
    }
}
