package com.wici.androidalbumdemo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReleaseCaptureBuilderTest {
    @Test fun syntheticCoverageClassifiesBoundaryAndInteriorAndRemovesNoise() {
        val fixture = JSONObject(
            checkNotNull(javaClass.classLoader?.getResourceAsStream("release-capture-alpha-fixture.json"))
                .bufferedReader().use { it.readText() },
        )
        val width = fixture.getInt("width")
        val height = fixture.getInt("height")
        fun coverage(includeNoise: Boolean): ByteArray {
            val result = ByteArray(width * height) { 255.toByte() }
            fun clearRect(values: org.json.JSONArray) {
                val x0 = values.getInt(0); val y0 = values.getInt(1)
                for (y in y0 until y0 + values.getInt(3)) for (x in x0 until x0 + values.getInt(2)) {
                    result[y * width + x] = 0
                }
            }
            clearRect(fixture.getJSONArray("boundaryHole"))
            clearRect(fixture.getJSONArray("interiorHole"))
            if (includeNoise) {
                val noise = fixture.getJSONArray("smallNoise")
                result[noise.getInt(1) * width + noise.getInt(0)] = 0
            }
            return result
        }
        val codec = FakeCodec()
        val builder = ReleaseCaptureBuilder(codec)
        val image = CaptureImage(width, height, IntArray(width * height) { -0x778899 })
        val withNoise = builder.build("fixture", image, image, coverage(true))
        val withoutNoise = builder.build("fixture", image, image, coverage(false))

        assertTrue(withNoise.peripheralPx > 0)
        assertTrue(withNoise.interiorPx > 0)
        assertEquals(withoutNoise.gapPx, withNoise.gapPx)
        assertEquals(width * height - withNoise.gapPx, withNoise.coveredPx)
        assertEquals(ReleaseCaptureBuilder.MASK_TOLERANCE, withNoise.alphaThreshold)
        assertEquals(ReleaseCaptureBuilder.RELEASE_MAX_SIDE, withNoise.releaseMaxSide)
        assertTrue(withNoise.seedDataUrl.startsWith("data:image/jpeg;base64,"))
        assertTrue(withNoise.refineMaskDataUrl.startsWith("data:image/png;base64,"))
    }

    @Test fun localRgbaUsesBottomUpCropAndSameCoverageBuilder() {
        val codec = FakeCodec()
        val builder = ReleaseCaptureBuilder(codec)
        val seed = ByteArray(3 * 2 * 4)
        val preview = ByteArray(seed.size)
        for (pixel in 0 until 6) {
            seed[pixel * 4] = pixel.toByte()
            seed[pixel * 4 + 3] = 255.toByte()
            preview[pixel * 4 + 1] = (pixel + 10).toByte()
        }
        val capture = builder.buildLocal("local", seed, preview, 3, 2, CaptureRect(1, 0, 2, 2))
        assertEquals(2, capture.renderWidth)
        assertEquals(2, capture.renderHeight)
        assertEquals(listOf(4, 5, 1, 2), codec.encoded.first().pixels.map { (it ushr 16) and 0xff })
        assertEquals(0, capture.gapPx)
    }

    @Test fun remoteRgbAndAlphaUseTheSharedBuilder() {
        val width = 32
        val height = 24
        val seed = CaptureImage(width, height, IntArray(width * height) { -0x1020304 })
        val preview = CaptureImage(width, height, IntArray(width * height) { -0x5060708 })
        val alphaPixels = IntArray(width * height) { -0x1 }
        for (y in 6 until 14) for (x in 0 until 8) alphaPixels[y * width + x] = -0x1000000
        val codec = FakeCodec(ArrayDeque(listOf(seed, preview, CaptureImage(width, height, alphaPixels))))
        val capture = ReleaseCaptureBuilder(codec).buildRemote(
            "remote", RemoteCapturePackage(width, height, byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)),
        )
        assertTrue(capture.peripheralPx > 0)
        assertEquals(capture.gapPx, capture.peripheralPx)
        assertEquals(0, capture.interiorPx)
        assertEquals(4, codec.encoded.size)
    }

    @Test fun captureZipChecksIdentityMembersDimensionsAndLosslessAlpha() {
        val valid = captureZip()
        val parsed = RemoteCaptureZipParser.parse(valid, SESSION, 7, 32, 24)
        assertEquals(32, parsed.width)
        assertTrue(parsed.alphaPng.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE))

        assertFails { RemoteCaptureZipParser.parse(valid, SESSION, 8, 32, 24) }
        assertFails { RemoteCaptureZipParser.parse(captureZip(omit = "preview.jpg"), SESSION, 7, 32, 24) }
        assertFails { RemoteCaptureZipParser.parse(captureZip(alpha = jpeg(32, 24)), SESSION, 7, 32, 24) }
        assertFails { RemoteCaptureZipParser.parse(captureZip(seed = jpeg(31, 24)), SESSION, 7, 32, 24) }
        assertFails { RemoteCaptureZipParser.parse(captureZip(requestId = 7.5), SESSION, 7, 32, 24) }
    }

    @Test fun captureZipEnforcesExpandedSizeLimitWhileStreaming() {
        val oversized = captureZip(extraManifestPadding = 2048)
        assertFails { RemoteCaptureZipParser.parse(oversized, SESSION, 7, 32, 24, maxExpandedBytes = 1024) }
    }

    @Test fun captureGateRejectsCaptureAfterNewInteraction() {
        val gate = RemoteCaptureGate()
        gate.beginInteraction()
        val requested = gate.snapshot()
        assertTrue(gate.accept(requested))
        gate.beginInteraction()
        assertTrue(!gate.accept(requested))
        gate.close()
        assertTrue(!gate.accept(gate.snapshot()))
    }

    private fun captureZip(
        seed: ByteArray = jpeg(32, 24),
        alpha: ByteArray = png(32, 24),
        omit: String? = null,
        extraManifestPadding: Int = 0,
        requestId: Any = 7,
    ): ByteArray {
        val manifest = JSONObject()
            .put("protocol", RemoteRenderProtocol.VERSION)
            .put("sessionId", SESSION)
            .put("requestId", requestId)
            .put("width", 32)
            .put("height", 24)
            .put("members", JSONObject().put("seed", "seed.jpg").put("preview", "preview.jpg").put("alpha", "alpha.png"))
        if (extraManifestPadding > 0) manifest.put("padding", "x".repeat(extraManifestPadding))
        val members = linkedMapOf(
            "manifest.json" to manifest.toString().toByteArray(),
            "seed.jpg" to seed,
            "preview.jpg" to jpeg(32, 24),
            "alpha.png" to alpha,
        )
        return ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { archive ->
                for ((name, bytes) in members) if (name != omit) {
                    archive.putNextEntry(ZipEntry(name)); archive.write(bytes); archive.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun jpeg(width: Int, height: Int) = byteArrayOf(
        0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b, 0x08,
        (height ushr 8).toByte(), height.toByte(), (width ushr 8).toByte(), width.toByte(),
        0x01, 0x01, 0x11, 0x00, 0xff.toByte(), 0xd9.toByte(),
    )

    private fun png(width: Int, height: Int) = PNG_SIGNATURE + byteArrayOf(
        0x00, 0x00, 0x00, 0x0d, 0x49, 0x48, 0x44, 0x52,
        (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
        (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
        0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    private fun assertFails(block: () -> Unit) {
        try { block(); throw AssertionError("expected failure") } catch (_: IllegalArgumentException) { }
    }

    private class FakeCodec(private val decoded: ArrayDeque<CaptureImage> = ArrayDeque()) : ReleaseCaptureImageCodec {
        val encoded = mutableListOf<CaptureImage>()
        override fun decode(bytes: ByteArray): CaptureImage = decoded.removeFirst()
        override fun scale(image: CaptureImage, width: Int, height: Int, filter: Boolean): CaptureImage {
            if (image.width == width && image.height == height) return image
            return CaptureImage(width, height, IntArray(width * height) { image.pixels.first() })
        }
        override fun dataUrl(image: CaptureImage, mimeType: String, quality: Int): String {
            encoded += image
            return "data:$mimeType;base64,AA=="
        }
    }

    companion object {
        private const val SESSION = "session_12345678"
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
