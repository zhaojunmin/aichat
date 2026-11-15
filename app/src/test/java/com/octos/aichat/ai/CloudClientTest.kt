package com.octos.aichat.ai

import com.octos.aichat.ai.AiException.Kind
import com.octos.aichat.data.AiBackend
import com.octos.aichat.data.AppSettings
import com.octos.aichat.data.BackendConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class CloudClientTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        server.shutdown()
    }

    private fun settings(baseUrl: String = server.url("/v1").toString()) = AppSettings(
        backendConfigs = mapOf(AiBackend.DEEPSEEK to BackendConfig(baseUrl, "m", "sk-test")),
    )

    private val fastHttp = OkHttpClient.Builder()
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .build()

    private fun ok(content: String) = MockResponse().setBody(
        """{"choices":[{"message":{"role":"assistant","content":""" +
            """${org.json.JSONObject.quote(content)}}}]}""",
    )

    private fun kindOf(block: suspend () -> Unit): AiException = runBlocking {
        try {
            block()
            fail("expected AiException")
            error("unreachable")
        } catch (e: AiException) {
            e
        }
    }

    @Test fun success_returnsContent_andSendsAuthAndPath() = runBlocking {
        server.enqueue(ok("[\"好\"]"))
        val out = CloudClient(fastHttp).complete(settings(), "sys", "usr")
        assertEquals("[\"好\"]", out)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        assertTrue(req.body.readUtf8().contains("\"model\":\"m\""))
    }

    @Test fun httpStatusesAreClassified() {
        val cases = listOf(
            401 to Kind.UNAUTHORIZED,
            402 to Kind.INSUFFICIENT_BALANCE,
            404 to Kind.NOT_FOUND,
            422 to Kind.BAD_REQUEST,
            429 to Kind.RATE_LIMITED,
            503 to Kind.SERVER,
        )
        for ((code, kind) in cases) {
            server.enqueue(
                MockResponse().setResponseCode(code).setBody("""{"error":{"message":"x"}}"""),
            )
            val e = kindOf { CloudClient(fastHttp).complete(settings(), "s", "u") }
            assertEquals("HTTP $code", kind, e.kind)
            assertEquals(code, e.httpCode)
        }
    }

    /** Volcengine Ark reports arrears as 403 + AccountOverdueError: that's money, not a bad key. */
    @Test fun arkOverdue403IsBalanceNotAuth() {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":"AccountOverdueError","message":"overdue"}}"""),
        )
        assertEquals(
            Kind.INSUFFICIENT_BALANCE,
            kindOf { CloudClient(fastHttp).complete(settings(), "s", "u") }.kind,
        )
    }

    @Test fun readTimeoutIsTimeout() {
        // Server accepts the request but never answers: no real sleep to wait out on shutdown.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        assertEquals(
            Kind.TIMEOUT,
            kindOf { CloudClient(fastHttp).complete(settings(), "s", "u") }.kind,
        )
    }

    @Test fun refusedConnectionIsNetwork() {
        val deadUrl = server.url("/v1").toString()
        server.shutdown()
        assertEquals(
            Kind.NETWORK,
            kindOf { CloudClient(fastHttp).complete(settings(deadUrl), "s", "u") }.kind,
        )
    }

    @Test fun disconnectMidResponseIsClassified() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val kind = kindOf { CloudClient(fastHttp).complete(settings(), "s", "u") }.kind
        assertTrue(kind == Kind.NETWORK || kind == Kind.OTHER)
    }

    @Test fun malformedOrEmptyBodyIsBadResponse() {
        server.enqueue(MockResponse().setBody("<html>gateway</html>"))
        assertEquals(
            Kind.BAD_RESPONSE,
            kindOf { CloudClient(fastHttp).complete(settings(), "s", "u") }.kind,
        )
        server.enqueue(ok("   "))
        assertEquals(
            Kind.BAD_RESPONSE,
            kindOf { CloudClient(fastHttp).complete(settings(), "s", "u") }.kind,
        )
        server.enqueue(MockResponse().setBody("""{"choices":[]}"""))
        assertEquals(
            Kind.BAD_RESPONSE,
            kindOf { CloudClient(fastHttp).complete(settings(), "s", "u") }.kind,
        )
    }

    @Test fun malformedBaseUrlIsNotFound() {
        assertEquals(
            Kind.NOT_FOUND,
            kindOf { CloudClient(fastHttp).complete(settings("api.deepseek.com"), "s", "u") }.kind,
        )
    }

    /** Cancelling the coroutine must abort the in-flight HTTP call, not wait for it. */
    @Test fun cancellationAbortsCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val slowHttp = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val started = System.nanoTime()
        val job = async(Dispatchers.IO) { CloudClient(slowHttp).complete(settings(), "s", "u") }
        // request is in flight
        withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
        delay(20)
        job.cancel()
        runCatching { job.await() }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancel took ${elapsedMs}ms", elapsedMs < 1_000)
    }
}

class AiExceptionTest {

    @Test fun balanceMarkersWinOverStatus() {
        assertEquals(
            Kind.INSUFFICIENT_BALANCE,
            AiException.fromHttp(400, """{"error":{"code":"insufficient_balance"}}""").kind,
        )
        assertEquals(
            Kind.INSUFFICIENT_BALANCE,
            AiException.fromHttp(403, "AccountOverdueError").kind,
        )
        assertEquals(Kind.UNAUTHORIZED, AiException.fromHttp(403, "AccessDenied").kind)
    }

    @Test fun unknownStatusIsOther() {
        val e = AiException.fromHttp(418, "teapot")
        assertEquals(Kind.OTHER, e.kind)
        assertEquals(418, e.httpCode)
    }

    @Test fun ioMapping() {
        assertEquals(Kind.TIMEOUT, AiException.fromIo(java.net.SocketTimeoutException()).kind)
        assertEquals(
            Kind.TIMEOUT,
            AiException.fromIo(java.io.InterruptedIOException("timeout")).kind,
        )
        assertEquals(Kind.NETWORK, AiException.fromIo(java.net.UnknownHostException("x")).kind)
        assertEquals(
            Kind.NETWORK,
            AiException.fromIo(javax.net.ssl.SSLHandshakeException("x")).kind,
        )
        assertEquals(Kind.OTHER, AiException.fromIo(java.io.IOException("x")).kind)
    }

    @Test fun messageKeepsDetailForLogs() {
        val e = AiException.fromHttp(429, "slow down")
        assertTrue(e.message!!.contains("RATE_LIMITED 429: slow down"))
    }
}
