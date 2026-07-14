package com.wici.androidalbumdemo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import kotlin.math.max
import kotlin.math.min

internal data class CaptureImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE)
        require(pixels.size == width * height)
    }
}

internal interface ReleaseCaptureImageCodec {
    fun decode(bytes: ByteArray): CaptureImage
    fun scale(image: CaptureImage, width: Int, height: Int, filter: Boolean): CaptureImage
    fun dataUrl(image: CaptureImage, mimeType: String, quality: Int): String
}

private object AndroidReleaseCaptureImageCodec : ReleaseCaptureImageCodec {
    override fun decode(bytes: ByteArray): CaptureImage {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("capture image is not decodable")
        return bitmap.toCaptureImageAndRecycle()
    }

    override fun scale(image: CaptureImage, width: Int, height: Int, filter: Boolean): CaptureImage {
        val source = image.toBitmap()
        val scaled = Bitmap.createScaledBitmap(source, width, height, filter)
        if (scaled !== source) source.recycle()
        return scaled.toCaptureImageAndRecycle()
    }

    override fun dataUrl(image: CaptureImage, mimeType: String, quality: Int): String {
        val format = when (mimeType) {
            "image/jpeg" -> Bitmap.CompressFormat.JPEG
            "image/png" -> Bitmap.CompressFormat.PNG
            else -> throw IllegalArgumentException("unsupported capture image MIME type")
        }
        val bitmap = image.toBitmap()
        val output = ByteArrayOutputStream()
        check(bitmap.compress(format, quality, output)) { "capture image encoding failed" }
        bitmap.recycle()
        return "data:$mimeType;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun CaptureImage.toBitmap() = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    private fun Bitmap.toCaptureImageAndRecycle(): CaptureImage {
        val argb = IntArray(width * height)
        getPixels(argb, 0, width, 0, 0, width, height)
        val result = CaptureImage(width, height, argb)
        recycle()
        return result
    }
}

internal data class CaptureRect(val x: Int, val y: Int, val width: Int, val height: Int)

internal data class RemoteCapturePackage(
    val width: Int,
    val height: Int,
    val seedJpeg: ByteArray,
    val previewJpeg: ByteArray,
    val alphaPng: ByteArray,
)

/** Strict, bounded decoder for the fixed remote-render-v1 capture archive. */
internal object RemoteCaptureZipParser {
    private val memberNames = setOf("manifest.json", "seed.jpg", "preview.jpg", "alpha.png")
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    internal const val MAX_EXPANDED_BYTES = 64 * 1024 * 1024

