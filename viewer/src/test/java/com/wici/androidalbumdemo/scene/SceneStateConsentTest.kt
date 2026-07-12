package com.wici.androidalbumdemo.scene

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.startCoroutine

class SceneStateConsentTest {
    private class MemoryRepository(var saved: PersistedSceneState? = null) : SceneRepository {
        override suspend fun load() = saved
        override suspend fun save(state: PersistedSceneState) { saved = state }
    }

    private fun suggestion(id: String, vararg candidates: String) = SceneSuggestion(
        id, candidates.toSet(), candidates.associateWith { QualityAssessment(1.0) }, 0.9,
        ReconstructionAssessment(ReconstructionLevel.GOOD, 0.8, listOf("visual and angle diversity")),
    )

    private fun runSuspend(block: suspend () -> Unit) {
        block.startCoroutine(object : kotlin.coroutines.Continuation<Unit> {
            override val context = kotlin.coroutines.EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) = result.getOrThrow()
        })
    }

    @Test fun `state survives restart and stale media operations are ignored`() {
        val repo = MemoryRepository()
        val first = SceneReviewController(repo, "visual-v1")
        runSuspend {
            first.accept(listOf(suggestion("a", "one", "two")), listOf(MediaFingerprint("one", "f1"), MediaFingerprint("two", "f2")))
            first.edit(SceneOverride.Exclude("two"))
            first.edit(SceneOverride.Anchor("a", "one"))
        }
        val restarted = SceneReviewController(repo, "visual-v1")
        runSuspend { restarted.accept(listOf(suggestion("a", "one")), listOf(MediaFingerprint("one", "f1"))) }
        assertEquals(setOf("one"), restarted.state.scenes.single().candidateIds)
        assertEquals("one", restarted.state.scenes.single().anchorCandidateId)
        assertEquals(1, restarted.state.overrides.size)
    }

    @Test fun `all discovery and editing phases make zero remote calls`() {
        var remoteCalls = 0
        val gateway = object : ReconstructionGateway {
            override suspend fun submit(manifest: ReconstructionManifest, consent: ConsentGrant): String { remoteCalls++; return "unexpected" }
        }
        val controller = SceneReviewController(MemoryRepository(), "visual-v1")
        controller.permission(true, false)
        controller.scanning()
        runSuspend {
            controller.accept(listOf(suggestion("a", "one", "two")), emptyList())
            controller.edit(SceneOverride.Exclude("two"))
            controller.undo()
        }
        controller.cancelled()
        assertNotNull(gateway)
        assertEquals(0, remoteCalls)
    }

    @Test fun `json round trips ordered overrides and corrupt override does not poison index`() {
        val state = PersistedSceneState("v1", listOf(MediaFingerprint("one", "fp")), listOf(SceneOverride.Exclude("one"), SceneOverride.Restore("one")))
        assertEquals(state, SceneStateJson.decode(SceneStateJson.encode(state)))
        val withUnknown = SceneStateJson.encode(state).replace("\"overrides\":[", "\"overrides\":[{\"type\":\"future\"},")
        assertEquals(state, SceneStateJson.decode(withUnknown))
    }

    @Test fun `local network preview is off until explicit confirmation`() {
        assertFalse(LocalPreviewConsent.mayRequestNetwork(isLocalMedia = true, explicitConsent = false))
        assertTrue(LocalPreviewConsent.mayRequestNetwork(isLocalMedia = true, explicitConsent = true))
        assertTrue(LocalPreviewConsent.mayRequestNetwork(isLocalMedia = false, explicitConsent = false))
    }
}
