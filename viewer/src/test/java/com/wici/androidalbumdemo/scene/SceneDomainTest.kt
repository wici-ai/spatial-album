package com.wici.androidalbumdemo.scene

import org.junit.Assert.*
import org.junit.Test

class SceneDomainTest {
    private fun suggestion(id: String, vararg candidates: String) = SceneSuggestion(
        id, candidates.toSet(), emptyMap(), 0.8,
        ReconstructionAssessment(ReconstructionLevel.GOOD, 0.8, listOf("fixture")),
    )

    @Test fun stableIdentityIncludesVolume() {
        assertNotEquals(MediaIdentity("external", 7), MediaIdentity("external_primary", 7))
    }

    @Test fun missingEvidenceRemainsNull() {
        val asset = MediaAsset(MediaIdentity("external", 1), MediaKind.IMAGE, "image/jpeg", 10, 10)
        assertNull(asset.capturedAtEpochMillis)
        assertNull(asset.location)
        assertNull(asset.directionDegrees)
    }

    @Test fun overridesReplayDeterministically() {
        val input = listOf(suggestion("a", "one", "two"), suggestion("b", "three"))
        val operations = listOf(
            SceneOverride.Move("two", "b"), SceneOverride.Exclude("two"),
            SceneOverride.Restore("two"), SceneOverride.Anchor("b", "two"),
            SceneOverride.Merge("a", "b"),
        )
        val first = SceneOverrideReplay.replay(input, operations)
        assertEquals(first, SceneOverrideReplay.replay(input, operations))
        assertEquals(setOf("one", "two", "three"), first.single().candidateIds)
        assertEquals("two", first.single().anchorCandidateId)
    }

    @Test fun manifestRejectsExcludedAnchor() {
        val scene = ReviewedScene("scene", setOf("a", "b"), setOf("a"), "a")
        assertThrows(IllegalArgumentException::class.java) { ReconstructionManifest.from(scene) }
    }

    @Test fun normalizedPixelsNeedNoAndroidRuntime() {
        val image = NormalizedImage(2, 1, floatArrayOf(0f, 1f))
        assertEquals(2, image.luminance.size)
    }
}
