package io.github.mrlundvik.golfsvingen.pose

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import io.github.mrlundvik.golfsvingen.store.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/** Hittar kroppspunkterna i varje bild med MediaPipes kroppsmodell. Allt sker i telefonen. */
object PoseAnalyzer {
    private const val MODEL = "pose_landmarker_heavy.task"
    /** Bilder per sekund som analyseras. Mer än så ger inget för en sving, men tar längre tid. */
    private const val ANALYSIS_FPS = 120.0
    private const val PREF_DELEGATE = "poseDelegate"

    // En analys åt gången, även när två svingar jämförs. Kroppsmodellen tar mycket minne på grafikprocessorn
    private val lock = Mutex()

    suspend fun analyze(context: Context, clip: Clip, onProgress: (Float) -> Unit): PoseTrack = lock.withLock { analyzeNow(context, clip, onProgress) }

    private suspend fun analyzeNow(context: Context, clip: Clip, onProgress: (Float) -> Unit): PoseTrack = withContext(Dispatchers.Default) {
        val meta = MediaMetadataRetriever().use { r ->
            r.setDataSource(context, clip.uri)
            fun num(key: Int) = r.extractMetadata(key)?.toDoubleOrNull()
            Meta(
                rotation = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0,
                frames = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toInt() ?: 0,
                durationMs = num(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0.0,
            )
        }
        val fps = if (meta.frames > 0 && meta.durationMs > 0) meta.frames / (meta.durationMs / 1000.0) else 240.0
        val stride = max(1, (fps / ANALYSIS_FPS).roundToInt())
        val expected = if (meta.frames > 0) (meta.frames + stride - 1) / stride else 0

        val startedAt = SystemClock.elapsedRealtime()
        val (landmarker, delegate) = createLandmarker(context)
        val times = ArrayList<Long>()
        val values = ArrayList<FloatArray>()
        val depths = ArrayList<FloatArray>()
        var width = 0
        var height = 0
        var lastMs = -1L
        var confirmed = false
        val ctx = coroutineContext
        try {
            FrameDecoder.decode(context, clip.uri, meta.rotation, stride, check = { ctx.ensureActive() }) { bitmap, ptsUs ->
                width = bitmap.width
                height = bitmap.height
                // MediaPipe kräver strängt växande tider i millisekunder
                val ms = max(lastMs + 1, ptsUs / 1000)
                lastMs = ms
                val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), ms)
                val person = result.landmarks().firstOrNull()
                val frame = FloatArray(Landmark.COUNT * 3)
                val z = FloatArray(Landmark.COUNT)
                if (person != null) {
                    for (i in 0 until minOf(Landmark.COUNT, person.size)) {
                        val lm = person[i]
                        frame[i * 3] = lm.x()
                        frame[i * 3 + 1] = lm.y()
                        frame[i * 3 + 2] = lm.visibility().orElse(1f).coerceIn(0.01f, 1f)
                        z[i] = lm.z()
                    }
                }
                times += ptsUs
                values += frame
                depths += z
                if (!confirmed && delegate == "GPU") {
                    confirmed = true
                    prefs(context).edit().putString(PREF_DELEGATE, "gpu").apply()
                }
                if (expected > 0) onProgress((times.size.toFloat() / expected).coerceAtMost(1f))
            }
        } finally {
            landmarker.close()
        }
        require(times.isNotEmpty()) { "Inga bilder att analysera" }
        val data = FloatArray(values.size * Landmark.COUNT * 3)
        values.forEachIndexed { f, frame -> frame.copyInto(data, f * Landmark.COUNT * 3) }
        val depth = FloatArray(depths.size * Landmark.COUNT)
        depths.forEachIndexed { f, z -> z.copyInto(depth, f * Landmark.COUNT) }
        PoseTrack(times.toLongArray(), data, width, height, delegate, (SystemClock.elapsedRealtime() - startedAt).toInt(), depth)
    }

    private class Meta(val rotation: Int, val frames: Int, val durationMs: Double)

    private fun prefs(context: Context) = context.getSharedPreferences("golfsvingen", Context.MODE_PRIVATE)

    /**
     * Grafikprocessorn är snabbast, men en drivrutin som inte klarar modellen kan krascha appen
     * utan att det går att fånga. Därför märks försöket innan det görs: står märket kvar nästa
     * gång kraschade det, och då används processorn i stället.
     */
    private fun createLandmarker(context: Context): Pair<PoseLandmarker, String> {
        val prefs = prefs(context)
        val state = prefs.getString(PREF_DELEGATE, null)
        if (state == null || state == "gpu") {
            prefs.edit().putString(PREF_DELEGATE, "gpu-testing").commit()
            try {
                return build(context, Delegate.GPU) to "GPU"
            } catch (e: Exception) {
                prefs.edit().putString(PREF_DELEGATE, "cpu").apply()
            }
        } else if (state == "gpu-testing") {
            prefs.edit().putString(PREF_DELEGATE, "cpu").apply()
        }
        return build(context, Delegate.CPU) to "CPU"
    }

    private fun build(context: Context, delegate: Delegate): PoseLandmarker {
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(delegate).build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(0.5f)
            .setMinPosePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return PoseLandmarker.createFromOptions(context, options)
    }
}
