package com.wici.androidalbumdemo.scene

/** Stable MediaStore identity. The volume is part of identity because row IDs are volume-local. */
data class MediaIdentity(val volumeName: String, val mediaId: Long) {
    init {
        require(volumeName.isNotBlank())
        require(mediaId >= 0)
    }
}

enum class MediaKind { IMAGE, VIDEO }

data class GeoPoint(val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0)
        require(longitude in -180.0..180.0)
    }
}

data class MediaAsset(
    val identity: MediaIdentity,
    val kind: MediaKind,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val capturedAtEpochMillis: Long? = null,
    val location: GeoPoint? = null,
    val directionDegrees: Double? = null,
    val durationMillis: Long? = null,
    val contentUri: String? = null,
    val sizeBytes: Long? = null,
    val modifiedAtEpochMillis: Long? = null,
    val focalLengthMm: Double? = null,
    val rotationDegrees: Int? = null,
) {
    init {
        require(mimeType.isNotBlank())
        require(width >= 0 && height >= 0)
        require(directionDegrees == null || directionDegrees in 0.0..<360.0)
        require(durationMillis == null || durationMillis >= 0)
        require(sizeBytes == null || sizeBytes >= 0)
        require(rotationDegrees == null || rotationDegrees in setOf(0, 90, 180, 270))
    }
}

/** A locally decodable still image, either an image asset or a frame from a video. */
data class ImageCandidate(
    val id: String,
    val source: MediaIdentity,
    val frameTimestampUs: Long? = null,
) {
    init {
        require(id.isNotBlank())
        require(frameTimestampUs == null || frameTimestampUs >= 0)
    }
}

enum class QualityReason { LOW_RESOLUTION, BLURRY, UNDEREXPOSED, OVEREXPOSED, LOW_INFORMATION, DUPLICATE }

data class QualityAssessment(
    val score: Double,
    val exclusionReasons: Set<QualityReason> = emptySet(),
) {
    init { require(score in 0.0..1.0) }
    val includedByDefault: Boolean get() = exclusionReasons.isEmpty()
}

enum class ReconstructionLevel { UNSUITABLE, WEAK, GOOD }

data class ReconstructionAssessment(
    val level: ReconstructionLevel,
    val score: Double,
    val explanations: List<String>,
) {
    init { require(score in 0.0..1.0) }
}

data class SceneSuggestion(
    val id: String,
    val candidateIds: Set<String>,
    val quality: Map<String, QualityAssessment>,
    val confidence: Double,
    val reconstruction: ReconstructionAssessment,
) {
    init {
        require(id.isNotBlank())
        require(candidateIds.isNotEmpty())
        require(quality.keys.all(candidateIds::contains))
        require(confidence in 0.0..1.0)
    }
}

sealed interface SceneOverride {
    data class Merge(val sourceSceneId: String, val targetSceneId: String) : SceneOverride
    data class Move(val candidateId: String, val targetSceneId: String) : SceneOverride
    data class Exclude(val candidateId: String) : SceneOverride
    data class Restore(val candidateId: String) : SceneOverride
    data class Anchor(val sceneId: String, val candidateId: String) : SceneOverride
}

data class ReviewedScene(
    val id: String,
    val candidateIds: Set<String>,
    val excludedCandidateIds: Set<String> = emptySet(),
    val anchorCandidateId: String? = null,
)

object SceneOverrideReplay {
    fun replay(suggestions: List<SceneSuggestion>, overrides: List<SceneOverride>): List<ReviewedScene> {
        val scenes = linkedMapOf<String, ReviewedScene>()
        suggestions.forEach { suggestion ->
            val excluded = suggestion.quality.filterValues { !it.includedByDefault }.keys
            scenes[suggestion.id] = ReviewedScene(suggestion.id, suggestion.candidateIds, excluded)
        }
        fun sceneContaining(candidateId: String) = scenes.values.firstOrNull { candidateId in it.candidateIds }
        overrides.forEach { operation ->
            when (operation) {
                is SceneOverride.Merge -> {
                    val source = scenes[operation.sourceSceneId] ?: return@forEach
                    val target = scenes[operation.targetSceneId] ?: return@forEach
                    scenes[operation.targetSceneId] = target.copy(
                        candidateIds = target.candidateIds + source.candidateIds,
                        excludedCandidateIds = target.excludedCandidateIds + source.excludedCandidateIds,
                    )
                    scenes.remove(operation.sourceSceneId)
                }
                is SceneOverride.Move -> {
                    val source = sceneContaining(operation.candidateId) ?: return@forEach
                    if (source.id == operation.targetSceneId) return@forEach
                    scenes[source.id] = source.copy(
                        candidateIds = source.candidateIds - operation.candidateId,
                        excludedCandidateIds = source.excludedCandidateIds - operation.candidateId,
                        anchorCandidateId = source.anchorCandidateId.takeUnless { it == operation.candidateId },
                    )
                    val target = scenes[operation.targetSceneId]
                    scenes[operation.targetSceneId] = (target ?: ReviewedScene(operation.targetSceneId, emptySet()))
                        .copy(candidateIds = (target?.candidateIds.orEmpty()) + operation.candidateId)
                }
                is SceneOverride.Exclude -> sceneContaining(operation.candidateId)?.let {
                    scenes[it.id] = it.copy(excludedCandidateIds = it.excludedCandidateIds + operation.candidateId)
                }
                is SceneOverride.Restore -> sceneContaining(operation.candidateId)?.let {
                    scenes[it.id] = it.copy(excludedCandidateIds = it.excludedCandidateIds - operation.candidateId)
                }
                is SceneOverride.Anchor -> scenes[operation.sceneId]?.takeIf { operation.candidateId in it.candidateIds }?.let {
                    scenes[it.id] = it.copy(anchorCandidateId = operation.candidateId)
                }
            }
        }
        return scenes.values.filter { it.candidateIds.isNotEmpty() }
    }
}

