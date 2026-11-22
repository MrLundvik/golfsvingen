package io.github.mrlundvik.golfsvingen.store

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hämtar en film från en annan app till svingarna. Vanliga filmer i H.264 eller HEVC, i MP4 eller MOV, kopieras
 * utan omkodning. HDR-filmer, som iPhone filmar som standard, och andra format görs om till vanlig HEVC först:
 * analysen läser bara 8-bitars bilder, och HDR skulle se blekt ut för kroppsmodellen.
 */
object VideoImport {
    /** [onProgress] får andelen klar, 0–1, när filmen behöver göras om. */
    suspend fun import(context: Context, source: Uri, onProgress: (Float) -> Unit): Clip {
        if (withContext(Dispatchers.IO) { canCopy(context, source) }) {
            val copied = withContext(Dispatchers.IO) { runCatching { ClipStore.import(context, source) } }
            copied.getOrNull()?.let { return it }
            // Gick kopian inte, prövas omkodningen, som klarar fler format
        }
        val temp = File(context.cacheDir, "import_${System.currentTimeMillis()}.mp4")
        try {
            convert(context, source, temp, onProgress)
            return withContext(Dispatchers.IO) { ClipStore.import(context, Uri.fromFile(temp), dateSource = source) }
        } finally {
            temp.delete()
        }
    }

    /** Om videon kan kopieras rakt av: H.264 eller HEVC i 8 bitar, utan HDR. */
    private fun canCopy(context: Context, source: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, source, null)
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return false
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime != MediaFormat.MIMETYPE_VIDEO_AVC && mime != MediaFormat.MIMETYPE_VIDEO_HEVC) return false
            val transfer = if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) else 0
            if (transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG) return false
            if (format.containsKey(MediaFormat.KEY_PROFILE)) {
                val profile = format.getInteger(MediaFormat.KEY_PROFILE)
                val tenBit = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                    profile != CodecProfileLevel.HEVCProfileMain && profile != CodecProfileLevel.HEVCProfileMainStill
                } else {
                    profile == CodecProfileLevel.AVCProfileHigh10 || profile == CodecProfileLevel.AVCProfileHigh422 ||
                        profile == CodecProfileLevel.AVCProfileHigh444
                }
                if (tenBit) return false
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            extractor.release()
        }
    }

    /** Gör om filmen till HEVC i 8 bitar utan ljud, med HDR omräknat till vanliga färger. */
    @OptIn(UnstableApi::class)
    private suspend fun convert(context: Context, source: Uri, out: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Main) {
            val done = CompletableDeferred<Unit>()
            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H265)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        done.complete(Unit)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        done.completeExceptionally(exportException)
                    }
                })
                .build()
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source)).setRemoveAudio(true).build()
            val composition = Composition.Builder(EditedMediaItemSequence(item))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                .build()
            transformer.start(composition, out.absolutePath)
            try {
                coroutineScope {
                    val poll = launch {
                        val holder = ProgressHolder()
                        while (isActive) {
                            if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                            delay(250)
                        }
                    }
                    try {
                        done.await()
                    } finally {
                        poll.cancel()
                    }
                }
            } catch (e: CancellationException) {
                transformer.cancel()
                throw e
            }
        }
}
