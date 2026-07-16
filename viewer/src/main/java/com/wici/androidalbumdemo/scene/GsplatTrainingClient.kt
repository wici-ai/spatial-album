package com.wici.androidalbumdemo.scene

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

const val GSPLAT_TRAINING_PROTOCOL = "wici.gsplat-training.v1"

data class GsplatTrainingCandidate(
    val candidateId: String,
    val filename: String,
    val contentType: String,
    val open: () -> InputStream,
)

class GsplatTrainingClient(
    private val endpoint: String,
    private val scratchDir: File,
    private val executor: Executor,
    private val pollIntervalMillis: Long = 500,
) {
    fun start(
        manifest: ReconstructionManifest,
        candidates: List<GsplatTrainingCandidate>,
        onStage: (ReconstructionStage) -> Unit,
        onReady: (File) -> Unit,
        onFailure: (String) -> Unit,
    ): ReconstructionRequest {
        val cancelled = AtomicBoolean(false)
        val connection = AtomicReference<HttpURLConnection?>()
        val jobId = AtomicReference<String?>()
        executor.execute {
            var upload: File? = null
            try {
                require(candidates.map { it.candidateId }.toSet() == manifest.selectedCandidateIds) {
                    "training candidates must exactly match selectedCandidateIds"
                }
                scratchDir.mkdirs()
                upload = File.createTempFile("gsplat-upload-", ".multipart", scratchDir)
                val boundary = "wici-gsplat-${UUID.randomUUID()}"
                buildMultipart(upload, boundary, manifest, candidates)
                if (cancelled.get()) return@execute
                onStage(ReconstructionStage.Uploading(0))
                val created = request(
                    "$endpoint",
                    "POST",
                    "multipart/form-data; boundary=$boundary",
                    upload,
                    connection,
                    cancelled,
                ) { bytes -> onStage(ReconstructionStage.Uploading(bytes)) }
                val createdJson = parseJson(created, "create training job")
                check(createdJson.getString("protocol") == GSPLAT_TRAINING_PROTOCOL)
                jobId.set(createdJson.getString("jobId"))
                onStage(ReconstructionStage.WaitingForInference)
                val result = poll(jobId.get()!!, connection, cancelled, onStage)
                if (cancelled.get()) return@execute
                onStage(ReconstructionStage.Streaming(0, 0))
                val output = File(scratchDir, "gsplat-${jobId.get()}.splat")
                download(result, output, connection, cancelled) { bytes ->
                    onStage(ReconstructionStage.Streaming(0, bytes))
                }
                val resultBytes = output.length()
                onStage(ReconstructionStage.CacheCommit(0, resultBytes))
                onReady(output)
                deleteJob(jobId.get()!!, connection)
                onStage(ReconstructionStage.Ready(0, resultBytes))
            } catch (error: Exception) {
                if (cancelled.get()) {
                    onStage(ReconstructionStage.Cancelled)
                } else {
                    val message = error.message ?: "gsplat training failed"
                    onStage(ReconstructionStage.Failed(message))
                    onFailure(message)
                }
            } finally {
                connection.getAndSet(null)?.disconnect()
                upload?.delete()
            }
        }
        return object : ReconstructionRequest {
            override fun cancel() {
                if (!cancelled.compareAndSet(false, true)) return
                connection.getAndSet(null)?.disconnect()
                jobId.get()?.let { id -> executor.execute { runCatching { deleteJob(id, AtomicReference()) } } }
            }
        }
    }

    private fun poll(
        jobId: String,
        connection: AtomicReference<HttpURLConnection?>,
        cancelled: AtomicBoolean,
        onStage: (ReconstructionStage) -> Unit,
    ): String {
        while (!cancelled.get()) {
            val payload = parseJson(request("$endpoint/$jobId", "GET", null, null, connection, cancelled), "query training job")
            val state = payload.getString("state")
            val step = payload.optInt("step", 0)
            val total = payload.optInt("totalSteps", 0)
            val stage = payload.optString("stage", state)
            onStage(ReconstructionStage.Training(step, total, stage))
            when (state) {
                "succeeded" -> return "$endpoint/$jobId/result"
                "failed" -> error(payload.optString("error", "training worker failed"))
                "cancelled" -> error("training job was cancelled")
            }
            Thread.sleep(pollIntervalMillis)
        }
        error("training cancelled")
    }

    private fun download(
        url: String,
        output: File,
        connection: AtomicReference<HttpURLConnection?>,
        cancelled: AtomicBoolean,
        progress: (Long) -> Unit,
    ) {
        val conn = open(url, "GET", null)
        connection.set(conn)
        checkResponse(conn, "download training result")
        var copied = 0L
        BufferedInputStream(conn.inputStream).use { input ->
            BufferedOutputStream(output.outputStream()).use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (cancelled.get()) error("training cancelled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    target.write(buffer, 0, count)
                    copied += count
                    progress(copied)
                }
            }
        }
        connection.compareAndSet(conn, null)
        conn.disconnect()
        require(output.length() > 0) { "training result is empty" }
    }

    private fun deleteJob(jobId: String, connection: AtomicReference<HttpURLConnection?>) {
        val conn = open("$endpoint/$jobId", "DELETE", null)
        connection.set(conn)
        val status = conn.responseCode
        if (status !in setOf(204, 404)) error("delete training job failed with HTTP $status")
        connection.compareAndSet(conn, null)
        conn.disconnect()
    }

    private fun request(
        url: String,
        method: String,
        contentType: String?,
        body: File?,
        connection: AtomicReference<HttpURLConnection?>,
        cancelled: AtomicBoolean,
        progress: (Long) -> Unit = {},
    ): ByteArray {
        val conn = open(url, method, contentType)
        connection.set(conn)
        if (body != null) {
            conn.setFixedLengthStreamingMode(body.length())
            var copied = 0L
            BufferedInputStream(body.inputStream()).use { input ->
                BufferedOutputStream(conn.outputStream).use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (cancelled.get()) error("training cancelled")
                        val count = input.read(buffer)
                        if (count < 0) break
                        target.write(buffer, 0, count)
                        copied += count
                        progress(copied)
                    }
                }
            }
        }
        val bytes = responseBytes(conn)
        checkResponse(conn, method.lowercase())
        connection.compareAndSet(conn, null)
        conn.disconnect()
        return bytes
    }

    private fun open(url: String, method: String, contentType: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            setRequestProperty("Accept", "application/json, application/octet-stream")
            if (contentType != null) setRequestProperty("Content-Type", contentType)
            doOutput = method == "POST"
        }

    private fun responseBytes(connection: HttpURLConnection): ByteArray {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.use { it.readBytes() } ?: ByteArray(0)
    }

    private fun checkResponse(connection: HttpURLConnection, operation: String) {
        if (connection.responseCode in 200..299) return
        val detail = runCatching { connection.errorStream?.use { it.readBytes().decodeToString() } }.getOrNull()
        error("$operation failed with HTTP ${connection.responseCode}${detail?.take(240)?.let { ": $it" }.orEmpty()}")
    }

    private fun parseJson(bytes: ByteArray, operation: String): JSONObject =
        runCatching { JSONObject(bytes.decodeToString()) }.getOrElse { error("$operation returned invalid JSON") }

    companion object {
        private val candidateId = Regex("[A-Za-z0-9@._:-]{1,160}")

        fun supports(backendBaseUrl: String, connectTimeoutMs: Int = 3_000): Boolean = runCatching {
            val base = backendBaseUrl.trimEnd('/')
            val connection = URL("$base/orbit/health").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = connectTimeoutMs
                connection.readTimeout = connectTimeoutMs
                if (connection.responseCode !in 200..299) return@runCatching false
                val payload = JSONObject(connection.inputStream.use { it.readBytes().decodeToString() })
                val capabilities = payload.optJSONArray("capabilities") ?: return@runCatching false
                (0 until capabilities.length()).any { capabilities.optString(it) == GSPLAT_TRAINING_PROTOCOL } &&
                    payload.optJSONObject("gsplatTraining")?.optBoolean("enabled", false) == true
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)

        internal fun buildManifest(manifest: ReconstructionManifest): JSONObject = JSONObject()
            .put("protocol", GSPLAT_TRAINING_PROTOCOL)
            .put("sceneId", manifest.sceneId)
            .put("anchorCandidateId", manifest.anchorCandidateId)
            .put("selectedCandidateIds", JSONArray(manifest.selectedCandidateIds.toList().sorted()))
            .put("options", JSONObject()
                .put("preloadTrainingData", true)
                .put("preloadChunkBytes", 768 * 1024)
                .put("batchSize", 8)
                .put("equivalentSteps", 30_000))

        internal fun buildMultipart(
            output: File,
            boundary: String,
            manifest: ReconstructionManifest,
            candidates: List<GsplatTrainingCandidate>,
        ) {
            BufferedOutputStream(output.outputStream()).use { out ->
                fun text(name: String, value: String) {
                    out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
                }
                text("protocol", GSPLAT_TRAINING_PROTOCOL)
                text("manifest", buildManifest(manifest).toString())
                val ordered = candidates.sortedBy { it.candidateId }
                for (candidate in ordered) {
                    require(candidateId.matches(candidate.candidateId)) { "invalid candidate id" }
                    val filename = candidate.filename.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    out.write((
                        "--$boundary\r\n" +
                            "Content-Disposition: form-data; name=\"image\"; filename=\"$filename\"\r\n" +
                            "Content-Type: ${candidate.contentType}\r\n" +
                            "X-Wici-Candidate-Id: ${candidate.candidateId}\r\n\r\n"
                    ).toByteArray())
                    candidate.open().use { it.copyTo(out) }
                    out.write("\r\n".toByteArray())
                }
                out.write("--$boundary--\r\n".toByteArray())
            }
        }
    }
}
