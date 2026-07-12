package com.wici.androidalbumdemo.scene

import kotlin.math.*

/** Pure Kotlin, deterministic local analysis; no media bytes leave the process. */
class LocalVisualAnalyzer : VisualAnalyzer {
    override fun describe(image: NormalizedImage): VisualDescriptor {
        val l = image.luminance
        val mean = l.average().toFloat()
        val hash = FloatArray(64)
        for (y in 0 until 8) for (x in 0 until 8) {
            val sx = x * image.width / 8; val ex = max(sx + 1, (x + 1) * image.width / 8)
            val sy = y * image.height / 8; val ey = max(sy + 1, (y + 1) * image.height / 8)
            var sum = 0.0; var n = 0
            for (py in sy until min(ey, image.height)) for (px in sx until min(ex, image.width)) { sum += l[py * image.width + px]; n++ }
            hash[y * 8 + x] = if (sum / n >= mean) 1f else 0f
        }
        val hist = FloatArray(16)
        l.forEach { hist[min(15, (it * 16).toInt())]++ }
        for (i in hist.indices) hist[i] /= l.size
        val edges = FloatArray(4)
        var edgeTotal = 0f
        for (y in 1 until image.height - 1) for (x in 1 until image.width - 1) {
            val gx = l[y * image.width + x + 1] - l[y * image.width + x - 1]
            val gy = l[(y + 1) * image.width + x] - l[(y - 1) * image.width + x]
            val magnitude = sqrt(gx * gx + gy * gy)
            if (magnitude > .05f) { edges[if (abs(gx) > abs(gy)) if (gx > 0) 0 else 1 else if (gy > 0) 2 else 3] += magnitude; edgeTotal += magnitude }
        }
        if (edgeTotal > 0) for (i in edges.indices) edges[i] /= edgeTotal
        val spatial = FloatArray(16)
        for (y in 0 until 4) for (x in 0 until 4) {
            val values = mutableListOf<Float>()
            for (py in y * image.height / 4 until max(y * image.height / 4 + 1, (y + 1) * image.height / 4))
                for (px in x * image.width / 4 until max(x * image.width / 4 + 1, (x + 1) * image.width / 4))
                    if (py < image.height && px < image.width) values += l[py * image.width + px]
            spatial[y * 4 + x] = values.average().toFloat()
        }
        val color = FloatArray(24)
        image.rgb?.let { rgb ->
            for (pixel in 0 until image.width * image.height) for (channel in 0..2) {
                color[channel * 8 + min(7, (rgb[pixel * 3 + channel] * 8).toInt())]++
            }
            for (i in color.indices) color[i] /= image.width * image.height
        }
        return VisualDescriptor(hash + hist + edges + spatial + color)
    }

    override fun assessQuality(image: NormalizedImage, config: DiscoveryConfig): QualityAssessment {
        val l = image.luminance
        val dark = l.count { it < .08f }.toDouble() / l.size
        val bright = l.count { it > .92f }.toDouble() / l.size
        var gradients = 0.0; var n = 0
        for (y in 1 until image.height) for (x in 1 until image.width) {
            gradients += abs(l[y * image.width + x] - l[y * image.width + x - 1]) + abs(l[y * image.width + x] - l[(y - 1) * image.width + x]); n += 2
        }
        val sharpness = if (n == 0) 0.0 else gradients / n
        val bins = IntArray(16); l.forEach { bins[min(15, (it * 16).toInt())]++ }
        val entropy = bins.filter { it > 0 }.sumOf { val p = it.toDouble() / l.size; -p * ln(p) } / ln(16.0)
        val reasons = buildSet {
            if (min(image.width, image.height) < config.minimumDimensionPixels) add(QualityReason.LOW_RESOLUTION)
            if (sharpness < config.blurThreshold) add(QualityReason.BLURRY)
            if (dark >= config.exposureFractionThreshold) add(QualityReason.UNDEREXPOSED)
            if (bright >= config.exposureFractionThreshold) add(QualityReason.OVEREXPOSED)
            if (entropy < config.informationThreshold) add(QualityReason.LOW_INFORMATION)
        }
        val score = (1.0 - reasons.size * .18).coerceIn(0.0, 1.0)
        return QualityAssessment(score, reasons)
    }
}

data class AnalyzedCandidate(val candidate: ImageCandidate, val asset: MediaAsset, val descriptor: VisualDescriptor, val quality: QualityAssessment)
data class CandidateEdge(val firstId: String, val secondId: String, val similarity: Double)
data class DiscoveryMetrics(val positivePairs: Int, val negativePairs: Int, val positiveRecall: Double, val negativePrecision: Double)

object LocalSceneClusterer {
    fun similarity(a: VisualDescriptor, b: VisualDescriptor): Double {
        require(a.values.size == b.values.size)
        // A raw pHash agreement has a 0.5 chance baseline. Likewise, histograms of
        // unrelated photographs often look close merely because their global tone
        // distributions match. Remove those baselines instead of letting unrelated
        // media start near the clustering threshold.
        val hashAgreement = (0 until 64).count { a.values[it] == b.values[it] } / 64.0
        val calibratedHash = ((hashAgreement - .5) * 2).coerceIn(0.0, 1.0)
        val spatialCorrelation = centeredCorrelation(a.values, b.values, 84..99)
        return (.80 * calibratedHash + .20 * spatialCorrelation).coerceIn(0.0, 1.0)
    }

