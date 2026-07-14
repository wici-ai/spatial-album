package com.wici.androidalbumdemo

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

data class RemoteIntrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
)

data class RemoteCamera(
    /** OpenCV world-to-camera transform, row-major 4x4. */
    val w2c: DoubleArray,
    val intrinsics: RemoteIntrinsics,
    val width: Int,
    val height: Int,
) {
    fun resized(newWidth: Int, newHeight: Int, maxFramePixels: Int): RemoteCamera {
        require(newWidth > 0 && newHeight > 0) { "viewport dimensions must be positive" }
        require(newWidth.toLong() * newHeight <= maxFramePixels.toLong()) { "viewport exceeds maxFramePixels" }
        val sx = newWidth.toDouble() / width
        val sy = newHeight.toDouble() / height
        return copy(
            w2c = w2c.copyOf(),
            intrinsics = RemoteIntrinsics(
                fx = intrinsics.fx * sx,
                fy = intrinsics.fy * sy,
                cx = intrinsics.cx * sx,
                cy = intrinsics.cy * sy,
            ),
            width = newWidth,
            height = newHeight,
        ).validated(maxFramePixels)
    }

    fun validated(maxFramePixels: Int): RemoteCamera {
        require(maxFramePixels > 0) { "maxFramePixels must be positive" }
        require(width > 0 && height > 0 && width.toLong() * height <= maxFramePixels.toLong()) {
            "invalid camera dimensions"
        }
        require(w2c.size == 16 && w2c.all(Double::isFinite)) { "w2c must contain 16 finite values" }
        val expectedLastRow = doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        require((0..3).all { abs(w2c[12 + it] - expectedLastRow[it]) <= RIGID_EPSILON }) {
            "w2c last row must be [0,0,0,1]"
        }
        for (row in 0..2) {
            for (other in 0..2) {
                val dot = (0..2).sumOf { w2c[row * 4 + it] * w2c[other * 4 + it] }
                require(abs(dot - if (row == other) 1.0 else 0.0) <= RIGID_EPSILON) {
                    "w2c rotation must be orthonormal"
                }
            }
        }
        val determinant =
            w2c[0] * (w2c[5] * w2c[10] - w2c[6] * w2c[9]) -
                w2c[1] * (w2c[4] * w2c[10] - w2c[6] * w2c[8]) +
                w2c[2] * (w2c[4] * w2c[9] - w2c[5] * w2c[8])
        require(abs(determinant - 1.0) <= RIGID_EPSILON) { "w2c rotation determinant must be +1" }
        require(intrinsics.fx.isFinite() && intrinsics.fy.isFinite() &&
            intrinsics.cx.isFinite() && intrinsics.cy.isFinite() &&
            intrinsics.fx > 0.0 && intrinsics.fy > 0.0 &&
            intrinsics.cx in 0.0..width.toDouble() && intrinsics.cy in 0.0..height.toDouble()
        ) { "invalid camera intrinsics" }
        return this
    }

    companion object {
        private const val RIGID_EPSILON = 1e-4
    }
}

data class RemoteRenderLimits(
    val maxFramePixels: Int,
    val maxUploadBytes: Long,
    val sessionIdleTtlSeconds: Double,
)

data class RemoteRenderSession(
    val sessionId: String,
    val camera: RemoteCamera,
    val pivot: DoubleArray,
    val focusDistance: Double,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val limits: RemoteRenderLimits,
)

data class RemoteFrameMetadata(
    val requestId: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
)

data class RemoteRenderError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val details: JSONObject?,
)

class RemoteProtocolException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

object RemoteRenderProtocol {
    const val VERSION = "remote-render-v1"
    const val CAMERA_CONVENTION = "opencv-w2c-row-major-4x4"
    const val INTERACTIVE_MIME = "image/jpeg"
    const val CAPTURE_MIME = "application/zip"
    private val sessionIdPattern = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val errorCodes = setOf(
        "invalid_request", "unsupported_protocol", "invalid_camera", "invalid_source",
        "invalid_content_type", "invalid_json", "invalid_multipart", "route_not_found",
        "session_not_found", "session_expired", "request_out_of_order", "request_superseded",
        "upload_too_large", "body_too_large", "frame_too_large", "session_capacity",
        "engine_unavailable", "token_unavailable", "render_unavailable", "invalid_engine_output",
        "capture_too_large", "remote_render_disabled", "internal_error",
    )

