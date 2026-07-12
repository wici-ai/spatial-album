package com.wici.androidalbumdemo.scene

import org.junit.Assert.*
import org.junit.Test

class VisualClusterAssessmentTest {
    private val analyzer = LocalVisualAnalyzer()
    private val config = DiscoveryConfig(minimumDimensionPixels = 8, clusterSimilarity = .70, strongVisualSimilarity = .80)

    private fun image(seed: Int, brightness: Float = 0f, flat: Boolean = false): NormalizedImage {
        var state = seed * 1103515245 + 12345
        val blocks = FloatArray(8 * 8) {
            state = state * 1103515245 + 12345
            ((state ushr 16) and 255) / 255f
        }
        val pixels = FloatArray(32 * 32) { i ->
            if (flat) brightness else (blocks[(i / 32) / 4 * 8 + (i % 32) / 4] * .7f + brightness).coerceIn(0f, 1f)
        }
        return NormalizedImage(32, 32, pixels)
    }

    private fun item(id: String, scene: Int, variant: Int = 0, video: Boolean = false): AnalyzedCandidate {
        val base = image(scene * 13)
        val transformed = FloatArray(base.luminance.size) { i ->
            val value = (base.luminance[i] + variant * .025f).coerceIn(0f, 1f)
            if (variant % 2 == 0) value else (value * 15).toInt() / 15f
        }
        val img = NormalizedImage(base.width, base.height, transformed)
        val identity = MediaIdentity("fixture", if (video) scene.toLong() else id.hashCode().toLong().let { kotlin.math.abs(it) })
        val asset = MediaAsset(identity, if (video) MediaKind.VIDEO else MediaKind.IMAGE, "image/jpeg", 1024, 768, capturedAtEpochMillis = scene * 60_000L)
        return AnalyzedCandidate(ImageCandidate(id, identity, if (video) variant * 1_000_000L else null), asset, analyzer.describe(img), analyzer.assessQuality(img, config))
    }

    @Test fun `visual descriptor tolerates brightness and compression-like quantization`() {
        val base = image(7)
        val bright = NormalizedImage(32, 32, FloatArray(1024) { (base.luminance[it] + .06f).coerceAtMost(1f) })
        val quantized = NormalizedImage(32, 32, FloatArray(1024) { (base.luminance[it] * 15).toInt() / 15f })
        val cropped = NormalizedImage(32, 32, FloatArray(1024) { i ->
            val x = i % 32; val y = i / 32
            base.luminance[(1 + y * 30 / 32) * 32 + 1 + x * 30 / 32]
        })
        assertTrue(LocalSceneClusterer.similarity(analyzer.describe(base), analyzer.describe(bright)) >= .82)
        assertTrue(LocalSceneClusterer.similarity(analyzer.describe(base), analyzer.describe(quantized)) >= .82)
        assertTrue(LocalSceneClusterer.similarity(analyzer.describe(base), analyzer.describe(cropped)) >= .82)
    }

    @Test fun `quality reasons are explainable and preserve candidate`() {
        val dark = analyzer.assessQuality(image(1, 0f, flat = true), config)
        val bright = analyzer.assessQuality(image(1, 1f, flat = true), config)
        assertTrue(QualityReason.UNDEREXPOSED in dark.exclusionReasons)
        assertTrue(QualityReason.OVEREXPOSED in bright.exclusionReasons)
        assertTrue(QualityReason.BLURRY in dark.exclusionReasons)
        assertTrue(QualityReason.LOW_INFORMATION in dark.exclusionReasons)
        assertFalse(dark.includedByDefault)
    }

    @Test fun `candidate graph metrics meet fixture gate and video cut does not merge unrelated scene`() {
        val positives = listOf(item("a0", 1), item("a1", 1), item("a2", 1), item("v0", 2, 0, true), item("v1", 2, 1, true))
        val negatives = listOf(item("b0", 7), item("c0", 11))
        val all = positives + negatives
        val expectedSame = setOf(setOf("a0", "a1"), setOf("a0", "a2"), setOf("a1", "a2"), setOf("v0", "v1"))
        val edges = LocalSceneClusterer.candidateEdges(all, config)
        val predicted = edges.filter { it.similarity >= config.clusterSimilarity }.map { setOf(it.firstId, it.secondId) }.toSet()
        val truePositive = predicted.count { it in expectedSame }
        val falsePositive = predicted.count { it !in expectedSame }
        val recall = truePositive.toDouble() / expectedSame.size
        val negativePrecision = if (predicted.isEmpty()) 1.0 else truePositive.toDouble() / (truePositive + falsePositive)
        println("S4_METRICS positivePairs=${expectedSame.size} negativePairs=${all.size * (all.size - 1) / 2 - expectedSame.size} recall=$recall precision=$negativePrecision predicted=$predicted")
        assertTrue("positive recall=$recall", recall >= .85)
        assertTrue("negative precision=$negativePrecision", negativePrecision >= .95)
        val clusters = LocalSceneClusterer.cluster(all, edges, config).map { it.map { c -> c.candidate.id }.toSet() }
        println("S4_CLUSTERS $clusters")
        assertTrue(clusters.none { "b0" in it && "c0" in it })
    }

    @Test fun `complete linkage prevents transitive bridge`() {
        fun descriptor(v: Float) = VisualDescriptor(FloatArray(100) { if (it < 64) v else .5f })
        fun synthetic(id: String, v: Float) = item(id, 20).copy(descriptor = descriptor(v))
        val a = synthetic("bridge-a", 0f); val b = synthetic("bridge-b", 0f); val c = synthetic("bridge-c", 1f)
        val edges = listOf(CandidateEdge(a.candidate.id, b.candidate.id, .9), CandidateEdge(b.candidate.id, c.candidate.id, .9))
        val clusters = LocalSceneClusterer.cluster(listOf(a, b, c), edges, config)
        assertEquals(2, clusters.size)
    }

    @Test fun `assessment retains excluded reasons and emits reconstruction advice`() {
        val duplicateIdentity = MediaIdentity("fixture", 99)
        val source = image(5)
        val descriptor = analyzer.describe(source)
        val quality = analyzer.assessQuality(source, config)
        val items = (0..2).map { i ->
            val asset = MediaAsset(duplicateIdentity.copy(mediaId = 99L + i), MediaKind.IMAGE, "image/jpeg", 1024, 768, capturedAtEpochMillis = 1L)
            AnalyzedCandidate(ImageCandidate("duplicate-$i", asset.identity), asset, descriptor, quality)
        }
        val scene = LocalSceneClusterer.suggestions(items, config).single()
        assertEquals(3, scene.candidateIds.size)
        assertTrue(scene.quality.values.all { QualityReason.DUPLICATE in it.exclusionReasons })
        assertTrue(scene.reconstruction.explanations.any { it.contains("duplicates") })
    }
}
