package com.wici.androidalbumdemo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumRemovalsTest {
    @Test
    fun `device photo stays removed after album recreation`() {
        var saved = emptySet<String>()
        val uri = "content://media/external/images/media/42"
        AlbumRemovals(saved) { saved = it }.remove("device-42", uri)
        val reopened = AlbumRemovals(saved) { saved = it }
        assertTrue(reopened.contains("device-42", uri))
        assertFalse(reopened.contains("device-43", "content://media/external/images/media/43"))
    }

    @Test
    fun `local exclusion survives changed picker IDs and explicit Add restores it`() {
        var saved = emptySet<String>()
        val uri = "content://media/external/images/media/42"
        AlbumRemovals(saved) { saved = it }.remove("local-100-1", uri)
        val reopened = AlbumRemovals(saved) { saved = it }
        assertTrue(reopened.contains("local-200-1", uri))
        reopened.restore(uri)
        assertFalse(AlbumRemovals(saved) {}.contains("device-42", uri))
    }

    @Test
    fun `existing curated exclusions survive local removal and restore`() {
        var saved = setOf("curated-photo")
        val removals = AlbumRemovals(saved) { saved = it }
        removals.remove("device-42", "content://photos/42")
        removals.restore("content://photos/42")
        assertTrue(AlbumRemovals(saved) {}.contains("curated-photo"))
    }
}
