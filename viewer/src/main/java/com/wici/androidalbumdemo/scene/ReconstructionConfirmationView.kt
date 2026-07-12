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
    private val confirm = Button(context).apply { text = "Confirm single-anchor SHARP reconstruction" }

    init {
        orientation = VERTICAL
        addView(target); addView(thumbnail); addView(format); addView(privacy); addView(confirm)
        confirm.isEnabled = false
    }

    fun bind(model: ReconstructionConfirmation, anchorThumbnail: Bitmap?, onConfirm: () -> Unit) {
        target.text = "Backend: ${model.target.displayName} (${model.target.kind.name.lowercase()})"
        thumbnail.setImageBitmap(anchorThumbnail)
        format.text = "Upload: ${model.anchorMimeType}; anchor ${model.manifest.anchorCandidateId}"
        privacy.text = "${model.notice} Local members: ${model.remainingLocalCount}. This is single-image SHARP, not multi-view fusion."
        confirm.isEnabled = true
        confirm.setOnClickListener { onConfirm() }
        visibility = View.VISIBLE
    }
}
