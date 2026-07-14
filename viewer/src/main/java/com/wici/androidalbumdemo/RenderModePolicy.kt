package com.wici.androidalbumdemo

import org.json.JSONObject

enum class RendererMode(val persistedValue: String) {
    AUTOMATIC("automatic"),
    LOCAL("local"),
    REMOTE("remote");

    companion object {
        fun fromPersisted(value: String?): RendererMode =
            entries.firstOrNull { it.persistedValue == value } ?: AUTOMATIC
    }
}

enum class InitialRendererDecision { LOCAL, REMOTE, REMOTE_UNAVAILABLE }

enum class RemoteFailureDecision { RETRY_REMOTE_ONCE, FALLBACK_LOCAL, SHOW_REMOTE_ERROR }

/** Pure, fail-closed negotiation and bounded fallback policy for MainActivity. */
object RenderModePolicy {
    const val REMOTE_PROTOCOL = "remote-render-v1"

    fun capabilitiesFromHealth(json: String): Set<String> {
        val root = JSONObject(json)
        val values = root.optJSONArray("capabilities") ?: return emptySet()
        return buildSet {
            for (index in 0 until values.length()) {
                val value = values.opt(index)
                if (value is String) add(value)
            }
        }
    }

    fun initial(mode: RendererMode, healthReachable: Boolean, capabilities: Set<String>): InitialRendererDecision =
        when (mode) {
            RendererMode.LOCAL -> InitialRendererDecision.LOCAL
            RendererMode.AUTOMATIC -> if (healthReachable && REMOTE_PROTOCOL in capabilities) {
                InitialRendererDecision.REMOTE
            } else {
                InitialRendererDecision.LOCAL
            }
            RendererMode.REMOTE -> if (healthReachable && REMOTE_PROTOCOL in capabilities) {
                InitialRendererDecision.REMOTE
            } else {
                InitialRendererDecision.REMOTE_UNAVAILABLE
            }
        }

    fun afterRemoteFailure(
        mode: RendererMode,
        retryable: Boolean,
        sessionExpired: Boolean,
        recreationAlreadyUsed: Boolean,
    ): RemoteFailureDecision = when (mode) {
        RendererMode.AUTOMATIC -> if ((retryable || sessionExpired) && !recreationAlreadyUsed) {
            RemoteFailureDecision.RETRY_REMOTE_ONCE
        } else {
            RemoteFailureDecision.FALLBACK_LOCAL
        }
        RendererMode.REMOTE -> RemoteFailureDecision.SHOW_REMOTE_ERROR
        RendererMode.LOCAL -> RemoteFailureDecision.FALLBACK_LOCAL
    }
}
