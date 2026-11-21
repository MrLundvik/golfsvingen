package io.github.mrlundvik.golfsvingen.camera

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import kotlin.math.abs

/**
 * Kodar kamerans bilder hela tiden och behåller de senaste sekunderna i minnet.
 * När en sving ska sparas plockas bilderna från strax före till strax efter triggern ut,
 * så att svingen kommer med från början även om triggern kommer vid träffen.
 */
class RingBufferEncoder(
    width: Int,
    height: Int,
    private val fps: Int,
    private val bufferUs: Long = 10_000_000L,
) {
    class Frame(val data: ByteArray, val ptsUs: Long, val key: Boolean)

    private class PendingSave(
        val triggerPtsUs: Long,
        val preUs: Long,
        val postUs: Long,
        val onReady: (List<Frame>, MediaFormat) -> Unit,
    )

    private val thread = HandlerThread("kodare").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    private val frames = ArrayDeque<Frame>()
    private val pending = mutableListOf<PendingSave>()
    private var lastKeyPtsUs = Long.MIN_VALUE
    @Volatile private var outputFormat: MediaFormat? = null
    @Volatile private var latestPtsUs = -1L

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val bytes = ByteArray(info.size)
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    buffer.get(bytes)
                    onFrame(Frame(bytes, info.presentationTimeUs, (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0))
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // Kodaren håller på att stängas
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e("Golfsvingen", "Kodarfel", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputFormat = format
        }
    }

    private val codec: MediaCodec
    val inputSurface: Surface
    val mime: String

    init {
        val (codec, mime) = createCodec(width, height)
        this.codec = codec
        this.mime = mime
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    val codecLabel: String get() = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "HEVC" else "H.264"

    private fun format(mime: String, width: Int, height: Int, withOperatingRate: Boolean) =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) 40_000_000 else 60_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setFloat(MediaFormat.KEY_I_FRAME_INTERVAL, 0.5f)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (withOperatingRate) setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
        }

    private fun createCodec(width: Int, height: Int): Pair<MediaCodec, String> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        var lastError: Exception? = null
        for (mime in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)) {
            for (withOperatingRate in listOf(true, false)) {
                val fmt = format(mime, width, height, withOperatingRate)
                val probe = MediaFormat.createVideoFormat(mime, width, height).apply { setInteger(MediaFormat.KEY_FRAME_RATE, fps) }
                val name = list.findEncoderForFormat(probe)
                var codec: MediaCodec? = null
                try {
                    codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(mime)
                    codec.setCallback(callback, handler)
                    codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    return codec to mime
                } catch (e: Exception) {
                    lastError = e
                    Log.w("Golfsvingen", "Kodaren $mime (operating rate $withOperatingRate) gick inte: ${e.message}")
                    try { codec?.release() } catch (_: Exception) {}
                }
            }
        }
        throw IllegalStateException("Ingen videokodare klarar ${width}×${height} i $fps fps", lastError)
    }

    private fun onFrame(frame: Frame) {
        val ready = mutableListOf<Pair<PendingSave, List<Frame>>>()
        synchronized(lock) {
            frames.addLast(frame)
            latestPtsUs = frame.ptsUs
            // Släpp allt före den senaste nyckelbilden som är äldre än bufferten,
            // så att det som finns kvar alltid börjar med en nyckelbild
            val cutoff = frame.ptsUs - bufferUs
            var dropUntil = -1
            for ((i, f) in frames.withIndex()) {
                if (f.ptsUs > cutoff) break
                if (f.key) dropUntil = i
            }
            if (dropUntil > 0) repeat(dropUntil) { frames.removeFirst() }
            // Skydd mot att minnet tar slut om kodaren slutar ge nyckelbilder
            while (frames.size > fps * 30) frames.removeFirst()

            val it = pending.iterator()
            while (it.hasNext()) {
                val p = it.next()
                if (frame.ptsUs >= p.triggerPtsUs + p.postUs) {
                    it.remove()
                    ready += p to snapshot(p)
                }
            }
        }
        val format = outputFormat
        if (format != null) for ((p, list) in ready) if (list.isNotEmpty()) p.onReady(list, format)

        if (frame.key) {
            lastKeyPtsUs = frame.ptsUs
        } else if (frame.ptsUs - lastKeyPtsUs > 500_000L) {
            // Be om en nyckelbild minst varje halv sekund, för exakt klippning och snabb stegning
            lastKeyPtsUs = frame.ptsUs
            try {
                codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
            } catch (_: Exception) {}
        }
    }

    private fun snapshot(p: PendingSave): List<Frame> {
        val start = p.triggerPtsUs - p.preUs
        val end = p.triggerPtsUs + p.postUs
        var first = 0
        for ((i, f) in frames.withIndex()) {
            if (f.ptsUs > start) break
            if (f.key) first = i
        }
        val out = ArrayList<Frame>()
        for (i in first until frames.size) {
            val f = frames[i]
            if (f.ptsUs > end) break
            out += f
        }
        // Första bilden måste vara en nyckelbild för att filen ska gå att spela
        while (out.isNotEmpty() && !out.first().key) out.removeAt(0)
        return out
    }

    /** Sparar från [preUs] före till [postUs] efter nu. Returnerar false om det inte gick, eller om en sparning nyss begärts. */
    fun requestSave(preUs: Long, postUs: Long, onReady: (List<Frame>, MediaFormat) -> Unit): Boolean {
        synchronized(lock) {
            val now = latestPtsUs
            if (now < 0) return false
            if (pending.any { abs(it.triggerPtsUs - now) < 1_000_000L }) return false
            pending += PendingSave(now, preUs, postUs, onReady)
            return true
        }
    }

    fun release() {
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { inputSurface.release() } catch (_: Exception) {}
        thread.quitSafely()
        synchronized(lock) {
            frames.clear()
            pending.clear()
        }
    }
}
