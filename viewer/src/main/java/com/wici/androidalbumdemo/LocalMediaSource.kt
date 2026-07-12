package com.wici.androidalbumdemo

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

interface LocalMediaSource {
    fun mimeType(uri: Uri): String?
    fun open(uri: Uri): InputStream
}

class AndroidLocalMediaSource(private val context: Context) : LocalMediaSource {
    override fun mimeType(uri: Uri): String? = when (uri.scheme) {
        "file" -> if (uri.path?.endsWith(".jpg", true) == true || uri.path?.endsWith(".jpeg", true) == true) "image/jpeg" else null
        else -> context.contentResolver.getType(uri)
    }

    override fun open(uri: Uri): InputStream = when (uri.scheme) {
        "file" -> File(requireNotNull(uri.path) { "file URI has no path" }).inputStream()
        else -> requireNotNull(context.contentResolver.openInputStream(uri)) { "openInputStream returned null" }
    }
}
