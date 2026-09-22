package com.wici.androidalbumdemo

/** Album-only exclusions. Local photos use their stable URI, not a picker-session ID. */
internal class AlbumRemovals(
    initialKeys: Set<String>,
    private val persist: (Set<String>) -> Unit
) {
    private val keys = initialKeys.toMutableSet()

    @Synchronized
    fun contains(photoId: String, localUri: String? = null): Boolean =
        photoId in keys || (localUri != null && localUri in keys)

    @Synchronized
    fun remove(photoId: String, localUri: String? = null) {
        if (keys.add(localUri ?: photoId)) persist(keys.toSet())
    }

    /** An explicit Add action allows the user to put a hidden photo back. */
    @Synchronized
    fun restore(localUri: String) {
        if (keys.remove(localUri)) persist(keys.toSet())
    }
}
