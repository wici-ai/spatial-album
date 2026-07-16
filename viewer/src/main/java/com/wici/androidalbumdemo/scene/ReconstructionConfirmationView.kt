package com.wici.androidalbumdemo.scene

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Programmatic confirmation surface. Creating/binding it never starts a network request. */
class ReconstructionConfirmationView(context: Context) : LinearLayout(context) {
    private val target = TextView(context)
    private val format = TextView(context)
    private val privacy = TextView(context)
    private val thumbnail = ImageView(context)
    private val status = TextView(context)
    private val confirm = Button(context).apply { text = "Confirm single-anchor SHARP reconstruction" }

    init {
        orientation = VERTICAL
        addView(target); addView(thumbnail); addView(format); addView(privacy); addView(status); addView(confirm)
        confirm.isEnabled = false
    }

    fun bind(model: ReconstructionConfirmation, anchorThumbnail: Bitmap?, onConfirm: () -> Unit) {
        target.text = "Backend: ${model.target.displayName} (${model.target.kind.name.lowercase()})"
        thumbnail.setImageBitmap(anchorThumbnail)
        val selectedCount = model.manifest.selectedCandidateIds.size
        if (model.mode == ReconstructionMode.GSPLAT_MULTI_VIEW) {
            format.text = "Upload: $selectedCount reviewed views; anchor ${model.manifest.anchorCandidateId}"
            privacy.text = "${model.notice} All $selectedCount selected views are uploaded to the confirmed backend; excluded media remains local."
            confirm.text = "Confirm multi-view gsplat training"
        } else {
            format.text = "Upload: ${model.anchorMimeType}; anchor ${model.manifest.anchorCandidateId}"
            privacy.text = "${model.notice} Local members: ${model.remainingLocalCount}. This is single-image SHARP, not multi-view fusion."
            confirm.text = "Confirm single-anchor SHARP reconstruction"
        }
        status.text = ""
        confirm.isEnabled = true
        confirm.setOnClickListener { onConfirm() }
        visibility = View.VISIBLE
    }
    fun showStage(stage: ReconstructionStage) {
        status.text = when (stage) {
            ReconstructionStage.Preparing -> "Preparing training upload…"
            is ReconstructionStage.Uploading -> "Uploading ${stage.bytes / 1024} KiB…"
            ReconstructionStage.WaitingForInference -> "Waiting for GPU training…"
            is ReconstructionStage.Training -> {
                val percent = if (stage.totalSteps <= 0) 0 else stage.step * 100 / stage.totalSteps
                "${stage.stage}: $percent% (${stage.step}/${stage.totalSteps})"
            }
            is ReconstructionStage.Streaming -> "Downloading result: ${stage.bytes / 1024} KiB"
            is ReconstructionStage.CacheCommit -> "Saving result locally…"
            is ReconstructionStage.Ready -> "Ready"
            is ReconstructionStage.Failed -> "Failed: ${stage.message}"
            ReconstructionStage.Cancelled -> "Cancelled"
        }
        confirm.isEnabled = stage is ReconstructionStage.Failed || stage is ReconstructionStage.Cancelled
    }

}
