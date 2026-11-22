package io.github.mrlundvik.golfsvingen.pose

import android.content.Context
import io.github.mrlundvik.golfsvingen.store.Clip
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin

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

    /** Par av vänster och höger punkt, i de kroppsdelar som kan förväxlas var för sig. */
    val SIDE_GROUPS = listOf(
        listOf(11 to 12, 13 to 14, 15 to 16, 17 to 18, 19 to 20, 21 to 22),
        listOf(23 to 24, 25 to 26, 27 to 28, 29 to 30, 31 to 32),
        listOf(1 to 4, 2 to 5, 3 to 6, 7 to 8, 9 to 10),
    )

    val BONES = listOf(
        11 to 12, 23 to 24, 11 to 23, 12 to 24,
        11 to 13, 13 to 15, 15 to 19,
        12 to 14, 14 to 16, 16 to 20,
        23 to 25, 25 to 27, 27 to 29, 29 to 31, 27 to 31,
        24 to 26, 26 to 28, 28 to 30, 30 to 32, 28 to 32,
    )
    val JOINTS = listOf(11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)
}

/** Händernas läge vid en tid, som andelar av bildens bredd och höjd. */
class HandPoint(val t: Long, val x: Float, val y: Float, val estimated: Boolean)

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
    /** Hur lång tid analysen tog, 0 om det inte är känt. */
    val analysisMs: Int = 0,
    /** Bilder där händerna var suddiga och deras läge är uppskattat. Sätts av [cleaned]. */
    private val estimatedFrames: BooleanArray? = null,
) {
    val size get() = timesUs.size
    private val stride = Landmark.COUNT * 3

    fun found(frame: Int) = (0 until Landmark.COUNT).any { data[frame * stride + it * 3 + 2] > 0f }

    private fun pose(frame: Int) = Pose(data.copyOfRange(frame * stride, (frame + 1) * stride))

    fun poseAt(frame: Int) = pose(frame)

    /**
     * Rättar de vanligaste felen från kroppsmodellen, som bara ser en platt bild:
     * vänster och höger som byter plats, en handled som skyms av den andra handen,
     * och korta luckor där modellen är osäker. Rådatan i cachen lämnas orörd.
     */
    fun cleaned(): PoseTrack {
        val d = data.copyOf()
        val scale = maxOf(width, height).toFloat()
        fun x(f: Int, i: Int) = d[f * stride + i * 3]
        fun y(f: Int, i: Int) = d[f * stride + i * 3 + 1]
        fun v(f: Int, i: Int) = d[f * stride + i * 3 + 2]
        fun set(f: Int, i: Int, nx: Float, ny: Float, nv: Float) {
            d[f * stride + i * 3] = nx
            d[f * stride + i * 3 + 1] = ny
            d[f * stride + i * 3 + 2] = nv
        }
        fun dist(f: Int, i: Int, g: Int, j: Int) =
            hypot((x(f, i) - x(g, j)) * width, (y(f, i) - y(g, j)) * height) / scale
        val present = BooleanArray(size) { f -> (0 until Landmark.COUNT).any { v(f, it) > 0f } }

        // 1. Vänster och höger: byt tillbaka en kroppsdel om den passar mycket bättre ombytt mot förra bilden
        var prev = -1
        for (f in 0 until size) {
            if (!present[f]) continue
            if (prev >= 0) for (group in Landmark.SIDE_GROUPS) {
                var same = 0f
                var swapped = 0f
                for ((a, b) in group) {
                    same += dist(f, a, prev, a) + dist(f, b, prev, b)
                    swapped += dist(f, a, prev, b) + dist(f, b, prev, a)
                }
                if (same > 0.02f && swapped < 0.5f * same) {
                    for ((a, b) in group) {
                        val ax = x(f, a); val ay = y(f, a); val av = v(f, a)
                        set(f, a, x(f, b), y(f, b), v(f, b))
                        set(f, b, ax, ay, av)
                    }
                }
            }
            prev = f
        }

        // 2. Händerna sitter ihop i greppet. Är en handled osäker, eller orimligt långt från den andra, följer den den säkrare
        for (f in 0 until size) {
            if (!present[f]) continue
            val torso = hypot(
                ((x(f, Landmark.SHOULDER_L) + x(f, Landmark.SHOULDER_R)) - (x(f, Landmark.HIP_L) + x(f, Landmark.HIP_R))) / 2 * width,
                ((y(f, Landmark.SHOULDER_L) + y(f, Landmark.SHOULDER_R)) - (y(f, Landmark.HIP_L) + y(f, Landmark.HIP_R))) / 2 * height,
            ) / scale
            val l = Landmark.WRIST_L
            val r = Landmark.WRIST_R
            val apart = dist(f, l, f, r) > 0.4f * torso
            val (weak, strong) = if (v(f, l) < v(f, r)) l to r else r to l
            if ((v(f, weak) < 0.5f && v(f, strong) > v(f, weak) + 0.2f) || apart) {
                for (k in 0..3) { // handleden och handens tre punkter
                    val w = weak + 2 * k
                    val s = strong + 2 * k
                    set(f, w, x(f, s), y(f, s), maxOf(0.5f, minOf(v(f, s), 0.6f)))
                }
            }
        }

        // 3. Rörelseoskärpa. I nedsvingen går händerna så fort att de blir suddiga med 240 fps slutartid, och
        //    modellen lägger då handlederna för nära armbågarna, inne vid kroppen. Sådana bilder känns igen på att
        //    båda underarmarna blir mycket kortare än de brukar vara, eller att båda handlederna är osäkra.
        //    Händerna läggs då längs en båge runt axlarna, mellan de närmaste skarpa bilderna, och märks som uppskattade.
        val estimated = BooleanArray(size)
        estimateBlurredHands(d, present, estimated, ::dist)

        // 4. Korta luckor där en punkt är osäker fylls i mellan de närmaste säkra bilderna
        val maxGapUs = 200_000L
        for (i in 0 until Landmark.COUNT) {
            var f = 0
            while (f < size) {
                if (!present[f] || v(f, i) >= 0.3f) { f++; continue }
                val start = f
                while (f < size && present[f] && v(f, i) < 0.3f) f++
                val before = start - 1
                val after = f
                if (before >= 0 && after < size && present[before] && present[after] &&
                    timesUs[after] - timesUs[before] <= maxGapUs
                ) {
                    for (g in start until after) {
                        val t = (timesUs[g] - timesUs[before]).toFloat() / (timesUs[after] - timesUs[before])
                        set(g, i, x(before, i) + (x(after, i) - x(before, i)) * t, y(before, i) + (y(after, i) - y(before, i)) * t, 0.4f)
                    }
                }
            }
        }
        return PoseTrack(timesUs, d, width, height, delegate, analysisMs, estimated)
    }

    private fun estimateBlurredHands(
        d: FloatArray,
        present: BooleanArray,
        estimated: BooleanArray,
        dist: (Int, Int, Int, Int) -> Float,
    ) {
        fun x(f: Int, i: Int) = d[f * stride + i * 3]
        fun y(f: Int, i: Int) = d[f * stride + i * 3 + 1]
        fun v(f: Int, i: Int) = d[f * stride + i * 3 + 2]
        val frames = (0 until size).filter { present[it] }
        if (frames.size < 20) return

        // Underarmens verkliga längd: den syns som längst när armen ligger i bildens plan
        fun reference(elbow: Int, wrist: Int): Float {
            val lengths = frames.filter { v(it, elbow) > 0.5f && v(it, wrist) > 0.5f }.map { dist(it, elbow, it, wrist) }.sorted()
            return if (lengths.size < 10) 0f else lengths[(lengths.size * 0.8f).toInt().coerceAtMost(lengths.lastIndex)]
        }
        val foreL = reference(13, Landmark.WRIST_L)
        val foreR = reference(14, Landmark.WRIST_R)
        if (foreL <= 0f || foreR <= 0f) return
        val torsos = frames.map { f ->
            hypot(
                ((x(f, Landmark.SHOULDER_L) + x(f, Landmark.SHOULDER_R)) - (x(f, Landmark.HIP_L) + x(f, Landmark.HIP_R))) / 2 * width,
                ((y(f, Landmark.SHOULDER_L) + y(f, Landmark.SHOULDER_R)) - (y(f, Landmark.HIP_L) + y(f, Landmark.HIP_R))) / 2 * height,
            ) / maxOf(width, height)
        }.sorted()
        val torso = torsos[torsos.size / 2]
        if (torso <= 0f) return

        val lost = BooleanArray(size) { f ->
            present[f] && (
                (v(f, Landmark.WRIST_L) < 0.5f && v(f, Landmark.WRIST_R) < 0.5f) ||
                    (dist(f, 13, f, Landmark.WRIST_L) < LOST_FOREARM * foreL && dist(f, 14, f, Landmark.WRIST_R) < LOST_FOREARM * foreR)
                )
        }
        // Bilderna närmast en suddig följd är oftast också lite suddiga
        val widened = lost.copyOf()
        for (f in 0 until size) if (lost[f]) {
            for (g in maxOf(0, f - LOST_MARGIN)..minOf(size - 1, f + LOST_MARGIN)) if (present[g]) widened[g] = true
        }

        // Armbågarnas fart, för att bara röra snabba partier. Vid toppen och i avslutet är underarmarna
        // ibland korta i bilden för att de pekar mot eller från kameran, och då stämmer punkterna
        fun elbowMid(f: Int) = Pair((x(f, 13) + x(f, 14)) / 2 * width, (y(f, 13) + y(f, 14)) / 2 * height)
        fun elbowSpeed(f: Int): Float {
            val a = (f - 2 downTo 0).firstOrNull { present[it] } ?: return 0f
            val b = (f + 2 until size).firstOrNull { present[it] } ?: return 0f
            val (ax, ay) = elbowMid(a)
            val (bx, by) = elbowMid(b)
            val dt = (timesUs[b] - timesUs[a]) / 1e6f
            return if (dt <= 0f) 0f else hypot(bx - ax, by - ay) / maxOf(width, height) / torso / dt
        }

        fun center(f: Int) = Pair((x(f, Landmark.SHOULDER_L) + x(f, Landmark.SHOULDER_R)) / 2 * width, (y(f, Landmark.SHOULDER_L) + y(f, Landmark.SHOULDER_R)) / 2 * height)
        fun hands(f: Int) = Pair((x(f, Landmark.WRIST_L) + x(f, Landmark.WRIST_R)) / 2 * width, (y(f, Landmark.WRIST_L) + y(f, Landmark.WRIST_R)) / 2 * height)
        fun polar(f: Int): Pair<Float, Float> {
            val (cx, cy) = center(f)
            val (hx, hy) = hands(f)
            return Pair(atan2(hy - cy, hx - cx), hypot(hx - cx, hy - cy))
        }
        fun wrap(a: Float): Float {
            var r = a
            while (r > PI.toFloat()) r -= 2 * PI.toFloat()
            while (r <= -PI.toFloat()) r += 2 * PI.toFloat()
            return r
        }

        var f = 0
        while (f < size) {
            if (!widened[f]) { f++; continue }
            val start = f
            while (f < size && (widened[f] || !present[f])) f++
            val before = (start - 1 downTo 0).firstOrNull { present[it] && !widened[it] } ?: continue
            val after = (f until size).firstOrNull { present[it] && !widened[it] } ?: continue
            if (timesUs[after] - timesUs[before] > MAX_BLUR_US) continue
            if ((start until f).none { present[it] && elbowSpeed(it) > FAST_ELBOWS }) continue

            val (a0, r0) = polar(before)
            val (a1, r1) = polar(after)
            var da = wrap(a1 - a0)
            // Åt vilket håll händerna gick in i luckan avgör vägen runt, om luckan är mer än en halv varv
            val earlier = (before - 3 downTo 0).firstOrNull { present[it] && !widened[it] }
            if (earlier != null) {
                val turning = wrap(a0 - polar(earlier).first)
                if (abs(turning) > 0.05f && sign(turning) != sign(da)) da += if (da > 0) -2 * PI.toFloat() else 2 * PI.toFloat()
            }
            for (g in start until f) {
                if (!present[g]) continue
                val t = (timesUs[g] - timesUs[before]).toFloat() / (timesUs[after] - timesUs[before])
                val (cx, cy) = center(g)
                val a = a0 + da * t
                val r = r0 + (r1 - r0) * t
                val hx = (cx + r * cos(a)) / width
                val hy = (cy + r * sin(a)) / height
                // Handlederna och handens punkter, båda händerna i greppet
                for (k in 0..3) for (side in 0..1) {
                    val i = Landmark.WRIST_L + side + 2 * k
                    d[g * stride + i * 3] = hx
                    d[g * stride + i * 3 + 1] = hy
                    d[g * stride + i * 3 + 2] = 0.4f
                }
                estimated[g] = true
            }
        }
    }

    /** Om händernas läge i bild [frame] är uppskattat för att de var suddiga. */
    fun handsEstimated(frame: Int) = estimatedFrames?.getOrNull(frame) == true

    /** Om händerna är uppskattade i den analyserade bild som ligger närmast tiden [timeUs]. */
    fun handsEstimatedAt(timeUs: Long): Boolean {
        val est = estimatedFrames ?: return false
        if (size == 0) return false
        var i = timesUs.binarySearch(timeUs)
        if (i < 0) {
            i = -i - 1
            if (i >= size || (i > 0 && timeUs - timesUs[i - 1] < timesUs[i] - timeUs)) i -= 1
        }
        return est[i.coerceIn(0, size - 1)]
    }

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

    /**
     * Händernas bana: mitten mellan handlederna, med tiden. Händerna håller ihop i greppet.
     * Utjämnad med median över fem bilder och medel över tre, så att enstaka hopp inte syns.
     */
    val handPath: List<HandPoint> by lazy {
        val fs = (0 until size).filter { found(it) }
        val xs = fs.map { (data[it * stride + Landmark.WRIST_L * 3] + data[it * stride + Landmark.WRIST_R * 3]) / 2 }
        val ys = fs.map { (data[it * stride + Landmark.WRIST_L * 3 + 1] + data[it * stride + Landmark.WRIST_R * 3 + 1]) / 2 }
        fun median(v: List<Float>, i: Int) = v.subList(maxOf(0, i - 2), minOf(v.size, i + 3)).sorted().let { it[it.size / 2] }
        val mx = List(fs.size) { median(xs, it) }
        val my = List(fs.size) { median(ys, it) }
        fun mean(v: List<Float>, i: Int) = v.subList(maxOf(0, i - 1), minOf(v.size, i + 2)).average().toFloat()
        List(fs.size) { k -> HandPoint(timesUs[fs[k]], mean(mx, k), mean(my, k), handsEstimated(fs[k])) }
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
/** Underarmar kortare än så här, jämfört med deras vanliga längd, räknas som suddiga. */
private const val LOST_FOREARM = 0.55f
/** Så många bilder på var sida om en suddig följd räknas också som suddiga. */
private const val LOST_MARGIN = 2
/** Längre suddiga följder än så här uppskattas inte, eftersom bågen då blir för osäker. */
private const val MAX_BLUR_US = 400_000L
/** Armbågarnas fart, i bålens längd per sekund, för att ett parti ska räknas som snabbt. */
private const val FAST_ELBOWS = 1.5f

object PoseStore {
    private const val MAGIC = 0x47535031 // "GSP1"
    private const val VERSION = 2

    private fun file(context: Context, clip: Clip) =
        File(File(context.filesDir, "pose").apply { mkdirs() }, "${clip.uri.lastPathSegment}.bin")

    fun load(context: Context, clip: Clip): PoseTrack? = runCatching {
        val f = file(context, clip)
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { s ->
            if (s.readInt() != MAGIC) return null
            val version = s.readInt()
            if (version !in 1..VERSION) return null
            val width = s.readInt()
            val height = s.readInt()
            val delegate = s.readUTF()
            val analysisMs = if (version >= 2) s.readInt() else 0
            val n = s.readInt()
            val times = LongArray(n) { s.readLong() }
            val data = FloatArray(n * Landmark.COUNT * 3) { s.readFloat() }
            PoseTrack(times, data, width, height, delegate, analysisMs)
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
            s.writeInt(track.analysisMs)
            s.writeInt(track.size)
            for (t in track.timesUs) s.writeLong(t)
            for (v in track.data) s.writeFloat(v)
        }
        tmp.renameTo(f)
    }
}
