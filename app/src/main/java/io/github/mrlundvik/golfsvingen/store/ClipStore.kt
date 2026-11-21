package io.github.mrlundvik.golfsvingen.store

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import io.github.mrlundvik.golfsvingen.camera.RingBufferEncoder
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Clip(
    val uri: Uri,
    val name: String,
    val dateAddedSec: Long,
    val durationMs: Long,
    val sizeBytes: Long,
)

/** Svingarna sparas i telefonens Filmer/Golfsvingen, så att de syns i Google Foto och Filer. */
object ClipStore {
    const val FOLDER = "Movies/Golfsvingen"

    fun write(context: Context, frames: List<RingBufferEncoder.Frame>, format: MediaFormat, rotation: Int): String {
        require(frames.isNotEmpty()) { "Inga bilder att spara" }
        val name = "Golfsvingen_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IllegalStateException("Filen kunde inte skapas")
        try {
            val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Filen kunde inte öppnas")
            pfd.use {
                val muxer = MediaMuxer(it.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    muxer.setOrientationHint(rotation)
                    val track = muxer.addTrack(format)
                    muxer.start()
                    val base = frames.first().ptsUs
                    val info = MediaCodec.BufferInfo()
                    for (f in frames) {
                        info.set(0, f.data.size, f.ptsUs - base, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                        muxer.writeSampleData(track, ByteBuffer.wrap(f.data), info)
                    }
                    muxer.stop()
                } finally {
                    muxer.release()
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return name
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    fun list(context: Context): List<Clip> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
        )
        val out = mutableListOf<Clip>()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("$FOLDER%"),
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val date = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val dur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val size = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext()) {
                out += Clip(
                    uri = ContentUris.withAppendedId(collection, c.getLong(id)),
                    name = c.getString(name) ?: "",
                    dateAddedSec = c.getLong(date),
                    durationMs = c.getLong(dur),
                    sizeBytes = c.getLong(size),
                )
            }
        }
        return out
    }
}