    fun parse(
        body: ByteArray,
        expectedSessionId: String,
        expectedRequestId: Long,
        expectedWidth: Int,
        expectedHeight: Int,
        maxExpandedBytes: Int = MAX_EXPANDED_BYTES,
    ): RemoteCapturePackage {
        require(body.isNotEmpty()) { "capture ZIP is empty" }
        require(maxExpandedBytes > 0) { "expanded ZIP limit must be positive" }
        val members = linkedMapOf<String, ByteArray>()
        var expanded = 0L
        ZipInputStream(ByteArrayInputStream(body)).use { archive ->
            while (true) {
                val entry = archive.nextEntry ?: break
                require(!entry.isDirectory && entry.name in memberNames) { "capture ZIP has unexpected member" }
                require(entry.name !in members) { "capture ZIP has duplicate member" }
                if (entry.size >= 0) require(expanded + entry.size <= maxExpandedBytes.toLong()) {
                    "capture ZIP exceeds expanded size limit"
                }
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = archive.read(buffer)
                    if (count < 0) break
                    expanded += count
                    require(expanded <= maxExpandedBytes.toLong()) { "capture ZIP exceeds expanded size limit" }
                    if (entry.name == "manifest.json") require(output.size() + count <= MAX_MANIFEST_BYTES) {
                        "capture manifest is too large"
                    }
                    output.write(buffer, 0, count)
                }
                members[entry.name] = output.toByteArray()
                archive.closeEntry()
            }
        }
        require(members.keys == memberNames) { "capture ZIP members do not match protocol" }
        val manifest = try {
            JSONObject(members.getValue("manifest.json").toString(StandardCharsets.UTF_8))
        } catch (failure: Exception) {
            throw IllegalArgumentException("capture manifest is invalid JSON", failure)
        }
        require(manifest.keys().asSequence().toSet() == setOf("protocol", "sessionId", "requestId", "width", "height", "members")) {
            "capture manifest has invalid fields"
        }
        require(manifest.opt("protocol") is String && manifest.getString("protocol") == RemoteRenderProtocol.VERSION)
        require(manifest.opt("sessionId") is String && manifest.getString("sessionId") == expectedSessionId) {
            "capture session does not match"
        }
        require(manifest.strictLong("requestId") == expectedRequestId) {
            "capture request does not match"
        }
        require(manifest.strictInt("width") == expectedWidth)
        require(manifest.strictInt("height") == expectedHeight)
        val roles = manifest.opt("members") as? JSONObject ?: throw IllegalArgumentException("capture member map is invalid")
        require(roles.keys().asSequence().toSet() == setOf("seed", "preview", "alpha"))
        require(roles.opt("seed") == "seed.jpg" && roles.opt("preview") == "preview.jpg" && roles.opt("alpha") == "alpha.png") {
            "capture member map does not match protocol"
        }
        val seed = members.getValue("seed.jpg")
        val preview = members.getValue("preview.jpg")
        val alpha = members.getValue("alpha.png")
        require(jpegDimensions(seed) == expectedWidth to expectedHeight) { "seed JPEG dimensions do not match frame" }
        require(jpegDimensions(preview) == expectedWidth to expectedHeight) { "preview JPEG dimensions do not match frame" }
        require(pngDimensions(alpha) == expectedWidth to expectedHeight) { "alpha PNG dimensions do not match frame" }
        return RemoteCapturePackage(expectedWidth, expectedHeight, seed, preview, alpha)
    }

    private fun jpegDimensions(bytes: ByteArray): Pair<Int, Int> {
        require(bytes.size >= 4 && u8(bytes, 0) == 0xff && u8(bytes, 1) == 0xd8) { "capture RGB member is not JPEG" }
        var offset = 2
        while (offset + 1 < bytes.size) {
            require(u8(bytes, offset) == 0xff) { "invalid JPEG marker" }
            while (offset < bytes.size && u8(bytes, offset) == 0xff) offset++
            require(offset < bytes.size) { "truncated JPEG marker" }
            val marker = u8(bytes, offset++)
            if (marker == 0xd9 || marker == 0xda) break
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            require(offset + 1 < bytes.size) { "truncated JPEG segment" }
            val length = (u8(bytes, offset) shl 8) or u8(bytes, offset + 1)
            require(length >= 2 && offset + length <= bytes.size) { "invalid JPEG segment" }
            if (marker in setOf(0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf)) {
                require(length >= 7) { "invalid JPEG dimensions" }
                val height = (u8(bytes, offset + 3) shl 8) or u8(bytes, offset + 4)
                val width = (u8(bytes, offset + 5) shl 8) or u8(bytes, offset + 6)
                require(width > 0 && height > 0) { "invalid JPEG dimensions" }
                return width to height
            }
            offset += length
        }
        throw IllegalArgumentException("JPEG dimensions are missing")
    }

    private fun pngDimensions(bytes: ByteArray): Pair<Int, Int> {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        require(bytes.size >= 33 && bytes.copyOfRange(0, 8).contentEquals(signature)) { "alpha member is not lossless PNG" }
        require(readInt(bytes, 8) == 13 && bytes.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(StandardCharsets.US_ASCII))) {
            "alpha PNG is missing IHDR"
        }
        val width = readInt(bytes, 16)
        val height = readInt(bytes, 20)
        require(width > 0 && height > 0) { "invalid alpha PNG dimensions" }
        return width to height
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        (u8(bytes, offset) shl 24) or (u8(bytes, offset + 1) shl 16) or
            (u8(bytes, offset + 2) shl 8) or u8(bytes, offset + 3)

    private fun u8(bytes: ByteArray, offset: Int) = bytes[offset].toInt() and 0xff

    private fun JSONObject.strictLong(key: String): Long = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException("capture $key must be an integer")
    }

    private fun JSONObject.strictInt(key: String): Int = when (val value = opt(key)) {
        is Int -> value
        is Long -> value.toInt().takeIf { it.toLong() == value }
            ?: throw IllegalArgumentException("capture $key is out of range")
        else -> throw IllegalArgumentException("capture $key must be an integer")
    }
}