    private fun centeredCorrelation(a: FloatArray, b: FloatArray, range: IntRange): Double {
        val meanA = range.sumOf { a[it].toDouble() } / range.count()
        val meanB = range.sumOf { b[it].toDouble() } / range.count()
        var dot = 0.0; var normA = 0.0; var normB = 0.0
        range.forEach {
            val x = a[it] - meanA; val y = b[it] - meanB
            dot += x * y; normA += x * x; normB += y * y
        }
        if (normA == 0.0 || normB == 0.0) return if (normA == normB) 1.0 else 0.0
        return ((dot / sqrt(normA * normB)) + 1.0) / 2.0
    }

    fun candidateEdges(items: List<AnalyzedCandidate>, config: DiscoveryConfig): List<CandidateEdge> {
        val result = mutableListOf<CandidateEdge>()
        items.forEachIndexed { i, a ->
            val neighbors = mutableListOf<CandidateEdge>()
            for (j in i + 1 until items.size) {
                val b = items[j]; val visual = similarity(a.descriptor, b.descriptor)
                val sameVideo = a.asset.kind == MediaKind.VIDEO && a.asset.identity == b.asset.identity
                val timeNear = a.asset.capturedAtEpochMillis?.let { at -> b.asset.capturedAtEpochMillis?.let { abs(at - it) <= config.timeWindowMillis } } == true
                val spaceNear = a.asset.location?.let { x -> b.asset.location?.let { haversine(x, it) <= config.distanceThresholdMeters } } == true
                if (sameVideo || timeNear || spaceNear || visual >= config.strongVisualSimilarity) neighbors += CandidateEdge(a.candidate.id, b.candidate.id, visual)
            }
            result += neighbors.sortedByDescending { it.similarity }.take(config.maximumCandidateNeighbors)
        }
        return result
    }

    fun cluster(items: List<AnalyzedCandidate>, edges: List<CandidateEdge>, config: DiscoveryConfig): List<List<AnalyzedCandidate>> {
        val byId = items.associateBy { it.candidate.id }; val clusters = items.map { mutableListOf(it) }.toMutableList()
        edges.sortedByDescending { it.similarity }.forEach { edge ->
            val left = clusters.firstOrNull { byId[edge.firstId] in it } ?: return@forEach
            val right = clusters.firstOrNull { byId[edge.secondId] in it } ?: return@forEach
            if (left === right) return@forEach
            val merged = left + right
            val medoid = merged.maxBy { m -> merged.sumOf { similarity(m.descriptor, it.descriptor) } }
            val consistent = merged.all { similarity(medoid.descriptor, it.descriptor) >= config.clusterSimilarity } &&
                left.all { a -> right.all { b -> similarity(a.descriptor, b.descriptor) >= config.clusterSimilarity } }
            if (consistent) { left += right; clusters.remove(right) }
        }
        return clusters.map { it.toList() }
    }

    fun suggestions(items: List<AnalyzedCandidate>, config: DiscoveryConfig): List<SceneSuggestion> {
        val clusters = cluster(items, candidateEdges(items, config), config)
        return clusters.mapIndexed { index, members ->
            val quality = members.associate { item ->
                val duplicate = members.any { it !== item && similarity(item.descriptor, it.descriptor) >= config.duplicateSimilarity }
                item.candidate.id to if (duplicate) item.quality.copy(exclusionReasons = item.quality.exclusionReasons + QualityReason.DUPLICATE) else item.quality
            }
            val good = quality.values.count { it.includedByDefault }
            val visualDiversity = if (members.size < 2) 0.0 else 1 - members.flatMapIndexed { i, a -> members.drop(i + 1).map { similarity(a.descriptor, it.descriptor) } }.average()
            val directions = members.mapNotNull { it.asset.directionDegrees }
            val angularDiversity = directions.flatMapIndexed { i, a -> directions.drop(i + 1).map { b -> min(abs(a - b), 360 - abs(a - b)) } }.maxOrNull() ?: 0.0
            val locationEvidence = members.count { it.asset.location != null }
            val timeEvidence = members.count { it.asset.capturedAtEpochMillis != null }
            val diversity = max(visualDiversity, (angularDiversity / 180.0).coerceIn(0.0, 1.0))
            val score = (.2 + good.coerceAtMost(5) * .12 + diversity * .3).coerceIn(0.0, 1.0)
            val level = when { good >= 4 && diversity >= .08 -> ReconstructionLevel.GOOD; good >= 2 -> ReconstructionLevel.WEAK; else -> ReconstructionLevel.UNSUITABLE }
            val explanations = buildList {
                add("$good quality views")
                add("${quality.values.count { QualityReason.DUPLICATE in it.exclusionReasons }} duplicates")
                add("time evidence $timeEvidence/${members.size}")
                add("location evidence $locationEvidence/${members.size}")
                if (directions.isNotEmpty()) add("viewpoint span ${angularDiversity.roundToInt()} degrees")
                if (diversity < .08) add("Capture more varied viewpoints")
            }
            SceneSuggestion("local-scene-$index", members.map { it.candidate.id }.toSet(), quality, members.map { it.quality.score }.average(), ReconstructionAssessment(level, score, explanations))
        }
    }

    private fun haversine(a: GeoPoint, b: GeoPoint): Double {
        val lat = Math.toRadians(b.latitude - a.latitude); val lon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
        return 6371000 * 2 * asin(sqrt(h))
    }
}