    fun parseSession(json: String, contentType: String? = "application/json"): RemoteRenderSession = parse("session response") {
        require(contentType?.trim()?.lowercase() == "application/json") { "unexpected session MIME type" }
        val root = JSONObject(json).strict("session response", "protocol", "sessionId", "camera", "pivot", "focusDistance", "source", "limits")
        root.requireProtocol()
        val sessionId = root.requiredString("sessionId")
        require(sessionIdPattern.matches(sessionId)) { "invalid sessionId" }

        val limitsJson = root.requiredObject("limits").strict(
            "limits", "maxFramePixels", "maxUploadBytes", "sessionIdleTtlSeconds",
        )
        val limits = RemoteRenderLimits(
            maxFramePixels = limitsJson.positiveInt("maxFramePixels"),
            maxUploadBytes = limitsJson.positiveLong("maxUploadBytes"),
            sessionIdleTtlSeconds = limitsJson.finiteNumber("sessionIdleTtlSeconds").also {
                require(it > 0.0) { "sessionIdleTtlSeconds must be positive" }
            },
        )
        val pivotJson = root.requiredArray("pivot")
        require(pivotJson.length() == 3) { "pivot must contain three values" }
        val pivot = DoubleArray(3) { pivotJson.finiteNumber(it, "pivot[$it]") }
        val focusDistance = root.finiteNumber("focusDistance")
        require(focusDistance > 0.0) { "focusDistance must be positive" }
        val source = root.requiredObject("source").strict("source", "width", "height")
        RemoteRenderSession(
            sessionId = sessionId,
            camera = parseCameraObject(root.requiredObject("camera"), limits.maxFramePixels),
            pivot = pivot,
            focusDistance = focusDistance,
            sourceWidth = source.positiveInt("width"),
            sourceHeight = source.positiveInt("height"),
            limits = limits,
        )
    }

    fun parseCamera(json: JSONObject, maxFramePixels: Int): RemoteCamera = parse("camera") {
        parseCameraObject(json, maxFramePixels)
    }

    private fun parseCameraObject(json: JSONObject, maxFramePixels: Int): RemoteCamera {
        json.strict("camera", "convention", "w2c", "intrinsics", "width", "height")
        require(json.requiredString("convention") == CAMERA_CONVENTION) { "unsupported camera convention" }
        val matrix = json.requiredArray("w2c")
        require(matrix.length() == 16) { "w2c must contain 16 values" }
        val intrinsics = json.requiredObject("intrinsics").strict("intrinsics", "fx", "fy", "cx", "cy")
        return RemoteCamera(
            w2c = DoubleArray(16) { matrix.finiteNumber(it, "w2c[$it]") },
            intrinsics = RemoteIntrinsics(
                fx = intrinsics.finiteNumber("fx"), fy = intrinsics.finiteNumber("fy"),
                cx = intrinsics.finiteNumber("cx"), cy = intrinsics.finiteNumber("cy"),
            ),
            width = json.positiveInt("width"),
            height = json.positiveInt("height"),
        ).validated(maxFramePixels)
    }

    fun parseFrameMetadata(
        contentType: String?,
        headers: Map<String, String>,
        expectedMode: String,
        maxFramePixels: Int,
    ): RemoteFrameMetadata = parse("frame response") {
        require(expectedMode == "interactive" || expectedMode == "capture") { "invalid expected frame mode" }
        val expectedMime = if (expectedMode == "interactive") INTERACTIVE_MIME else CAPTURE_MIME
        require(maxFramePixels > 0) { "maxFramePixels must be positive" }
        val mime = contentType?.trim()?.lowercase()
        require(mime == expectedMime) { "unexpected frame MIME type" }
        fun header(name: String): String {
            val matches = headers.entries.filter { it.key.equals(name, ignoreCase = true) }
            require(matches.size == 1) { "missing or duplicate $name header" }
            return matches.single().value
        }
        require(header("X-Wici-Protocol") == VERSION) { "unsupported frame protocol" }
        val requestId = header("X-Wici-Request-Id").strictNonNegativeLong("request ID")
        val width = header("X-Wici-Frame-Width").strictPositiveInt("frame width")
        val height = header("X-Wici-Frame-Height").strictPositiveInt("frame height")
        require(width.toLong() * height <= maxFramePixels.toLong()) { "frame dimensions exceed maxFramePixels" }
        RemoteFrameMetadata(requestId, width, height, mime)
    }

