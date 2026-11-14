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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.ProtocolException
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import kotlin.concurrent.thread

class GatewayRecoveryTest {
    private val key = "gateway-recovery-fixture-key-123456789"
    private val clients = mutableListOf<OkHttpClient>()

    @After
    fun closeClients() {
        clients.forEach {
            it.dispatcher.executorService.shutdownNow()
            it.connectionPool.evictAll()
        }
    }

    @Test
    fun lostGetConnectionRecoversWithoutManualRefresh() = runBlocking {
        val requests = droppedConnection("<html>fixture</html>") { gateway ->
            gateway.page("https://site.test/latest/").use {
                assertEquals("<html>fixture</html>", it.body.string())
                assertEquals("https://site.test/latest/", it.request.url.toString())
            }
        }
        assertEquals(2, requests.size)
        assertTrue(requests[0].first.startsWith("GET /v1/sites/x1hub/page?"))
        assertEquals(requests[0], requests[1])
    }

    @Test
    fun ticketPostCanBeReplayedAfterConnectionLoss() = runBlocking {
        val requests = droppedConnection("""{"urls":["/v1/sites/x1hub/asset?ticket=signed"]}""") { gateway ->
            val result = gateway.assets(listOf("https://site.test/contents/videos/1.jpg"), "image", "https://site.test/latest/")
            assertEquals(1, result.size)
            assertTrue(gateway.ownsAsset(result.single()))
        }
        assertEquals(2, requests.size)
        assertTrue(requests[0].first.startsWith("POST /v1/sites/x1hub/tickets "))
        assertEquals(requests[0], requests[1])
        assertTrue(requests[0].second.contains("https://site.test/contents/videos/1.jpg"))
    }

    @Test
    fun recoveryIsBoundedToOneAdditionalAttempt() = runBlocking {
        val calls = AtomicInteger()
        val gateway = gateway {
            calls.incrementAndGet()
            throw EOFException("fixture connection closed")
        }
        val error = failure { gateway.page("https://site.test/") }
        assertTrue(error.message.orEmpty().contains("CONNECTION_CLOSED"))
        assertEquals(2, calls.get())
    }

    @Test
    fun permanentFailuresAndSlowReadsAreNotReplayedOrExposed() = runBlocking {
        val secret = "private-URL-or-key-must-not-appear"
        val failures = listOf(
            UnknownServiceException("CLEARTEXT $secret") to "HTTP_BLOCKED",
            ConnectException("Permission denied $secret") to "ACCESS_DENIED",
            IOException("outer", SocketException("EPERM $secret")) to "ACCESS_DENIED",
            UnknownHostException(secret) to "DNS_FAILED",
            SSLHandshakeException(secret) to "TLS_FAILED",
            SocketTimeoutException(secret) to "TIMEOUT",
            InterruptedIOException("timeout $secret") to "TIMEOUT",
            ProtocolException(secret) to "HTTP_PROTOCOL",
            IOException(secret) to "IO_FAILED",
        )
        for ((cause, code) in failures) {
            val calls = AtomicInteger()
            val gateway = gateway {
                calls.incrementAndGet()
                throw cause
            }
            val error = failure { gateway.page("https://site.test/") }
            assertTrue(error.message.orEmpty().contains("[$code]"))
            assertFalse(error.message.orEmpty().contains(secret))
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun httpErrorsDoNotEnterTransportRecovery() = runBlocking {
        for (code in listOf(401, 429, 502, 503)) {
            val calls = AtomicInteger()
            val gateway = gateway { chain ->
                calls.incrementAndGet()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(code).message("fixture").header("Retry-After", "60")
                    .body("".toResponseBody()).build()
            }
            failure { gateway.page("https://site.test/") }
            assertEquals(1, calls.get())
        }
    }

    @Test
    fun cancellationDuringRecoveryDelayDoesNotSendAnotherRequest() = runBlocking {
        val paused = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val client = client {
            calls.incrementAndGet()
            throw EOFException("fixture closed")
        }
        val gateway = ChallengeGateway("http://127.0.0.1:8787", key, "x1hub", client) {
            paused.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
        val job = launch { gateway.page("https://site.test/").close() }
        paused.await()
        job.cancelAndJoin()
        assertEquals(1, calls.get())
    }

    @Test
    fun cancellationAlsoClosesTheRecoveryCall() = runBlocking {
        val entered = CompletableDeferred<okhttp3.Call>()
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val gateway = gateway { chain ->
            if (calls.incrementAndGet() == 1) throw EOFException("fixture closed")
            entered.complete(chain.call())
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("fixture").body("".toResponseBody()).build()
        }
        val job = launch { gateway.page("https://site.test/").close() }
        try {
            val call = entered.await()
            job.cancelAndJoin()
            assertTrue(call.isCanceled())
            assertEquals(2, calls.get())
        } finally {
            release.countDown()
        }
    }

    private fun client(block: (Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { block(it) }).build().also { clients += it }

    private fun gateway(block: (Interceptor.Chain) -> Response) =
        ChallengeGateway("http://127.0.0.1:8787", key, "x1hub", client(block), pauseBeforeRetry = {})

    private suspend fun failure(block: suspend () -> Unit): IOException {
        try {
            block()
        } catch (error: IOException) {
            return error
        }
        throw AssertionError("Expected IOException")
    }

    /** Real TCP close before response headers, without an external website or Android. */
    private suspend fun droppedConnection(body: String, check: suspend (ChallengeGateway) -> Unit): List<Pair<String, String>> {
        val requests = mutableListOf<Pair<String, String>>()
        val serverFailure = AtomicReference<Throwable>()
        ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val worker = thread(isDaemon = true, name = "gateway-recovery-fixture") {
                try {
                    repeat(2) { attempt ->
                        server.accept().use { socket ->
                            socket.soTimeout = 5000
                            val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                            val first = input.readLine() ?: throw EOFException("fixture request line")
                            var length = 0
                            while (true) {
                                val line = input.readLine() ?: throw EOFException("fixture headers")
                                if (line.isEmpty()) break
                                if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                            }
                            val content = CharArray(length)
                            var offset = 0
                            while (offset < length) {
                                val count = input.read(content, offset, length - offset)
                                if (count < 0) throw EOFException("fixture body")
                                offset += count
                            }
                            synchronized(requests) { requests += first to content.concatToString() }
                            if (attempt == 1) {
                                val bytes = body.toByteArray(Charsets.UTF_8)
                                val headers = "HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nX-Source-Url: https://site.test/latest/\r\nConnection: close\r\n\r\n"
                                socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII))
                                socket.getOutputStream().write(bytes)
                                socket.getOutputStream().flush()
                            }
                        }
                    }
                } catch (error: Throwable) {
                    serverFailure.set(error)
                }
            }
            val networkClient = OkHttpClient().also { clients += it }
            try {
                check(ChallengeGateway("http://127.0.0.1:${server.localPort}", key, "x1hub", networkClient, pauseBeforeRetry = {}))
            } finally {
                server.close()
                worker.join(6000)
            }
            serverFailure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
            assertFalse(worker.isAlive)
        }
        return requests.toList()
    }
}
