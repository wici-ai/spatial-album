package com.wici.androidalbumdemo.scene

import org.junit.Assert.*
import org.junit.Test

class ReconstructionProgressTest {
    private fun confirmation(target: ConfirmedTarget): ReconstructionConfirmation {
        val scene = ReviewedScene("scene", setOf("image", "video@1000"), setOf("image"), "video@1000")
        return ReconstructionConfirmation(target, ReconstructionManifest.from(scene), "image/jpeg", 1)
    }

    @Test fun `no request occurs before explicit consent and only anchor is submitted`() {
        var requests = 0
        var uploaded: String? = null
        val policy = TargetBoundConsentPolicy { "token" }
        val target = ConfirmedTarget("lan-a", "Living room box", ReconstructionTargetKind.LAN, "http://box/orbit/ingest")
        val confirmation = confirmation(target)
        val session = ReconstructionSession(policy) { manifest, _, stage ->
            requests++; uploaded = manifest.anchorCandidateId
            stage(ReconstructionStage.Uploading(321))
            stage(ReconstructionStage.WaitingForInference)
            stage(ReconstructionStage.Streaming(4, 80))
            stage(ReconstructionStage.CacheCommit(4, 104))
            stage(ReconstructionStage.Ready(4, 104))
            object : ReconstructionRequest { override fun cancel() = Unit }
        }
        assertEquals(0, requests)
        assertTrue(session.submit(confirmation, policy.authorize(confirmation)))
        assertEquals("video@1000", uploaded)
        assertEquals(setOf("USER_EXCLUDED"), confirmation.manifest.exclusionReasons.getValue("image"))
        assertEquals(listOf("Preparing", "Uploading", "WaitingForInference", "Streaming", "CacheCommit", "Ready"), session.stages.map { it::class.simpleName })
    }

    @Test fun `target change needs new consent and cancellation disconnects request`() {
        var cancelled = false
        var requests = 0
        val policy = TargetBoundConsentPolicy { "token-${requests}" }
        val lan = ConfirmedTarget("lan", "LAN", ReconstructionTargetKind.LAN, "http://lan/orbit/ingest")
        val cloud = ConfirmedTarget("cloud", "Cloud", ReconstructionTargetKind.CLOUD, "https://cloud/orbit/ingest")
        val lanConfirmation = confirmation(lan)
        val session = ReconstructionSession(policy) { _, _, _ ->
            requests++
            object : ReconstructionRequest { override fun cancel() { cancelled = true } }
        }
        val lanConsent = policy.authorize(lanConfirmation)
        val changedSelection = ReconstructionConfirmation(
            lan,
            ReconstructionManifest.from(ReviewedScene("scene", setOf("image", "video@1000", "third"), emptySet(), "video@1000")),
            "image/jpeg",
            0,
        )
        assertFalse(session.submit(changedSelection, lanConsent))
        assertFalse(session.submit(confirmation(cloud), lanConsent))
        assertEquals(0, requests)
        assertTrue(session.submit(lanConfirmation, lanConsent))
        session.cancel()
        assertTrue(cancelled)
        assertTrue(session.stages.last() is ReconstructionStage.Cancelled)
    }

    @Test fun `failure cannot become ready`() {
        val policy = TargetBoundConsentPolicy { "t" }
        val confirmation = confirmation(ConfirmedTarget("manual", "Manual", ReconstructionTargetKind.MANUAL, "http://host/orbit/ingest"))
        val session = ReconstructionSession(policy) { _, _, stage ->
            stage(ReconstructionStage.Failed("offline"))
            object : ReconstructionRequest { override fun cancel() = Unit }
        }
        session.submit(confirmation, policy.authorize(confirmation))
        assertFalse(session.stages.any { it is ReconstructionStage.Ready })
    }
}
