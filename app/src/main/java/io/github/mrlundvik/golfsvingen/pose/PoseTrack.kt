package io.github.mrlundvik.golfsvingen.pose

import android.content.Context
import io.github.mrlundvik.golfsvingen.store.Clip
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** MediaPipes 33 kroppspunkter. Udda nummer är vänster sida, jämna höger. */
object Landmark {
    const val COUNT = 33
    const val NOSE = 0
    const val EAR_L = 7
    const val EAR_R = 8
    const val SHOULDER_L = 11
    const val SHOULDER_R = 12
    const val WRIST_L = 15
    const val WRIST_R = 16
    const val HIP_L = 23
    const val HIP_R = 24

    val BONES = listOf(
        11 to 12, 23 to 24, 11 to 23, 12 to 24,
        11 to 13, 13 to 15, 15 to 19,
        12 to 14, 14 to 16, 16 to 20,
        23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31,
        24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32,
    )
    val JOINTS = listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
}

/** En bild: x och y är andelar av bildens bredd och höjd, v är hur säkert punkten syns (0–1). */
class Pose(val data: FloatArray) {
    fun x(i: Int) = data[i * 3]
    fun y(i: Int) = data[i * 3 + 1]
    fun v(i: Int) = data[i * 3 + 2]
}

/**
 * Kroppspunkterna för en hel film. [width] och [height] är bildens upprätta storlek,
 * som behövs för att räkna vinklar rätt när bredd och höjd skiljer sig.
 */
class PoseTrack(
    val timesUs: LongArray,
    /** Per bild 33 punkter × (x, y, v). En bild utan person har bara nollor. */
    val data: FloatArray,
    val width: Int,
    val height: Int,
    val delegate: String,
) {
    val size get() = timesUs.size
    private val stride = Landmark.COUNT * 3

    fun found(frame: Int) = (0 until Landmark.COUNT).any { data[frame * stride + it * 3 + 2] > 0f }

    private fun pose(frame: Int) = Pose(data.copyOfRange(frame * stride, (frame + 1) * stride))

    /** Kroppen vid tiden [timeUs], interpolerad mellan de två närmaste analyserade bilderna. */
    fun at(timeUs: Long): Pose? {
        if (size == 0) return null
        var i = timesUs.binarySearch(timeUs)
        if (i >= 0) return if (found(i)) pose(i) else null
        i = -i - 1 // första bilden efter tiden
        if (i == 0) return if (found(0)) pose(0) else null
        if (i >= size) return if (found(size - 1)) pose(size - 1) else null
        val a = i - 1
        val b = i
        val fa = found(a)
        val fb = found(b)
        if (!fa && !fb) return null
        if (!fa) return pose(b)
        if (!fb) return pose(a)
        val t = (timeUs - timesUs[a]).toFloat() / (timesUs[b] - timesUs[a])
        val out = FloatArray(stride)
        for (k in 0 until stride) out[k] = data[a * stride + k] * (1 - t) + data[b * stride + k] * t
        return Pose(out)
    }

    /** Händernas bana: mitten mellan handlederna, med tiden. Händerna håller ihop i greppet. */
    val handPath: List<Triple<Long, Float, Float>> by lazy {
        (0 until size).filter { found(it) }.map { f ->
            val p = pose(f)
            Triple(timesUs[f], (p.x(Landmark.WRIST_L) + p.x(Landmark.WRIST_R)) / 2, (p.y(Landmark.WRIST_L) + p.y(Landmark.WRIST_R)) / 2)
        }
    }

    /** Första bilden med en person, alltså uppställningen om klippet börjar där. */
    val address: Pose? by lazy { (0 until size).firstOrNull { found(it) }?.let { pose(it) } }

    /** Huvudets mitt och en ungefärlig radie, som andelar av bildens bredd och höjd. */
    fun head(p: Pose): Triple<Float, Float, Float> {
        val points = listOf(Landmark.NOSE, Landmark.EAR_L, Landmark.EAR_R).filter { p.v(it) > 0.5f }
            .ifEmpty { listOf(Landmark.NOSE) }
        val cx = points.map { p.x(it) }.average().toFloat()
        val cy = points.map { p.y(it) }.average().toFloat()
        // Huvudets radie är ungefär en femtedel av bålens längd
        val torso = hypot(
            ((p.x(Landmark.SHOULDER_L) + p.x(Landmark.SHOULDER_R)) - (p.x(Landmark.HIP_L) + p.x(Landmark.HIP_R))) / 2 * width,
            ((p.y(Landmark.SHOULDER_L) + p.y(Landmark.SHOULDER_R)) - (p.y(Landmark.HIP_L) + p.y(Landmark.HIP_R))) / 2 * height,
        )
        return Triple(cx, cy, 0.22f * torso / width)
    }

    /** Ryggens lutning från lodlinjen i grader, från höfternas mitt till axlarnas mitt. */
    fun spineTilt(p: Pose): Float {
        val dx = ((p.x(Landmark.SHOULDER_L) + p.x(Landmark.SHOULDER_R)) - (p.x(Landmark.HIP_L) + p.x(Landmark.HIP_R))) / 2 * width
        val dy = ((p.y(Landmark.SHOULDER_L) + p.y(Landmark.SHOULDER_R)) - (p.y(Landmark.HIP_L) + p.y(Landmark.HIP_R))) / 2 * height
        return Math.toDegrees(atan2(abs(dx).toDouble(), (-dy).toDouble())).toFloat()
    }
}

/** Analysen sparas i appens egna filer, så att den bara behöver göras en gång per sving. */
object PoseStore {
    private const val MAGIC = 0x47535031 // "GSP1"
    private const val VERSION = 1

    private fun file(context: Context, clip: Clip) =
        File(File(context.filesDir, "pose").apply { mkdirs() }, "${clip.uri.lastPathSegment}.bin")

    fun load(context: Context, clip: Clip): PoseTrack? = runCatching {
        val f = file(context, clip)
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { s ->
            if (s.readInt() != MAGIC || s.readInt() != VERSION) return null
            val width = s.readInt()
            val height = s.readInt()
            val delegate = s.readUTF()
            val n = s.readInt()
            val times = LongArray(n) { s.readLong() }
            val data = FloatArray(n * Landmark.COUNT * 3) { s.readFloat() }
            PoseTrack(times, data, width, height, delegate)
        }
    }.getOrNull()

    fun save(context: Context, clip: Clip, track: PoseTrack) {
        val f = file(context, clip)
        val tmp = File(f.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { s ->
            s.writeInt(MAGIC)
            s.writeInt(VERSION)
            s.writeInt(track.width)
            s.writeInt(track.height)
            s.writeUTF(track.delegate)
            s.writeInt(track.size)
            for (t in track.timesUs) s.writeLong(t)
            for (v in track.data) s.writeFloat(v)
        }
        tmp.renameTo(f)
    }
}
