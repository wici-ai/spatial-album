package com.wici.androidalbumdemo

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class RemoteFrame(val metadata: RemoteFrameMetadata, val body: ByteArray)

sealed interface RemoteSessionSource {
    data class Gallery(val photoId: String) : RemoteSessionSource
    data class Image(val bytes: ByteArray, val filename: String, val mimeType: String) : RemoteSessionSource
}

class RemoteTransportException(
    message: String,
    val status: Int? = null,
    val remoteError: RemoteRenderError? = null,
    cause: Throwable? = null,
) : IOException(message, cause) {
    val retryable: Boolean get() = remoteError?.retryable ?: cause is SocketTimeoutException || status == null || status >= 500
    val sessionExpired: Boolean get() = status == 410 || remoteError?.code == "session_expired"
}

interface RemoteRenderClient {
    fun createSession(source: RemoteSessionSource): RemoteRenderSession
    fun renderFrame(session: RemoteRenderSession, requestId: Long, mode: String, camera: RemoteCamera): RemoteFrame
    fun deleteSession(sessionId: String)
    fun cancelActive()
}

class RemoteRenderTransport(
    baseUrl: String,
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxJsonBytes: Int = 256 * 1024,
    private val maxInteractiveBytes: Int = 12 * 1024 * 1024,
    private val maxCaptureBytes: Int = 32 * 1024 * 1024,
    private val maxUploadBytes: Int = 20 * 1024 * 1024,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : RemoteRenderClient {
    private val sessionsUrl = URL(baseUrl.trimEnd('/') + "/render/sessions")
    private val activeConnection = AtomicReference<HttpURLConnection?>()

    init {
        require(connectTimeoutMs > 0 && readTimeoutMs > 0)
        require(maxJsonBytes > 0 && maxInteractiveBytes > 0 && maxCaptureBytes > 0 && maxUploadBytes > 0)
    }

    override fun createSession(source: RemoteSessionSource): RemoteRenderSession {
        val connection = open(sessionsUrl, "POST")
        try {
            when (source) {
                is RemoteSessionSource.Gallery -> write(connection, "application/json", RemoteRenderProtocol.galleryCreateJson(source.photoId).toByteArray(StandardCharsets.UTF_8))
                is RemoteSessionSource.Image -> writeMultipart(connection, source)
            }
            val response = response(connection, maxJsonBytes)
            if (response.status != 201) throw response.asException("create session failed")
            return RemoteRenderProtocol.parseSession(response.text(), response.contentType)
        } finally {
            clearAndDisconnect(connection)
        }
    }

    override fun renderFrame(session: RemoteRenderSession, requestId: Long, mode: String, camera: RemoteCamera): RemoteFrame {
        val url = URL(sessionsUrl.toString() + "/${session.sessionId}/frames")
        val connection = open(url, "POST")
        try {
            val json = RemoteRenderProtocol.frameRequestJson(requestId, mode, camera, session.limits.maxFramePixels)
            write(connection, "application/json", json.toByteArray(StandardCharsets.UTF_8))
            val limit = if (mode == "interactive") maxInteractiveBytes else maxCaptureBytes
            val response = response(connection, limit)
            if (response.status != 200) throw response.asException("render frame failed")
            val metadata = RemoteRenderProtocol.parseFrameMetadata(
                response.contentType, response.headers, mode, session.limits.maxFramePixels,
            )
            if (metadata.requestId != requestId) throw RemoteTransportException("response request ID does not match request")
            return RemoteFrame(metadata, response.body)
        } finally {
            clearAndDisconnect(connection)
        }
    }

    override fun deleteSession(sessionId: String) {
        val connection = open(URL(sessionsUrl.toString() + "/$sessionId"), "DELETE")
        try {
            val response = response(connection, maxJsonBytes)
            if (response.status !in setOf(204, 404, 410)) throw response.asException("delete session failed")
        } finally {
            clearAndDisconnect(connection)
        }
    }

    override fun cancelActive() {
        activeConnection.getAndSet(null)?.disconnect()
    }

    private fun open(url: URL, method: String): HttpURLConnection {
        val connection = connectionFactory(url).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Connection", "keep-alive")
            setRequestProperty("Accept", "application/json, image/jpeg, application/zip")
        }
        if (!activeConnection.compareAndSet(null, connection)) {
            connection.disconnect()
            throw IllegalStateException("RemoteRenderTransport allows only one active request")
        }
        return connection
    }

    private fun write(connection: HttpURLConnection, contentType: String, body: ByteArray) {
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", contentType)
        connection.setFixedLengthStreamingMode(body.size)
        connection.outputStream.use { it.write(body) }
    }

    private fun writeMultipart(connection: HttpURLConnection, source: RemoteSessionSource.Image) {
        require(source.bytes.isNotEmpty()) { "image must not be empty" }
        require(source.bytes.size <= maxUploadBytes) { "image exceeds $maxUploadBytes bytes" }
        require(source.mimeType == "image/jpeg" || source.mimeType == "image/png") { "unsupported image MIME type" }
        require(source.filename.isNotBlank() && !source.filename.contains('\n') && !source.filename.contains('\r') && !source.filename.contains('"')) { "invalid filename" }
        val boundary = "wici-${UUID.randomUUID()}"
        val output = ByteArrayOutputStream()
        fun field(name: String, value: String) {
            output.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(StandardCharsets.UTF_8))
        }
        field("protocol", RemoteRenderProtocol.VERSION)
        field("sourceType", "image")
        output.write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"${source.filename}\"\r\nContent-Type: ${source.mimeType}\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
        output.write(source.bytes)
        output.write("\r\n--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        write(connection, "multipart/form-data; boundary=$boundary", output.toByteArray())
    }

    private fun response(connection: HttpURLConnection, limit: Int): HttpResponse {
        return try {
            val status = connection.responseCode
            val effectiveLimit = if (status >= 400) maxJsonBytes else limit
            val declaredLength = connection.contentLengthLong
            if (declaredLength > effectiveLimit) {
                throw RemoteTransportException("response body exceeds $effectiveLimit bytes", status)
            }
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val body = stream?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > effectiveLimit) throw RemoteTransportException("response body exceeds $effectiveLimit bytes", status)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            val headers = connection.headerFields.orEmpty().entries.filter { it.key != null && it.value?.size == 1 }
                .associate { it.key!! to it.value!!.single() }
            HttpResponse(status, connection.contentType, headers, body)
        } catch (error: RemoteTransportException) {
            throw error
        } catch (error: IOException) {
            throw RemoteTransportException("remote render request failed: ${error.message}", cause = error)
        }
    }

    private fun clearAndDisconnect(connection: HttpURLConnection) {
        activeConnection.compareAndSet(connection, null)
        connection.disconnect()
    }

    private data class HttpResponse(val status: Int, val contentType: String?, val headers: Map<String, String>, val body: ByteArray) {
        fun text() = body.toString(StandardCharsets.UTF_8)
        fun asException(prefix: String): RemoteTransportException {
            val parsed = try {
                RemoteRenderProtocol.parseError(text(), contentType)
            } catch (failure: RemoteProtocolException) {
                return RemoteTransportException(
                    "$prefix (HTTP $status with invalid structured error)",
                    status = status,
                    cause = failure,
                )
            }
            return RemoteTransportException(
                "$prefix (HTTP $status${parsed?.let { ": ${it.code}: ${it.message}" } ?: ""})",
                status, parsed,
            )
        }
    }
}