class ReconstructionManifest private constructor(
    val sceneId: String,
    val selectedCandidateIds: Set<String>,
    val excludedCandidateIds: Set<String>,
    val anchorCandidateId: String,
) {
    companion object {
        fun from(scene: ReviewedScene): ReconstructionManifest {
            val selected = scene.candidateIds - scene.excludedCandidateIds
            val anchor = requireNotNull(scene.anchorCandidateId) { "An anchor is required" }
            require(anchor in selected) { "Anchor must be a selected, non-excluded candidate" }
            return ReconstructionManifest(scene.id, selected, scene.excludedCandidateIds, anchor)
        }
    }
}

data class NormalizedImage(val width: Int, val height: Int, val luminance: FloatArray) {
    init {
        require(width > 0 && height > 0)
        require(luminance.size == width * height)
        require(luminance.all { it in 0f..1f })
    }
}

data class VisualDescriptor(val values: FloatArray)

data class DiscoveryConfig(
    val timeWindowMillis: Long = 15 * 60 * 1000L,
    val distanceThresholdMeters: Double = 75.0,
    val directionThresholdDegrees: Double = 45.0,
    val minimumQualityScore: Double = 0.45,
    val videoSampleIntervalMillis: Long = 2_000L,
    val maximumVideoSamples: Int = 60,
    val maximumImages: Int = 500,
    val maximumVideos: Int = 100,
    val maximumKeyframesPerVideo: Int = 12,
    val keyframeCacheBytes: Long = 64L * 1024 * 1024,
    val minimumFrameChange: Double = 0.08,
) {
    init {
        require(timeWindowMillis > 0 && distanceThresholdMeters > 0 && directionThresholdDegrees in 0.0..180.0)
        require(minimumQualityScore in 0.0..1.0 && videoSampleIntervalMillis > 0 && maximumVideoSamples > 0)
        require(maximumImages > 0 && maximumVideos > 0 && maximumKeyframesPerVideo > 0 && keyframeCacheBytes > 0)
        require(minimumFrameChange in 0.0..1.0)
    }
}

data class MediaPermissionState(val images: Boolean, val videos: Boolean, val location: Boolean)

object MediaPermissionPolicy {
    fun catalogKinds(state: MediaPermissionState): Set<MediaKind> = buildSet {
        if (state.images) add(MediaKind.IMAGE)
        if (state.videos) add(MediaKind.VIDEO)
    }
    fun mayReadLocation(state: MediaPermissionState): Boolean = state.location
}

data class ScanResult(val assets: List<MediaAsset>, val imageTotal: Int, val videoTotal: Int) {
    val partial: Boolean get() = assets.count { it.kind == MediaKind.IMAGE } < imageTotal ||
        assets.count { it.kind == MediaKind.VIDEO } < videoTotal
}

data class FrameScore(val timestampUs: Long, val quality: Double, val change: Double)

object KeyframePolicy {
    fun select(frames: List<FrameScore>, config: DiscoveryConfig): List<FrameScore> = frames
        .filter { it.quality >= config.minimumQualityScore }
        .fold(mutableListOf<FrameScore>()) { kept, frame ->
            if (kept.isEmpty() || frame.change >= config.minimumFrameChange) kept += frame
            kept
        }
        .sortedByDescending { it.quality }
        .take(config.maximumKeyframesPerVideo)
        .sortedBy { it.timestampUs }
}

fun normalizeDirection(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

class CacheBudget(private val maximumBytes: Long) {
    private val entries = linkedMapOf<String, Long>()
    init { require(maximumBytes > 0) }
    fun record(id: String, bytes: Long): List<String> {
        require(bytes >= 0)
        entries.remove(id); entries[id] = bytes
        val evicted = mutableListOf<String>()
        while (entries.values.sum() > maximumBytes && entries.isNotEmpty()) {
            entries.entries.first().also { entries.remove(it.key); evicted += it.key }
        }
        return evicted
    }
}
