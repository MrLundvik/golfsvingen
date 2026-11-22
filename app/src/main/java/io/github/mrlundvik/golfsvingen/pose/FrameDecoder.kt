package io.github.mrlundvik.golfsvingen.pose

import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Avkodar en film bild för bild till upprätta, nedskalade bitmappar. Avkodningen går i följd
 * genom filen, vilket är många gånger snabbare än att söka fram varje bild för sig.
 */
object FrameDecoder {
    /** Längsta sidan på bilderna som analyseras. Kroppsmodellen arbetar ändå i 256×256. */
    private const val TARGET_LONG_SIDE = 960

    /**
     * Anropar [onFrame] med var [stride]:e bild. Bitmappen återanvänds, så den får inte sparas.
     * [check] anropas mellan bilderna och kan kasta för att avbryta. Med [fromUs] och [toUs] avkodas bara det
     * avsnittet, från nyckelbilden före, och [want] kan hoppa över bilder som inte behövs utan att räkna om dem.
     */
    fun decode(
        context: Context,
        uri: Uri,
        rotation: Int,
        stride: Int,
        check: () -> Unit,
        fromUs: Long = 0L,
        toUs: Long = Long.MAX_VALUE,
        want: (ptsUs: Long) -> Boolean = { true },
        onFrame: (bitmap: Bitmap, ptsUs: Long) -> Unit,
    ) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("Filen har ingen video")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            // Inte i realtid: avkoda så fort det går
            format.setInteger(MediaFormat.KEY_PRIORITY, 1)
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()

            val converter = YuvConverter(rotation)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var index = 0
            while (true) {
                check()
                if (!inputDone) {
                    val inIndex = dec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = dec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = dec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val end = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    val pts = info.presentationTimeUs
                    val past = pts > toUs
                    if (!past && (info.size > 0 || !end)) {
                        if (pts >= fromUs && index % stride == 0 && want(pts)) {
                            val image = dec.getOutputImage(outIndex)
                                ?: throw IllegalStateException("Videoavkodaren lämnar inga bilder att analysera")
                            val bitmap = image.use { converter.convert(it) }
                            onFrame(bitmap, pts)
                        }
                        index++
                    }
                    dec.releaseOutputBuffer(outIndex, false)
                    if (end || past) break
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            extractor.release()
        }
    }

    /** YUV 4:2:0 till ARGB, med nedskalning och vridning till upprätt läge i samma steg. */
    private class YuvConverter(private val rotation: Int) {
        private var bitmap: Bitmap? = null
        private var pixels = IntArray(0)
        private var yBytes = ByteArray(0)
        private var uBytes = ByteArray(0)
        private var vBytes = ByteArray(0)

        fun convert(image: Image): Bitmap {
            val crop = image.cropRect
            val srcW = crop.width()
            val srcH = crop.height()
            val step = max(1, (max(srcW, srcH).toFloat() / TARGET_LONG_SIDE).roundToInt())
            val w = srcW / step
            val h = srcH / step
            val turned = rotation == 90 || rotation == 270
            val outW = if (turned) h else w
            val outH = if (turned) w else h

            val bmp = bitmap?.takeIf { it.width == outW && it.height == outH }
                ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { bitmap = it }
            if (pixels.size != outW * outH) pixels = IntArray(outW * outH)

            val planes = image.planes
            yBytes = copy(planes[0].buffer, yBytes)
            uBytes = copy(planes[1].buffer, uBytes)
            vBytes = copy(planes[2].buffer, vBytes)
            val yRow = planes[0].rowStride
            val yPix = planes[0].pixelStride
            val uRow = planes[1].rowStride
            val uPix = planes[1].pixelStride
            val vRow = planes[2].rowStride
            val vPix = planes[2].pixelStride

            var o = 0
            for (oy in 0 until outH) {
                for (ox in 0 until outW) {
                    // Från punkten i den upprätta bilden tillbaka till punkten i den lagrade
                    val sx: Int
                    val sy: Int
                    when (rotation) {
                        90 -> { sx = oy; sy = h - 1 - ox }
                        180 -> { sx = w - 1 - ox; sy = h - 1 - oy }
                        270 -> { sx = w - 1 - oy; sy = ox }
                        else -> { sx = ox; sy = oy }
                    }
                    val px = crop.left + sx * step
                    val py = crop.top + sy * step
                    val y = (yBytes[py * yRow + px * yPix].toInt() and 0xff) - 16
                    val cx = px shr 1
                    val cy = py shr 1
                    val u = (uBytes[cy * uRow + cx * uPix].toInt() and 0xff) - 128
                    val v = (vBytes[cy * vRow + cx * vPix].toInt() and 0xff) - 128
                    val c = 298 * max(0, y)
                    val r = clamp((c + 409 * v + 128) shr 8)
                    val g = clamp((c - 100 * u - 208 * v + 128) shr 8)
                    val b = clamp((c + 516 * u + 128) shr 8)
                    pixels[o++] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            bmp.setPixels(pixels, 0, outW, 0, 0, outW, outH)
            return bmp
        }

        private fun copy(buffer: java.nio.ByteBuffer, reuse: ByteArray): ByteArray {
            buffer.rewind()
            val n = buffer.remaining()
            val out = if (reuse.size >= n) reuse else ByteArray(n)
            buffer.get(out, 0, n)
            return out
        }

        private fun clamp(x: Int) = if (x < 0) 0 else if (x > 255) 255 else x
    }
}
