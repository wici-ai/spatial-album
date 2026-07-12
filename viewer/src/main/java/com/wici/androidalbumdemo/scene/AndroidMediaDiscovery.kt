package com.wici.androidalbumdemo.scene

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

class AndroidMediaCatalog(private val resolver: ContentResolver, private val config: DiscoveryConfig) {
    fun scan(state: MediaPermissionState): ScanResult {
        val assets = mutableListOf<MediaAsset>()
        var imageTotal = 0
        var videoTotal = 0
        if (state.images) query(MediaKind.IMAGE, config.maximumImages).also { imageTotal = it.second; assets += it.first }
        if (state.videos) query(MediaKind.VIDEO, config.maximumVideos).also { videoTotal = it.second; assets += it.first }
        return ScanResult(assets, imageTotal, videoTotal)
    }

    private fun query(kind: MediaKind, limit: Int): Pair<List<MediaAsset>, Int> {
        val collection = if (kind == MediaKind.IMAGE) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED,
            if (kind == MediaKind.VIDEO) MediaStore.Video.Media.DURATION else MediaStore.MediaColumns._ID)
        val result = mutableListOf<MediaAsset>()
        var total = 0
        resolver.query(collection, projection, null, null, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val mime = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val duration = if (kind == MediaKind.VIDEO) c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
            while (c.moveToNext()) {
                total++
                if (result.size >= limit) continue
                val row = c.getLong(id)
                result += MediaAsset(MediaIdentity(MediaStore.VOLUME_EXTERNAL, row), kind, c.getString(mime) ?: "application/octet-stream",
                    c.getInt(width), c.getInt(height), c.longOrNull(taken), durationMillis = if (duration >= 0) c.longOrNull(duration) else null,
                    contentUri = Uri.withAppendedPath(collection, row.toString()).toString(), sizeBytes = c.longOrNull(size),
                    modifiedAtEpochMillis = c.longOrNull(modified)?.times(1000))
            }
        }
        return result to total
    }
    private fun android.database.Cursor.longOrNull(index: Int) = if (isNull(index)) null else getLong(index)
}

class AndroidExifMetadataReader(private val resolver: ContentResolver, private val locationAllowed: () -> Boolean) : MetadataReader {
    override suspend fun enrich(asset: MediaAsset): MediaAsset {
        val uri = asset.contentUri?.let(Uri::parse) ?: return asset
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val location = if (locationAllowed()) exif.latLong?.let { GeoPoint(it[0], it[1]) } else null
                asset.copy(capturedAtEpochMillis = parseDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)) ?: asset.capturedAtEpochMillis,
                    location = location, directionDegrees = if (locationAllowed()) exif.getAttributeDouble(ExifInterface.TAG_GPS_IMG_DIRECTION, Double.NaN).takeIf(Double::isFinite)?.let(::normalizeDirection) else null,
                    focalLengthMm = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, Double.NaN).takeIf(Double::isFinite),
                    rotationDegrees = orientation(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)))
            } ?: asset
        } catch (_: Exception) { asset }
    }
    private fun parseDate(value: String?) = try { value?.let { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() } } catch (_: Exception) { null }
    private fun orientation(value: Int) = when (value) { ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0 }
}

class KeyframeCache(private val directory: File, private val maximumBytes: Long) {
    init { directory.mkdirs() }
    fun put(id: String, bitmap: Bitmap): File {
        val file = File(directory, id.replace(Regex("[^A-Za-z0-9@._-]"), "_") + ".jpg")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        evict()
        return file
    }
    fun invalidate(identity: MediaIdentity) = directory.listFiles()?.filter { it.name.startsWith("${identity.volumeName}_${identity.mediaId}@") }?.forEach(File::delete)
    private fun evict() { directory.listFiles()?.sortedBy { it.lastModified() }?.let { files -> var bytes = files.sumOf(File::length); files.forEach { if (bytes > maximumBytes) { bytes -= it.length(); it.delete() } } } }
}

class AndroidKeyframeExtractor(private val resolver: ContentResolver, private val cache: KeyframeCache) : KeyframeExtractor {
    override suspend fun extract(video: MediaAsset, config: DiscoveryConfig): List<ImageCandidate> {
        val uri = video.contentUri?.let(Uri::parse) ?: return emptyList()
        val retriever = MediaMetadataRetriever()
        val frames = mutableListOf<Pair<FrameScore, Bitmap>>()
        try {
            resolver.openAssetFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) } ?: return emptyList()
            val durationUs = (video.durationMillis ?: 0L) * 1000L
            val intervalUs = config.videoSampleIntervalMillis * 1000L
            var previous: Bitmap? = null
            var timestamp = 0L
            repeat(config.maximumVideoSamples) {
                if (timestamp > durationUs) return@repeat
                if (Thread.currentThread().isInterrupted) throw CancellationException("keyframe scan cancelled")
                val frame = retriever.getFrameAtTime(timestamp, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val quality = frameQuality(frame)
                    val change = previous?.let { frameChange(it, frame) } ?: 1.0
                    frames += FrameScore(timestamp, quality, change) to frame
                    previous = frame
                }
                timestamp += intervalUs
            }
            val selected = KeyframePolicy.select(frames.map { it.first }, config).associateBy { it.timestampUs }
            return frames.mapNotNull { (score, bitmap) -> selected[score.timestampUs]?.let {
                val id = "${video.identity.volumeName}_${video.identity.mediaId}@${score.timestampUs}"
                cache.put(id, bitmap); ImageCandidate(id, video.identity, score.timestampUs)
            } }.also { frames.forEach { it.second.recycle() } }
        } catch (cancelled: CancellationException) { frames.forEach { it.second.recycle() }; throw cancelled }
        catch (_: RuntimeException) { frames.forEach { it.second.recycle() }; return emptyList() }
        finally { retriever.release() }
    }
    private fun frameQuality(bitmap: Bitmap) = (bitmap.width.toDouble() * bitmap.height / (1280.0 * 720.0)).coerceIn(0.0, 1.0)
    private fun frameChange(a: Bitmap, b: Bitmap): Double { val points = 16; var delta = 0.0; repeat(points) { i -> val ax = i % 4 * a.width / 4; val ay = i / 4 * a.height / 4; val bx = i % 4 * b.width / 4; val by = i / 4 * b.height / 4; delta += kotlin.math.abs(android.graphics.Color.luminance(a.getPixel(ax.coerceAtMost(a.width-1), ay.coerceAtMost(a.height-1))) - android.graphics.Color.luminance(b.getPixel(bx.coerceAtMost(b.width-1), by.coerceAtMost(b.height-1)))) }; return delta / points }
}
