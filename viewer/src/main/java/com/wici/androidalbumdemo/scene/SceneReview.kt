package com.wici.androidalbumdemo.scene

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class MediaFingerprint(val candidateId: String, val fingerprint: String)

data class PersistedSceneState(
    val algorithmVersion: String,
    val media: List<MediaFingerprint>,
    val overrides: List<SceneOverride>,
)

enum class SceneReviewPhase { PERMISSION_REQUIRED, PARTIAL_PERMISSION, SCANNING, READY, CANCELLED, RECOVERABLE_ERROR }

data class SceneReviewState(
    val phase: SceneReviewPhase,
    val scenes: List<ReviewedScene> = emptyList(),
    val overrides: List<SceneOverride> = emptyList(),
    val message: String,
)

/** Owns local review state. Network submission deliberately is not part of this controller. */
class SceneReviewController(
    private val repository: SceneRepository,
    private val algorithmVersion: String,
) {
    private var suggestions: List<SceneSuggestion> = emptyList()
    private var fingerprints: List<MediaFingerprint> = emptyList()
    private var overrides: List<SceneOverride> = emptyList()

    var state = SceneReviewState(SceneReviewPhase.PERMISSION_REQUIRED, message = "Allow photos or videos to discover scenes locally")
        private set

    fun permission(images: Boolean, videos: Boolean) {
        state = when {
            !images && !videos -> SceneReviewState(SceneReviewPhase.PERMISSION_REQUIRED, message = "Media access denied. You can grant access and retry.")
            images.xor(videos) -> SceneReviewState(SceneReviewPhase.PARTIAL_PERMISSION, message = "Partial access: available media can still be scanned locally.")
            else -> SceneReviewState(SceneReviewPhase.SCANNING, message = "Scanning on this device…")
        }
    }

    fun scanning() { state = state.copy(phase = SceneReviewPhase.SCANNING, message = "Scanning and grouping on this device…") }
    fun cancelled() { state = state.copy(phase = SceneReviewPhase.CANCELLED, message = "Scan cancelled. Your saved corrections are safe; retry when ready.") }
    fun failed() { state = state.copy(phase = SceneReviewPhase.RECOVERABLE_ERROR, message = "Some media could not be read. Review available scenes or rescan.") }

    suspend fun accept(newSuggestions: List<SceneSuggestion>, newFingerprints: List<MediaFingerprint>) {
        suggestions = newSuggestions
        fingerprints = newFingerprints
        val saved = repository.load()
        val currentById = newFingerprints.associate { it.candidateId to it.fingerprint }
        val savedById = saved?.media.orEmpty().associate { it.candidateId to it.fingerprint }
        val suggestedIds = newSuggestions.flatMapTo(mutableSetOf()) { it.candidateIds }
        val currentIds = suggestedIds.filterTo(mutableSetOf()) { id ->
            savedById[id] == null || currentById[id] == savedById[id]
        }
        overrides = saved?.overrides.orEmpty().filter { it.referencesOnly(currentIds, newSuggestions.mapTo(mutableSetOf()) { s -> s.id }) }
        publish("${newSuggestions.size} local scenes ready. Corrections are saved on this device.")
        persist()
    }

    suspend fun edit(operation: SceneOverride) {
        overrides = overrides + operation
        publish("Correction saved locally. Undo or edit again at any time.")
        persist()
    }

    suspend fun undo() {
        if (overrides.isNotEmpty()) overrides = overrides.dropLast(1)
        publish("Last correction undone.")
        persist()
    }

    private fun publish(message: String) {
        state = SceneReviewState(SceneReviewPhase.READY, SceneOverrideReplay.replay(suggestions, overrides), overrides, message)
    }

    private suspend fun persist() = repository.save(PersistedSceneState(algorithmVersion, fingerprints, overrides))
}

private fun SceneOverride.referencesOnly(candidateIds: Set<String>, sceneIds: Set<String>): Boolean = when (this) {
    is SceneOverride.Merge -> sourceSceneId in sceneIds && targetSceneId in sceneIds
    is SceneOverride.Move -> candidateId in candidateIds
    is SceneOverride.Exclude -> candidateId in candidateIds
    is SceneOverride.Restore -> candidateId in candidateIds
    is SceneOverride.Anchor -> sceneId in sceneIds && candidateId in candidateIds
}

class AtomicJsonSceneRepository(context: Context) : SceneRepository {
    private val file = File(context.filesDir, "scene-review-state.json")

    override suspend fun load(): PersistedSceneState? = runCatching {
        if (!file.isFile) return null
        SceneStateJson.decode(file.readText(StandardCharsets.UTF_8))
    }.getOrNull()

    override suspend fun save(state: PersistedSceneState) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temp).use { output ->
            output.write(SceneStateJson.encode(state).toByteArray(StandardCharsets.UTF_8))
            output.fd.sync()
        }
        check(temp.renameTo(file) || run { temp.copyTo(file, overwrite = true); temp.delete() })
    }
}

object SceneStateJson {
    fun encode(state: PersistedSceneState): String = JSONObject()
        .put("algorithmVersion", state.algorithmVersion)
        .put("media", JSONArray(state.media.map { JSONObject().put("id", it.candidateId).put("fingerprint", it.fingerprint) }))
        .put("overrides", JSONArray(state.overrides.map(::encodeOverride)))
        .toString()