    fun parseError(json: String, contentType: String? = "application/json"): RemoteRenderError = parse("error response") {
        require(contentType?.trim()?.lowercase() == "application/json") { "unexpected error MIME type" }
        val root = JSONObject(json).strict("error response", "protocol", "error")
        root.requireProtocol()
        val error = root.requiredObject("error").strict("error", setOf("code", "message", "retryable"), setOf("details"))
        val code = error.requiredString("code")
        require(code in errorCodes) { "unknown remote error code" }
        val details = if (error.has("details")) error.requiredObject("details") else null
        val message = error.requiredString("message")
        require(message.isNotEmpty()) { "error message must not be empty" }
        RemoteRenderError(code, message, error.requiredBoolean("retryable"), details)
    }

    fun galleryCreateJson(photoId: String): String {
        require(photoId.isNotEmpty() && photoId.length <= 256) { "invalid photoId" }
        return JSONObject().put("protocol", VERSION).put(
            "source", JSONObject().put("type", "gallery").put("photoId", photoId),
        ).toString()
    }

    fun frameRequestJson(requestId: Long, mode: String, camera: RemoteCamera, maxFramePixels: Int): String {
        require(requestId >= 0) { "requestId must be non-negative" }
        require(mode == "interactive" || mode == "capture") { "invalid frame mode" }
        camera.validated(maxFramePixels)
        return JSONObject().put("protocol", VERSION).put("requestId", requestId).put("mode", mode)
            .put("camera", camera.toJson()).toString()
    }

    private fun RemoteCamera.toJson() = JSONObject()
        .put("convention", CAMERA_CONVENTION)
        .put("w2c", JSONArray(w2c.toList()))
        .put("intrinsics", JSONObject().put("fx", intrinsics.fx).put("fy", intrinsics.fy).put("cx", intrinsics.cx).put("cy", intrinsics.cy))
        .put("width", width).put("height", height)

    private inline fun <T> parse(name: String, block: () -> T): T = try {
        block()
    } catch (error: RemoteProtocolException) {
        throw error
    } catch (error: Exception) {
        throw RemoteProtocolException("invalid $name: ${error.message}", error)
    }

    private fun JSONObject.requireProtocol() = require(requiredString("protocol") == VERSION) { "unsupported protocol" }

    private fun JSONObject.strict(name: String, vararg required: String) = strict(name, required.toSet(), emptySet())

    private fun JSONObject.strict(name: String, required: Set<String>, optional: Set<String>): JSONObject {
        val actual = keys().asSequence().toSet()
        require(actual.containsAll(required) && actual.all { it in required || it in optional }) { "$name has invalid fields" }
        return this
    }

    private fun JSONObject.requiredString(key: String): String {
        require(has(key) && !isNull(key) && opt(key) is String) { "$key must be a string" }
        return getString(key)
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean {
        require(has(key) && !isNull(key) && opt(key) is Boolean) { "$key must be a boolean" }
        return getBoolean(key)
    }

    private fun JSONObject.requiredObject(key: String): JSONObject {
        require(has(key) && !isNull(key) && opt(key) is JSONObject) { "$key must be an object" }
        return getJSONObject(key)
    }

    private fun JSONObject.requiredArray(key: String): JSONArray {
        require(has(key) && !isNull(key) && opt(key) is JSONArray) { "$key must be an array" }
        return getJSONArray(key)
    }

    private fun JSONObject.positiveInt(key: String): Int {
        val value = strictInteger(opt(key), key)
        require(value in 1..Int.MAX_VALUE.toLong()) { "$key must be a positive integer" }
        return value.toInt()
    }

    private fun JSONObject.positiveLong(key: String): Long {
        val value = strictInteger(opt(key), key)
        require(value > 0) { "$key must be a positive integer" }
        return value
    }

    private fun JSONObject.finiteNumber(key: String): Double = finiteNumber(opt(key), key)

    private fun JSONArray.finiteNumber(index: Int, name: String): Double = finiteNumber(opt(index), name)

    private fun finiteNumber(value: Any?, name: String): Double {
        require(value is Number) { "$name must be a number" }
        val result = value.toDouble()
        require(result.isFinite()) { "$name must be finite" }
        return result
    }

    private fun strictInteger(value: Any?, name: String): Long {
        require(value is Byte || value is Short || value is Int || value is Long) { "$name must be an integer" }
        return (value as Number).toLong()
    }

    private fun String.strictNonNegativeLong(name: String): Long {
        require(matches(Regex("0|[1-9][0-9]*"))) { "$name must be a canonical non-negative integer" }
        return toLongOrNull() ?: throw IllegalArgumentException("$name is out of range")
    }

    private fun String.strictPositiveInt(name: String): Int {
        val value = strictNonNegativeLong(name)
        require(value in 1..Int.MAX_VALUE.toLong()) { "$name must be positive" }
        return value.toInt()
    }
}
