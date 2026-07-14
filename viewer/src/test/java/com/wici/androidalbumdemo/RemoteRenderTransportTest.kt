package com.wici.androidalbumdemo

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RemoteRenderTransportTest {
    private var server: HttpServer? = null

    @After fun stop() { server?.stop(0) }

    @Test fun galleryCreateFrameAndIdempotentDeleteUseVersionedWireContract() {
        val requests = mutableListOf<Pair<String, String>>()
        val base = serve { exchange ->
            val body = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            synchronized(requests) { requests += exchange.requestMethod + " " + exchange.requestURI.path to body }
            when {
                exchange.requestURI.path == "/render/sessions" -> json(exchange, 201, sessionJson())
                exchange.requestURI.path.endsWith("/frames") -> {
                    exchange.responseHeaders.add("Content-Type", "image/jpeg")
                    exchange.responseHeaders.add("X-Wici-Protocol", RemoteRenderProtocol.VERSION)
                    exchange.responseHeaders.add("X-Wici-Request-Id", "7")
                    exchange.responseHeaders.add("X-Wici-Frame-Width", "640")
                    exchange.responseHeaders.add("X-Wici-Frame-Height", "480")
                    send(exchange, 200, byteArrayOf(1, 2, 3))
                }
                else -> send(exchange, 410, errorJson("session_expired").toByteArray())
            }
        }
        val transport = RemoteRenderTransport(base)
        val session = transport.createSession(RemoteSessionSource.Gallery("photo-7"))
        val frame = transport.renderFrame(session, 7, "interactive", session.camera)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.body)
        transport.deleteSession(session.sessionId)
        assertTrue(requests[0].second.contains("\"photoId\":\"photo-7\""))
        assertTrue(requests[1].second.contains("\"requestId\":7"))
        assertEquals("DELETE /render/sessions/session_123", requests[2].first)
    }

    @Test fun imageCreateUsesOnlyRequiredMultipartFields() {
        var contentType = ""
        var body = ""
        val base = serve { exchange ->
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            body = exchange.requestBody.readBytes().toString(StandardCharsets.ISO_8859_1)
            json(exchange, 201, sessionJson())
        }
        RemoteRenderTransport(base).createSession(
            RemoteSessionSource.Image(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1), "input.jpg", "image/jpeg"),
        )
        assertTrue(contentType.startsWith("multipart/form-data; boundary="))
        assertTrue(body.contains("name=\"protocol\""))
        assertTrue(body.contains("name=\"sourceType\""))
        assertTrue(body.contains("name=\"image\"; filename=\"input.jpg\""))
    }

    @Test fun parsesStructured410AndBoundsResponseBody() {
        val base = serve { exchange -> json(exchange, 410, errorJson("session_expired")) }
        val transport = RemoteRenderTransport(base, maxJsonBytes = 4096)
        val failure = expectTransport { transport.deleteSession("session_123") }
        // DELETE deliberately treats expiration as cleanup success.
        assertEquals(null, failure)

        server?.stop(0)
        val tooLarge = serve { exchange -> json(exchange, 201, "x".repeat(65)) }
        val bounded = RemoteRenderTransport(tooLarge, maxJsonBytes = 64)
        val sizeFailure = expectTransport { bounded.createSession(RemoteSessionSource.Gallery("photo")) }
        assertTrue(sizeFailure?.message.orEmpty().contains("exceeds 64 bytes"))
    }

    @Test fun timeoutIsRetryableAndDoesNotBecomeProtocolSuccess() {
        val base = serve { exchange ->
            Thread.sleep(150)
            runCatching { json(exchange, 201, sessionJson()) }
        }
        val failure = expectTransport {
            RemoteRenderTransport(base, connectTimeoutMs = 1000, readTimeoutMs = 30)
                .createSession(RemoteSessionSource.Gallery("photo"))
        } ?: throw AssertionError("expected timeout")
        assertTrue(failure.retryable)
        assertTrue(failure.cause is SocketTimeoutException)
    }

    @Test fun frame410PreservesStructuredError() {
        val base = serve { exchange -> json(exchange, 410, errorJson("session_expired")) }
        val session = RemoteRenderProtocol.parseSession(sessionJson())
        val failure = expectTransport { RemoteRenderTransport(base).renderFrame(session, 1, "interactive", session.camera) }
            ?: throw AssertionError("expected 410")
        assertTrue(failure.sessionExpired)
        assertEquals("session_expired", failure.remoteError?.code)
    }

    @Test fun cancelActiveDisconnectsBlockedRequest() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val base = serve { exchange ->
            entered.countDown()
            release.await(2, TimeUnit.SECONDS)
            runCatching { json(exchange, 201, sessionJson()) }
        }
        val transport = RemoteRenderTransport(base, readTimeoutMs = 2_000)
        val result = AtomicReference<Throwable?>()
        val request = Thread {
            try {
                transport.createSession(RemoteSessionSource.Gallery("photo"))
            } catch (failure: Throwable) {
                result.set(failure)
            }
        }
        request.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        transport.cancelActive()
        release.countDown()
        request.join(2_000)
        assertTrue("cancelled request must finish", !request.isAlive)
        assertTrue(result.get() is RemoteTransportException)
    }

    @Test fun rejectsOversizeUploadBeforeOpeningConnection() {
        val transport = RemoteRenderTransport("http://127.0.0.1:1", maxUploadBytes = 2)
        try {
            transport.createSession(RemoteSessionSource.Image(byteArrayOf(1, 2, 3), "x.jpg", "image/jpeg"))
            fail("expected upload limit rejection")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message.orEmpty().contains("exceeds 2 bytes"))
        }
    }

    @Test fun malformedErrorCannotMasqueradeAsStructuredBackendError() {
        val base = serve { exchange -> json(exchange, 503, "{}") }
        val failure = expectTransport {
            RemoteRenderTransport(base).createSession(RemoteSessionSource.Gallery("photo"))
        } ?: throw AssertionError("expected malformed error rejection")
        assertEquals(503, failure.status)
        assertEquals(null, failure.remoteError)
        assertTrue(failure.cause is RemoteProtocolException)
    }

    private fun expectTransport(block: () -> Unit): RemoteTransportException? = try {
        block(); null
    } catch (failure: RemoteTransportException) { failure }

    private fun serve(handler: (HttpExchange) -> Unit): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/") { exchange -> handler(exchange) }
        http.executor = Executors.newCachedThreadPool()
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    private fun json(exchange: HttpExchange, status: Int, body: String) {
        exchange.responseHeaders.add("Content-Type", "application/json")
        send(exchange, status, body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun send(exchange: HttpExchange, status: Int, body: ByteArray) {
        if (status == 204) exchange.sendResponseHeaders(status, -1)
        else { exchange.sendResponseHeaders(status, body.size.toLong()); exchange.responseBody.use { it.write(body) } }
        exchange.close()
    }

    private fun errorJson(code: String) =
        """{"protocol":"remote-render-v1","error":{"code":"$code","message":"gone","retryable":false}}"""

    private fun sessionJson() = """{
      "protocol":"remote-render-v1","sessionId":"session_123",
      "camera":{"convention":"opencv-w2c-row-major-4x4","w2c":[1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1],"intrinsics":{"fx":500,"fy":500,"cx":320,"cy":240},"width":640,"height":480},
      "pivot":[0,0,0],"focusDistance":2,"source":{"width":640,"height":480},
      "limits":{"maxFramePixels":1000000,"maxUploadBytes":1000000,"sessionIdleTtlSeconds":120}
    }"""
}