/** The single source of truth for local and remote release masks and data URLs. */
internal class ReleaseCaptureBuilder(
    private val codec: ReleaseCaptureImageCodec = AndroidReleaseCaptureImageCodec,
) {
    fun buildLocal(
        assetName: String,
        seedRgbaBottomUp: ByteArray,
        previewRgbaBottomUp: ByteArray,
        renderWidth: Int,
        renderHeight: Int,
        captureRect: CaptureRect,
    ): ReleaseCapture {
        require(renderWidth > 0 && renderHeight > 0)
        require(seedRgbaBottomUp.size == renderWidth * renderHeight * 4)
        require(previewRgbaBottomUp.size == seedRgbaBottomUp.size)
        require(captureRect.x >= 0 && captureRect.y >= 0 && captureRect.width > 0 && captureRect.height > 0)
        require(captureRect.x + captureRect.width <= renderWidth && captureRect.y + captureRect.height <= renderHeight)
        val total = captureRect.width * captureRect.height
        val seed = IntArray(total)
        val preview = IntArray(total)
        val coverage = ByteArray(total)
        for (y in 0 until captureRect.height) {
            val sourceY = renderHeight - 1 - (captureRect.y + y)
            for (x in 0 until captureRect.width) {
                val destination = y * captureRect.width + x
                val source = (sourceY * renderWidth + captureRect.x + x) * 4
                seed[destination] = opaque(seedRgbaBottomUp, source)
                preview[destination] = opaque(previewRgbaBottomUp, source)
                coverage[destination] = seedRgbaBottomUp[source + 3]
            }
        }
        return build(assetName, CaptureImage(captureRect.width, captureRect.height, seed),
            CaptureImage(captureRect.width, captureRect.height, preview), coverage)
    }

    fun buildRemote(assetName: String, capture: RemoteCapturePackage): ReleaseCapture {
        val seed = codec.decode(capture.seedJpeg)
        val preview = codec.decode(capture.previewJpeg)
        val alpha = codec.decode(capture.alphaPng)
        require(seed.width == capture.width && seed.height == capture.height)
        require(preview.width == capture.width && preview.height == capture.height)
        require(alpha.width == capture.width && alpha.height == capture.height)
        val coverage = ByteArray(alpha.pixels.size) { index -> ((alpha.pixels[index] ushr 16) and 0xff).toByte() }
        return build(assetName, seed, preview, coverage)
    }

    internal fun build(
        assetName: String,
        seed: CaptureImage,
        preview: CaptureImage,
        coverage: ByteArray,
    ): ReleaseCapture {
        require(assetName.isNotBlank()) { "capture asset name must not be blank" }
        require(seed.width == preview.width && seed.height == preview.height)
        require(coverage.size == seed.pixels.size)
        val width = seed.width
        val height = seed.height
        val rawGap = ByteArray(coverage.size)
        for (index in coverage.indices) {
            if (255 - (coverage[index].toInt() and 0xff) > MASK_TOLERANCE) rawGap[index] = 1
        }
        val gap = cleanGapMask(rawGap, width, height)
        val peripheral = floodBorder(gap, width, height)
        val refine = ByteArray(gap.size)
        var gapPx = 0
        var peripheralPx = 0
        var interiorPx = 0
        for (index in gap.indices) {
            if (gap[index].toInt() != 0) gapPx++
            if (peripheral[index].toInt() != 0) peripheralPx++ else {
                refine[index] = 1
                if (gap[index].toInt() != 0) interiorPx++
            }
        }
        val scale = min(1f, RELEASE_MAX_SIDE.toFloat() / max(width, height).toFloat())
        val outputWidth = max(1, (width * scale).toInt())
        val outputHeight = max(1, (height * scale).toInt())
        val scaledSeed = codec.scale(seed, outputWidth, outputHeight, true)
        val scaledPreview = codec.scale(preview, outputWidth, outputHeight, true)
        val refineImage = codec.scale(maskImage(refine, width, height), outputWidth, outputHeight, false)
        val peripheralImage = codec.scale(maskImage(peripheral, width, height), outputWidth, outputHeight, false)
        return ReleaseCapture(
            assetName = assetName,
            renderWidth = width,
            renderHeight = height,
            width = outputWidth,
            height = outputHeight,
            seedDataUrl = codec.dataUrl(scaledSeed, "image/jpeg", JPEG_QUALITY),
            previewDataUrl = codec.dataUrl(scaledPreview, "image/jpeg", JPEG_QUALITY),
            refineMaskDataUrl = codec.dataUrl(refineImage, "image/png", 100),
            peripheralMaskDataUrl = codec.dataUrl(peripheralImage, "image/png", 100),
            gapPx = gapPx,
            peripheralPx = peripheralPx,
            interiorPx = interiorPx,
            coveredPx = gap.size - gapPx,
            alphaThreshold = MASK_TOLERANCE,
            releaseMaxSide = RELEASE_MAX_SIDE,
        )
    }

    internal fun cleanGapMask(raw: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && raw.size == width * height)
        val referenceScale = max(width, height).toFloat() / RELEASE_MAX_SIDE.toFloat()
        val firstArea = Math.round(80f * referenceScale * referenceScale)
        val dilations = max(1, Math.round(2f * referenceScale))
        val erosions = max(1, Math.round(referenceScale))
        val secondArea = Math.round(120f * referenceScale * referenceScale)
        var result = removeSmallComponents(raw, width, height, firstArea)
        result = dilate4(result, width, height, dilations)
        result = erode4(result, width, height, erosions)
        return removeSmallComponents(result, width, height, secondArea)
    }

    internal fun floodBorder(mask: ByteArray, width: Int, height: Int): ByteArray {
        require(width > 0 && height > 0 && mask.size == width * height)
        val output = ByteArray(mask.size)
        val queue = IntArray(mask.size)
        var head = 0
        var tail = 0
        fun add(index: Int) {
            if (index in mask.indices && mask[index].toInt() != 0 && output[index].toInt() == 0) {
                output[index] = 1
                queue[tail++] = index
            }
        }
        for (x in 0 until width) { add(x); add((height - 1) * width + x) }
        for (y in 0 until height) { add(y * width); add(y * width + width - 1) }
        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            add(index - width); add(index + width)
            if (x > 0) add(index - 1)
            if (x < width - 1) add(index + 1)
        }
        return output
    }

    private fun dilate4(mask: ByteArray, width: Int, height: Int, iterations: Int): ByteArray {
        var current = mask
        repeat(iterations) {
            val output = current.copyOf()
            for (index in current.indices) {
                if (current[index].toInt() == 0) continue
                val x = index % width
                if (index >= width) output[index - width] = 1
                if (index < current.size - width) output[index + width] = 1
                if (x > 0) output[index - 1] = 1
                if (x < width - 1) output[index + 1] = 1
            }
            current = output
        }
        return current
    }

    private fun erode4(mask: ByteArray, width: Int, height: Int, iterations: Int): ByteArray {
        val inverse = ByteArray(mask.size) { if (mask[it].toInt() != 0) 0 else 1 }
        val grown = dilate4(inverse, width, height, iterations)
        return ByteArray(mask.size) { if (grown[it].toInt() != 0) 0 else 1 }
    }

    private fun removeSmallComponents(mask: ByteArray, width: Int, height: Int, minArea: Int): ByteArray {
        if (minArea <= 1) return mask.copyOf()
        val seen = ByteArray(mask.size)
        val output = ByteArray(mask.size)
        val queue = IntArray(mask.size)
        val component = IntArray(mask.size)
        for (start in mask.indices) {
            if (mask[start].toInt() == 0 || seen[start].toInt() != 0) continue
            var head = 0; var tail = 0; var componentLength = 0
            queue[tail++] = start; seen[start] = 1
            while (head < tail) {
                val index = queue[head++]
                component[componentLength++] = index
                val x = index % width
                fun add(candidate: Int) {
                    if (candidate in mask.indices && mask[candidate].toInt() != 0 && seen[candidate].toInt() == 0) {
                        seen[candidate] = 1; queue[tail++] = candidate
                    }
                }
                add(index - width); add(index + width)
                if (x > 0) add(index - 1)
                if (x < width - 1) add(index + 1)
            }
            if (componentLength >= minArea) for (index in 0 until componentLength) output[component[index]] = 1
        }
        return output
    }

    private fun maskImage(mask: ByteArray, width: Int, height: Int) = CaptureImage(
        width, height, IntArray(mask.size) { if (mask[it].toInt() != 0) -0x1 else -0x1000000 },
    )

    private fun opaque(bytes: ByteArray, offset: Int): Int = -0x1000000 or
        ((bytes[offset].toInt() and 0xff) shl 16) or ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        (bytes[offset + 2].toInt() and 0xff)

    companion object {
        const val MASK_TOLERANCE = 8
        const val RELEASE_MAX_SIDE = 960
        const val JPEG_QUALITY = 90
    }
}