    fun decode(value: String): PersistedSceneState {
        val root = JSONObject(value)
        val media = root.getJSONArray("media").objects().map { MediaFingerprint(it.getString("id"), it.getString("fingerprint")) }
        val overrides = root.getJSONArray("overrides").objects().mapNotNull(::decodeOverride)
        return PersistedSceneState(root.getString("algorithmVersion"), media, overrides)
    }

    private fun encodeOverride(value: SceneOverride): JSONObject = when (value) {
        is SceneOverride.Merge -> JSONObject().put("type", "merge").put("source", value.sourceSceneId).put("target", value.targetSceneId)
        is SceneOverride.Move -> JSONObject().put("type", "move").put("candidate", value.candidateId).put("target", value.targetSceneId)
        is SceneOverride.Exclude -> JSONObject().put("type", "exclude").put("candidate", value.candidateId)
        is SceneOverride.Restore -> JSONObject().put("type", "restore").put("candidate", value.candidateId)
        is SceneOverride.Anchor -> JSONObject().put("type", "anchor").put("scene", value.sceneId).put("candidate", value.candidateId)
    }

    private fun decodeOverride(it: JSONObject): SceneOverride? = when (it.optString("type")) {
        "merge" -> SceneOverride.Merge(it.getString("source"), it.getString("target"))
        "move" -> SceneOverride.Move(it.getString("candidate"), it.getString("target"))
        "exclude" -> SceneOverride.Exclude(it.getString("candidate"))
        "restore" -> SceneOverride.Restore(it.getString("candidate"))
        "anchor" -> SceneOverride.Anchor(it.getString("scene"), it.getString("candidate"))
        else -> null
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
}

fun privacySafeIdentity(identity: MediaIdentity): String {
    val bytes = "${identity.volumeName}:${identity.mediaId}".toByteArray(StandardCharsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).take(6).joinToString("") { "%02x".format(it) }
}

/** Programmatic-Views scene list/detail surface; host supplies thumbnails and dispatches edits. */
class SceneReviewPanel(context: Context) : LinearLayout(context) {
    var onEdit: ((SceneOverride) -> Unit)? = null
    var onUndo: (() -> Unit)? = null
    var onReconstruct: ((ReviewedScene) -> Unit)? = null

    init { orientation = VERTICAL; setPadding(24, 24, 24, 24) }

    fun render(state: SceneReviewState, suggestions: Map<String, SceneSuggestion>) {
        removeAllViews()
        addView(TextView(context).apply { text = "Private scene discovery"; textSize = 24f; setTextColor(Color.BLACK) })
        addView(TextView(context).apply { text = state.message; textSize = 14f; setPadding(0, 8, 0, 16) })
        if (state.overrides.isNotEmpty()) addView(Button(context).apply { text = "Undo last correction"; setOnClickListener { onUndo?.invoke() } })
        state.scenes.forEach { scene ->
            val suggestion = suggestions[scene.id]
            val videoFrames = scene.candidateIds.count { '@' in it }
            val excluded = scene.excludedCandidateIds.size
            val assessment = suggestion?.reconstruction
            val card = LinearLayout(context).apply {
                orientation = VERTICAL
                setPadding(20, 18, 20, 18)
                setBackgroundColor(Color.rgb(245, 243, 238))
            }
            card.addView(TextView(context).apply {
                text = "Scene ${scene.id.takeLast(8)} · ${scene.candidateIds.size} members · $videoFrames video frames"
                textSize = 17f
            })
            card.addView(TextView(context).apply {
                text = "${assessment?.level ?: ReconstructionLevel.WEAK} · confidence ${((suggestion?.confidence ?: 0.0) * 100).toInt()}%\n" +
                    (assessment?.explanations?.joinToString(" · ") ?: "More viewpoints may improve reconstruction") +
                    "\n$excluded excluded; originals remain untouched"
            })
            scene.candidateIds.forEach { candidate ->
                val excludedNow = candidate in scene.excludedCandidateIds
                card.addView(Button(context).apply {
                    text = (if (excludedNow) "Restore " else "Exclude ") + candidate.takeLast(12)
                    setOnClickListener { onEdit?.invoke(if (excludedNow) SceneOverride.Restore(candidate) else SceneOverride.Exclude(candidate)) }
                })
                card.addView(Button(context).apply {
                    text = if (scene.anchorCandidateId == candidate) "Anchor selected" else "Use as reconstruction anchor"
                    isEnabled = !excludedNow
                    setOnClickListener { onEdit?.invoke(SceneOverride.Anchor(scene.id, candidate)) }
                })
                card.addView(Button(context).apply {
                    text = "Move to new scene"
                    setOnClickListener { onEdit?.invoke(SceneOverride.Move(candidate, "user-${candidate.hashCode().toUInt()}")) }
                })
            }
            val other = state.scenes.firstOrNull { it.id != scene.id }
            if (other != null) card.addView(Button(context).apply {
                text = "Merge with ${other.id.takeLast(8)}"
                setOnClickListener { onEdit?.invoke(SceneOverride.Merge(scene.id, other.id)) }
            })
            card.addView(Button(context).apply {
                text = "Review reconstruction upload"
                isEnabled = scene.anchorCandidateId != null && scene.anchorCandidateId !in scene.excludedCandidateIds
                setOnClickListener { onReconstruct?.invoke(scene) }
            })
            addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 })
        }
    }
}

/** Curated cached previews remain readable; local media needs a separate explicit confirmation. */
object LocalPreviewConsent {
    fun mayRequestNetwork(isLocalMedia: Boolean, explicitConsent: Boolean): Boolean = !isLocalMedia || explicitConsent
}
