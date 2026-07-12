package com.wici.androidalbumdemo.scene

interface MediaCatalog { suspend fun scan(): List<MediaAsset> }
interface MetadataReader { suspend fun enrich(asset: MediaAsset): MediaAsset }
interface KeyframeExtractor { suspend fun extract(video: MediaAsset, config: DiscoveryConfig): List<ImageCandidate> }
interface VisualAnalyzer {
    fun describe(image: NormalizedImage): VisualDescriptor
    fun assessQuality(image: NormalizedImage, config: DiscoveryConfig): QualityAssessment
}
interface SceneRepository {
    suspend fun load(): PersistedSceneState?
    suspend fun save(state: PersistedSceneState)
}
interface ReconstructionGateway { suspend fun submit(manifest: ReconstructionManifest, consent: ConsentGrant): String }

data class ReconstructionTarget(val id: String, val displayName: String)
data class ConsentGrant(val target: ReconstructionTarget, val manifest: ReconstructionManifest, val token: String)
interface ConsentPolicy { fun authorize(target: ReconstructionTarget, manifest: ReconstructionManifest): ConsentGrant }
