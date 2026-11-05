package eu.kanade.tachiyomi.animeextension.all.x1hub

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class MediaResolverTest {
    private val clients = mutableListOf<OkHttpClient>()
    private val origin = "https://site.test/get_file/token/movie.mp4/"
    private val headers = Headers.headersOf("Referer", "https://site.test/videos/1/", "User-Agent", "test-UA")
    private val signature = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray()

    @After
    fun closeClients() {
        clients.forEach {
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
    }

    @Test
    fun resolvesCdnRedirectAndKeepsSignedQueryWithoutLeakingWebsiteCredentials() = runBlocking {
        val target = "https://cdn.test/media?token=opaque%2Bvalue&expires=123"
        val requests = AtomicInteger()
        val resolver = resolver { chain ->
            requests.incrementAndGet()
            assertEquals("bytes=0-11", chain.request().header("Range"))
            assertEquals("identity", chain.request().header("Accept-Encoding"))
            assertEquals("no-cache", chain.request().header("Cache-Control"))
            if (chain.request().url.host == "site.test") {
                response(chain, 302).newBuilder().header("Location", target).build()
            } else {
                assertNull(chain.request().header("Cookie"))
                assertNull(chain.request().header("Authorization"))
                assertNull(chain.request().header("Proxy-Authorization"))
                response(chain).newBuilder()
                    .request(chain.request().newBuilder().header("User-Agent", "solved-UA").build())
                    .build()
            }
        }
        val result = resolver.resolve(
            origin,
            headers.newBuilder().set("Cookie", "session=private").set("Authorization", "private")
                .set("Proxy-Authorization", "private").set("If-Range", "old").build(),
        )
        assertEquals(target, result.url)
        assertEquals("solved-UA", result.headers["User-Agent"])
        assertEquals(headers["Referer"], result.headers["Referer"])
        listOf("Range", "If-Range", "Cookie", "Authorization", "Proxy-Authorization").forEach {
            assertNull(result.headers[it])
        }
        assertEquals(2, requests.get())
    }

    @Test
    fun keepsSameHostCredentialsAndAcceptsMediaWithoutAFileExtension() = runBlocking {
        val target = "https://site.test/media/opaque"
        val resolver = resolver { chain ->
            if (chain.request().url.encodedPath.startsWith("/get_file/")) {
                response(chain, 302).newBuilder().header("Location", target).build()
            } else {
                assertEquals("session=test", chain.request().header("Cookie"))
                response(chain)
            }
        }
        val result = resolver.resolve(origin, headers.newBuilder().set("Cookie", "session=test").build())
        assertEquals(target, result.url)
        assertEquals("session=test", result.headers["Cookie"])
    }

    @Test
    fun closesAndReadsOnlyPrefixWhenServerIgnoresRange() = runBlocking {
        val body = CountingBody(signature + ByteArray(1024 * 1024))
        val resolver = resolver { response(it, 200, body) }
        assertEquals(origin, resolver.resolve(origin, headers).url)
        assertTrue(body.closed)
        assertTrue("Must not download the whole movie", body.readBytes <= 8192)
    }

    @Test
    fun rejectsSuccessfulHtmlLoginOrChallengePage() = runBlocking {
        val body = CountingBody("<html>verify or log in</html>".toByteArray(), "text/html")
        val resolver = resolver { response(it, 200, body) }
        expectIOException { resolver.resolve(origin, headers) }
        assertTrue(body.closed)
        assertEquals(0L, body.readBytes)
    }

    @Test
    fun rejectsNonMp4BytesEvenWithVideoContentType() = runBlocking {
        val body = CountingBody("<html>not a movie</html>".toByteArray())
        val resolver = resolver { response(it, 200, body) }
        expectIOException { resolver.resolve(origin, headers) }
        assertTrue(body.closed)
    }

    @Test
    fun truncatedPrefixIsAnErrorAndClosesResponse() = runBlocking {
        val body = CountingBody(byteArrayOf(0, 0, 0))
        val resolver = resolver { response(it, 200, body) }
        expectIOException { resolver.resolve(origin, headers) }
        assertTrue(body.closed)
    }

    @Test
    fun rejectsPartialResponseStartingAtWrongOffset() = runBlocking {
        val body = CountingBody(signature)
        val resolver = resolver { response(it, 206, body).newBuilder().header("Content-Range", "bytes 100-111/1000").build() }
        expectIOException { resolver.resolve(origin, headers) }
        assertTrue(body.closed)
    }

    @Test
    fun httpFailureDoesNotReturnProtectedUrlOrRetryForever() = runBlocking {
        val calls = AtomicInteger()
        val resolver = resolver {
            calls.incrementAndGet()
            response(it, 403)
        }
        expectIOException { resolver.resolve(origin, headers) }
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellationWhileWaitingDoesNotStartMediaRequest() = runBlocking {
        val waiting = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val policy = RequestPolicy(nowMillis = { 0L })
        policy.beforeRequest("site.test")
        val http = HttpClient(client { calls.incrementAndGet(); response(it) }, policy) {
            waiting.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val job = launch { MediaResolver(http).resolve(origin, headers) }
        waiting.await()
        job.cancelAndJoin()
        assertEquals(0, calls.get())
    }

    private fun client(handler: (Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { handler(it) }.build().also { clients += it }

    private fun resolver(handler: (Interceptor.Chain) -> Response): MediaResolver {
        var now = 0L
        return MediaResolver(HttpClient(client(handler), RequestPolicy(nowMillis = { now })) { now += it })
    }

    private fun response(
        chain: Interceptor.Chain,
        code: Int = 206,
        body: ResponseBody = signature.toResponseBody("video/mp4".toMediaType()),
    ): Response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
        .code(code).message("test").body(body).header("Content-Type", body.contentType().toString())
        .header("Content-Range", "bytes 0-11/1000").build()

    private suspend fun expectIOException(block: suspend () -> Any) {
        try {
            block()
            throw AssertionError("Expected IOException")
        } catch (_: IOException) {
            // Expected; an unusable media response must not be handed to the player.
        }
    }

    private class CountingBody(bytes: ByteArray, type: String = "video/mp4") : ResponseBody() {
        var readBytes = 0L
        var closed = false
        private val length = bytes.size.toLong()
        private val mediaType = type.toMediaType()
        private val buffered = object : ForwardingSource(Buffer().write(bytes)) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, byteCount).also {
                if (it > 0) readBytes += it
            }
            override fun close() {
                closed = true
                super.close()
            }
        }.buffer()

        override fun contentType() = mediaType
        override fun contentLength() = length
        override fun source(): BufferedSource = buffered
    }
}
