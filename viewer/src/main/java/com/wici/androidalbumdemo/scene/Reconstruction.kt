package com.wici.androidalbumdemo.scene

import java.util.UUID

enum class ReconstructionTargetKind { LAN, MANUAL, CLOUD }

data class ConfirmedTarget(
    val id: String,
    val displayName: String,
    val kind: ReconstructionTargetKind,
    val endpoint: String,
)

data class ReconstructionConsent(
    val token: String,
    val targetId: String,
    val sceneId: String,
    val anchorCandidateId: String,
    val selectedCandidateIds: Set<String>,
)

enum class ReconstructionMode { SHARP_SINGLE_IMAGE, GSPLAT_MULTI_VIEW }

sealed interface ReconstructionStage {
    data object Preparing : ReconstructionStage
    data class Uploading(val bytes: Long) : ReconstructionStage
    data object WaitingForInference : ReconstructionStage
    data class Training(val step: Int, val totalSteps: Int, val stage: String) : ReconstructionStage
    data class Streaming(val records: Int, val bytes: Long) : ReconstructionStage
    data class CacheCommit(val records: Int, val bytes: Long) : ReconstructionStage
    data class Ready(val records: Int, val bytes: Long) : ReconstructionStage
    data class Failed(val message: String) : ReconstructionStage
    data object Cancelled : ReconstructionStage
}

data class ReconstructionConfirmation(
    val target: ConfirmedTarget,
    val manifest: ReconstructionManifest,
    val anchorMimeType: String,
    val remainingLocalCount: Int,
    val mode: ReconstructionMode = ReconstructionMode.SHARP_SINGLE_IMAGE,
    val notice: String = "Only the selected anchor is uploaded; all other scene members remain local.",
)

class TargetBoundConsentPolicy(private val tokenFactory: () -> String = { UUID.randomUUID().toString() }) {
    fun authorize(confirmation: ReconstructionConfirmation): ReconstructionConsent = ReconstructionConsent(
        tokenFactory(), confirmation.target.id, confirmation.manifest.sceneId, confirmation.manifest.anchorCandidateId,
        confirmation.manifest.selectedCandidateIds.toSet(),
    )

    fun accepts(consent: ReconstructionConsent, confirmation: ReconstructionConfirmation): Boolean =
        consent.targetId == confirmation.target.id &&
            consent.sceneId == confirmation.manifest.sceneId &&
            consent.anchorCandidateId == confirmation.manifest.anchorCandidateId &&
            consent.selectedCandidateIds == confirmation.manifest.selectedCandidateIds
}

interface ReconstructionRequest { fun cancel() }

fun interface ReconstructionRequestStarter {
    fun start(manifest: ReconstructionManifest, target: ConfirmedTarget, onStage: (ReconstructionStage) -> Unit): ReconstructionRequest
}

class ReconstructionSession(
    private val consentPolicy: TargetBoundConsentPolicy,
    private val starter: ReconstructionRequestStarter,
) {
    private var request: ReconstructionRequest? = null
    var stages: List<ReconstructionStage> = emptyList()
        private set

    fun submit(confirmation: ReconstructionConfirmation, consent: ReconstructionConsent): Boolean {
        if (!consentPolicy.accepts(consent, confirmation)) return false
        request?.cancel()
        stages = listOf(ReconstructionStage.Preparing)
        request = starter.start(confirmation.manifest, confirmation.target) { stage ->
            stages = stages + stage
        }
        return true
    }

    fun cancel() {
        request?.cancel()
        request = null
        if (stages.lastOrNull() !is ReconstructionStage.Ready) stages = stages + ReconstructionStage.Cancelled
    }
}
