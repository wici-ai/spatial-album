package com.wici.androidalbumdemo

/** Lifecycle shared by local GLES and remote interactive render surfaces. */
interface InteractiveRenderSurface {
    fun resume()
    fun pause()
    fun reset()
    fun shutdown()
}
