package io.github.mrlundvik.golfsvingen.store

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import io.github.mrlundvik.golfsvingen.camera.RingBufferEncoder
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Clip(
    val uri: Uri,
    val name: String,
    /** När svingen filmades. Läses ur filnamnet, så att ett klipp behåller originalets tid. */
    val takenSec: Long,
    val durationMs: Long,
    val sizeBytes: Long,
)

/** Svingarna sparas i telefonens Filmer/Golfsvingen, så att de syns i Google Foto och Filer. */
object ClipStore {
    const val FOLDER = "Movies/Golfsvingen"
    private const val STAMP = "yyyyMMdd_HHmmss"
    private val stampInName = Regex("""\d{8}_\d{6}""")
    private val projection = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DISPLAY_NAME,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.SIZE,
    )

    fun write(context: Context, frames: List<RingBufferEncoder.Frame>, format: MediaFormat, rotation: Int): String {
        require(frames.isNotEmpty()) { "Inga bilder att spara" }
        val name = "Golfsvingen_" + SimpleDateFormat(STAMP, Locale.ROOT).format(Date()) + ".mp4"
        create(context, name) { muxer ->
            muxer.setOrientationHint(rotation)
            val track = muxer.addTrack(format)
            muxer.start()
            val base = frames.first().ptsUs
            val info = MediaCodec.BufferInfo()
            for (f in frames) {
                info.set(0, f.data.size, f.ptsUs - base, if (f.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(track, ByteBuffer.wrap(f.data), info)
            }
        }
        return name
    }

    /** Tiderna för filmens nyckelbilder, i µs. Ett klipp kan bara börja på en nyckelbild utan att videon kodas om. */
    fun keyFrameTimesUs(context: Context, uri: Uri): List<Long> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(videoTrack(extractor))
            val out = mutableListOf<Long>()
            while (true) {
                val t = extractor.sampleTime
                if (t < 0) break
                if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) out += t
                if (!extractor.advance()) break
            }
            return out
        } finally {
            extractor.release()
        }
    }

    /**
     * Sparar avsnittet som en ny fil, utan omkodning. Returnerar det nya klippet och var i originalet det
     * börjar, alltså nyckelbilden närmast före [startUs], så att analysen kan flyttas över.
     */
    fun trim(context: Context, src: Clip, startUs: Long, endUs: Long): Pair<Clip, Long> {
        val name = src.name.removeSuffix(".mp4").replace(Regex("""(_klipp)+$"""), "") + "_klipp.mp4"
        val (uri, start) = remux(context, src.uri, name, startUs, endUs, fromStart = true)
        return (get(context, uri) ?: Clip(uri, name, src.takenSec, (endUs - startUs) / 1000, 0)) to start
    }

    /**
     * Hämtar en film från en annan app, till exempel Google Foto, Drive eller Filer, till svingarna. Bara videon
     * följer med, utan ljud, och den kodas inte om. Svingen får filmens inspelningstid om filmen har en.
     */
    fun import(context: Context, source: Uri): Clip {
        val recorded = MediaMetadataRetriever().use { r ->
            r.setDataSource(context, source)
            recordedMs(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
        }
        val taken = recorded ?: System.currentTimeMillis()
        val name = "Golfsvingen_" + SimpleDateFormat(STAMP, Locale.ROOT).format(Date(taken)) + "_import.mp4"
        val (uri, _) = remux(context, source, name, 0L, Long.MAX_VALUE, fromStart = false)
        return get(context, uri) ?: Clip(uri, name, taken / 1000, 0, 0)
    }

    /** Inspelningstiden ur filmens metadata, som till exempel 20261122T142503.000Z i UTC. Null om den saknas. */
    private fun recordedMs(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val ms = listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss").firstNotNullOfOrNull { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(value)?.time
            }.getOrNull()
        } ?: return null
        // Filmer utan tid har ibland 1904 eller 1970, och en tid i framtiden är också fel
        return ms.takeIf { it > 946_684_800_000L && it <= System.currentTimeMillis() + 86_400_000L }
    }

    /**
     * Kopierar videon i [source] från [startUs] till [endUs] till en ny fil bland svingarna. Med [fromStart]
     * börjar tiderna på noll i klippet, annars behålls filmens egna. Returnerar filen och var den börjar i källan.
     */
    private fun remux(context: Context, source: Uri, name: String, startUs: Long, endUs: Long, fromStart: Boolean): Pair<Uri, Long> {
        val rotation = MediaMetadataRetriever().use { r ->
            r.setDataSource(context, source)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, source, null)
            val track = videoTrack(extractor)
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val first = extractor.sampleTime
            require(first >= 0) { "Filmen är tom" }
            val base = if (fromStart) first else 0L
            val buffer = ByteBuffer.allocate(maxOf(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 0), 8 shl 20))
            val uri = create(context, name) { muxer ->
                muxer.setOrientationHint(rotation)
                val out = muxer.addTrack(format)
                muxer.start()
                val info = MediaCodec.BufferInfo()
                while (true) {
                    val t = extractor.sampleTime
                    if (t < 0 || t > endUs) break
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val key = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                    info.set(0, size, maxOf(0L, t - base), if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(out, buffer, info)
                    if (!extractor.advance()) break
                }
            }
            return uri to first
        } finally {
            extractor.release()
        }
    }

    /** Lägger appens egen fil i papperskorgen, där den finns kvar i 30 dagar. False om filen inte är appens. */
    fun trash(context: Context, uri: Uri): Boolean =
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, 1) }
            context.contentResolver.update(uri, values, null, null) > 0
        } catch (e: SecurityException) {
            false
        }

    fun list(context: Context): List<Clip> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val out = mutableListOf<Clip>()
        context.contentResolver.query(
            collection, projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("$FOLDER%"),
            null,
        )?.use { c ->
            while (c.moveToNext()) out += c.toClip(ContentUris.withAppendedId(collection, c.getLong(0)))
        }
        return out.sortedByDescending { it.takenSec }
    }

    fun get(context: Context, uri: Uri): Clip? =
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.toClip(uri) else null
        }

    private fun Cursor.toClip(uri: Uri): Clip {
        val name = getString(1) ?: ""
        return Clip(uri, name, takenSec(name) ?: getLong(2), getLong(3), getLong(4))
    }

    private fun takenSec(name: String): Long? =
        stampInName.find(name)?.let { m ->
            runCatching { SimpleDateFormat(STAMP, Locale.ROOT).parse(m.value)?.time?.div(1000) }.getOrNull()
        }

    private fun videoTrack(extractor: MediaExtractor): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: throw IllegalStateException("Filen har ingen video")

    private fun create(context: Context, name: String, body: (MediaMuxer) -> Unit): Uri {
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
                    body(muxer)
                    muxer.stop()
                } finally {
                    muxer.release()
                }
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
