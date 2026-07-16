package com.wici.androidalbumdemo

internal enum class BackendSource { MANUAL, ACTIVATED_BOX, NSD, CLOUD }

internal data class BackendSessionChoice(
    val baseUrl: String,
    val source: BackendSource
)

internal object BackendSessionPolicy {
    fun choose(
        manualUrl: String?,
        activatedBoxUrl: String?,
        healthyNsdUrl: String?,
        cloudUrl: String
    ): BackendSessionChoice = when {
        manualUrl != null -> BackendSessionChoice(manualUrl, BackendSource.MANUAL)
        activatedBoxUrl != null -> BackendSessionChoice(activatedBoxUrl, BackendSource.ACTIVATED_BOX)
        healthyNsdUrl != null -> BackendSessionChoice(healthyNsdUrl, BackendSource.NSD)
        else -> BackendSessionChoice(cloudUrl, BackendSource.CLOUD)
    }

    fun canFallbackToCloud(source: BackendSource): Boolean =
        source == BackendSource.ACTIVATED_BOX || source == BackendSource.NSD
}

internal object BoxStageText {
    fun spatial(state: String?, ready: Map<String, Boolean>): String = when {
        state == "evicting" -> "Switching from Q&A..."
        ready["orbit"] != true -> "Starting Orbit..."
        ready["difix"] != true -> "Loading DiFix..."
        ready["flux"] != true -> "Loading FLUX..."
        else -> "Checking 3D server..."
    }
}

internal class RetryOncePolicy {
    private var retried = false

    fun claimCloudRetry(source: BackendSource): Boolean {
        if (retried || !BackendSessionPolicy.canFallbackToCloud(source)) return false
        retried = true
        return true
    }
}
