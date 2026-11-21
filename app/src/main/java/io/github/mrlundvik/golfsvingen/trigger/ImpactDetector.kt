package io.github.mrlundvik.golfsvingen.trigger

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lyssnar efter en kort, kraftig ljudtopp: bollträffen, eller en handklapp direkt efter svingen.
 * Ljudet analyseras löpande i 10 ms-bitar och sparas aldrig.
 */
class ImpactDetector {
    /** 0 = okänslig, 1 = mycket känslig. */
    @Volatile var sensitivity = 0.5f
    @Volatile private var running = false
    private var worker: Thread? = null

    /** Toppnivån som krävs, som andel av full skala. Används för markeringen i nivåmätaren. */
    fun thresholdLevel(s: Float = sensitivity): Float = requiredPeak(s) / 32768f

    private fun requiredPeak(s: Float) = 20000f - 16000f * s
    private fun requiredRatio(s: Float) = 30f - 24f * s

    @SuppressLint("MissingPermission")
    fun start(onLevel: (Float) -> Unit, onImpact: () -> Unit) {
        if (running) return
        running = true
        worker = thread(name = "ljudtrigger") {
            val rate = 48_000
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = createRecord(rate, max(minBuffer, rate / 5))
            if (record == null) {
                running = false
                return@thread
            }
            val chunk = ShortArray(rate / 100)
            var background = 300.0
            var lastTrigger = 0L
            var lastLevel = 0L
            try {
                record.startRecording()
                while (running) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    var peak = 0
                    var sumSquares = 0.0
                    for (i in 0 until n) {
                        val v = abs(chunk[i].toInt())
                        if (v > peak) peak = v
                        sumSquares += v.toDouble() * v
                    }
                    val rms = sqrt(sumSquares / n)
                    val now = SystemClock.elapsedRealtime()
                    val s = sensitivity
                    val ratio = peak / max(background, 50.0)
                    if (peak > requiredPeak(s) && ratio > requiredRatio(s) && now - lastTrigger > 2500) {
                        lastTrigger = now
                        onImpact()
                    } else {
                        // Bakgrundsnivån följer det vanliga ljudet, men inte topparna
                        background = background * 0.98 + rms * 0.02
                    }
                    if (now - lastLevel > 50) {
                        lastLevel = now
                        onLevel((peak / 32768f).coerceIn(0f, 1f))
                    }
                }
            } catch (_: Exception) {
            } finally {
                try { record.stop() } catch (_: Exception) {}
                record.release()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(rate: Int, bufferBytes: Int): AudioRecord? {
        // UNPROCESSED saknar automatisk nivåreglering, som annars trycker ned träffljudet
        for (source in listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            try {
                val r = AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (_: Exception) {}
        }
        return null
    }

    fun stop() {
        running = false
        try { worker?.join(500) } catch (_: InterruptedException) {}
        worker = null
    }
}
