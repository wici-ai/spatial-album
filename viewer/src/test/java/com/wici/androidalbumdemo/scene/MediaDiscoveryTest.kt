package com.wici.androidalbumdemo.scene

import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.*
import org.junit.Test

class MediaDiscoveryTest {
    @Test fun `partial media grants scan only granted kinds`() {
        assertEquals(setOf(MediaKind.IMAGE), MediaPermissionPolicy.catalogKinds(MediaPermissionState(true, false, false)))
        assertEquals(setOf(MediaKind.VIDEO), MediaPermissionPolicy.catalogKinds(MediaPermissionState(false, true, false)))
        assertFalse(MediaPermissionPolicy.mayReadLocation(MediaPermissionState(true, true, false)))
    }

    @Test fun `unknown metadata remains unknown and direction wraps across north`() {
        val asset = MediaAsset(MediaIdentity("external", 7), MediaKind.IMAGE, "image/jpeg", 1, 1)
        assertNull(asset.capturedAtEpochMillis); assertNull(asset.location)
        assertEquals(359.0, normalizeDirection(359.0), 0.0)
        assertEquals(1.0, normalizeDirection(361.0), 0.0)
    }

    @Test fun `keyframe selection rejects empty and damaged quality samples and is bounded`() {
        val config = DiscoveryConfig(minimumQualityScore = .5, minimumFrameChange = .1, maximumKeyframesPerVideo = 2)
        val selected = KeyframePolicy.select(listOf(FrameScore(0, .1, 1.0), FrameScore(1, .8, 1.0), FrameScore(2, .9, .01), FrameScore(3, .7, .2)), config)
        assertEquals(listOf(1L, 3L), selected.map { it.timestampUs })
        assertTrue(KeyframePolicy.select(emptyList(), config).isEmpty())
    }

    @Test fun `cache budget evicts oldest entries`() {
        val budget = CacheBudget(10)
        assertTrue(budget.record("first", 6).isEmpty())
        assertEquals(listOf("first"), budget.record("second", 6))
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is not converted into an empty successful scan`() {
        throw CancellationException("cancelled")
    }

    @Test fun `scan result explains truncation`() {
        val image = MediaAsset(MediaIdentity("external", 1), MediaKind.IMAGE, "image/jpeg", 1, 1)
        assertTrue(ScanResult(listOf(image), imageTotal = 2, videoTotal = 0).partial)
    }
}
