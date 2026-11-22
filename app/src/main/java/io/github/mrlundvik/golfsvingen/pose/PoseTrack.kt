package io.github.mrlundvik.golfsvingen.pose

import android.content.Context
import io.github.mrlundvik.golfsvingen.store.Clip
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Vad en ombytt bild kostar i rättningen av vänster och höger, i andelar av bildens storlek. */
private const val SWAP_COST = 0.02f

/** Fötter längre än så här från sin plats, i benlängder, räknas som tappade. */
private const val LOST_FEET = 0.1f
private const val LOST_MAX_US = 400_000L
private const val LOST_JOIN_US = 60_000L

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
class HandPoint(val t: Long, val x: Float, val y: Float)

/**
 * En bild: x och y är andelar av bildens bredd och höjd, v är hur säkert punkten syns (0–1).
 * [depth] är modellens uppskattade djup per punkt, i bildbredder från höfternas mitt. Mindre är närmare kameran.
 */
class Pose(val data: FloatArray, val depth: FloatArray? = null) {
    fun x(i: Int) = data[i * 3]
    fun y(i: Int) = data[i * 3 + 1]
    fun v(i: Int) = data[i * 3 + 2]
    fun z(i: Int) = depth?.get(i) ?: 0f
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
    /** Per bild 33 djupvärden, se [Pose.depth]. Null i analyser från före 0.4.9. */
    val depth: FloatArray? = null,
) {
    val size get() = timesUs.size
    private val stride = Landmark.COUNT * 3

    fun found(frame: Int) = (0 until Landmark.COUNT).any { data[frame * stride + it * 3 + 2] > 0f }

    private fun pose(frame: Int) = Pose(
        data.copyOfRange(frame * stride, (frame + 1) * stride),
        depth?.copyOfRange(frame * Landmark.COUNT, (frame + 1) * Landmark.COUNT),
    )

    fun poseAt(frame: Int) = pose(frame)

    /**
     * Rättar de vanligaste felen från kroppsmodellen, som bara ser en platt bild: underkroppen som tappas en kort
     * stund, vänster och höger som byter plats, en handled som skyms av den andra handen, och korta luckor där
     * modellen är osäker. Rådatan i cachen lämnas orörd.
     */
    fun cleaned(): PoseTrack {
        val d = data.copyOf()
        val dz = depth?.copyOf()
        val n = Landmark.COUNT
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

        val frames = (0 until size).filter { present[it] }
        fun swap(f: Int, a: Int, b: Int) {
            val ax = x(f, a); val ay = y(f, a); val av = v(f, a)
            set(f, a, x(f, b), y(f, b), v(f, b))
            set(f, b, ax, ay, av)
            if (dz != null) dz[f * n + a] = dz[f * n + b].also { dz[f * n + b] = dz[f * n + a] }
        }

        // 1a. Fötterna står still genom svingen. Hoppar de iväg och kommer tillbaka inom 0,4 s har modellen tappat
        //     underkroppen, oftast vid toppen. Höfter, knän och fötter räknas då som osäkra och fylls i i steg 3
        val lost = BooleanArray(size)
        if (frames.size > 2) {
            fun foot(f: Int, ankle: Int) = floatArrayOf(
                (x(f, ankle) + x(f, ankle + 2) + x(f, ankle + 4)) / 3 * width / scale,
                (y(f, ankle) + y(f, ankle + 2) + y(f, ankle + 4)) / 3 * height / scale,
            )
            fun gap(p: FloatArray, q: FloatArray) = hypot(p[0] - q[0], p[1] - q[1])
            val legs = frames.map { f ->
                hypot(
                    ((x(f, Landmark.HIP_L) + x(f, Landmark.HIP_R)) - (x(f, 27) + x(f, 28))) / 2 * width,
                    ((y(f, Landmark.HIP_L) + y(f, Landmark.HIP_R)) - (y(f, 27) + y(f, 28))) / 2 * height,
                ) / scale
            }.sorted()
            val tol = LOST_FEET * legs[legs.size / 2]
            var home: Pair<FloatArray, FloatArray>? = null
            val away = ArrayList<Int>()
            for (f in frames) {
                val l = foot(f, 27)
                val r = foot(f, 28)
                val h = home
                if (h == null) { home = l to r; continue }
                // Fötternas namn kan vara ombytta, så båda sätten att para ihop dem prövas
                val off = minOf(gap(l, h.first) + gap(r, h.second), gap(l, h.second) + gap(r, h.first)) / 2
                if (off > tol) {
                    away += f
                    // Borta längre än så är en riktig rörelse, som bakre foten i avslutet
                    if (timesUs[away.last()] - timesUs[away.first()] > LOST_MAX_US) { away.clear(); home = l to r }
                } else {
                    for (g in away) lost[g] = true
                    away.clear()
                    home = l to r
                }
            }
            // Två tappade partier tätt efter varandra är samma fel, där fötterna råkat hamna rätt däremellan
            var last = -1
            for (f in frames) if (lost[f]) {
                if (last >= 0 && timesUs[f] - timesUs[last] <= LOST_JOIN_US) for (g in last + 1 until f) lost[g] = present[g]
                last = f
            }
            for (f in frames) if (lost[f]) for (i in Landmark.HIP_L until Landmark.COUNT) d[f * stride + i * 3 + 2] = 0.1f
        }

        // 1b. Vänster och höger väljs för hela filmen på en gång, som den följd av byten som ger minst hopp mellan
        //     bilderna, där varje ombytt bild kostar lite. Förut jämfördes varje bild med den förra, och då kunde ett
        //     byte under ett tappat parti låsa sig och lämna benen korsade resten av svingen
        for ((g, group) in Landmark.SIDE_GROUPS.withIndex()) {
            val fs = if (g == 1) frames.filter { !lost[it] } else frames
            if (fs.size < 2) continue
            fun jump(fp: Int, fc: Int, crossed: Boolean): Float {
                var total = 0f
                for ((a, b) in group) {
                    val pa = if (crossed) b else a
                    val pb = if (crossed) a else b
                    total += minOf(v(fp, pa), v(fc, a)) * dist(fc, a, fp, pa) + minOf(v(fp, pb), v(fc, b)) * dist(fc, b, fp, pb)
                }
                return total
            }
            val back = Array(fs.size) { IntArray(2) }
            var keep = 0f
            var swapped = SWAP_COST
            for (k in 1 until fs.size) {
                val same = jump(fs[k - 1], fs[k], false)
                val crossed = jump(fs[k - 1], fs[k], true)
                back[k][0] = if (keep + same <= swapped + crossed) 0 else 1
                back[k][1] = if (keep + crossed <= swapped + same) 0 else 1
                val nextKeep = minOf(keep + same, swapped + crossed)
                swapped = minOf(keep + crossed, swapped + same) + SWAP_COST
                keep = nextKeep
            }
            var state = if (keep <= swapped) 0 else 1
            for (k in fs.lastIndex downTo 0) {
                if (state == 1) for ((a, b) in group) swap(fs[k], a, b)
                state = back[k][state]
            }
        }

        // 2. Händerna sitter ihop i greppet. Är en handled osäker, eller orimligt långt från den andra, följer den den
        //    trovärdigare. Det avgörs i första hand av underarmen: en handled som modellen tappar i rörelseoskärpa
        //    hamnar vid armbågen, så att underarmen blir för kort, medan modellen ofta är lika säker på den som på den
        //    rätta. Bara när underarmarna inte skiljer sig avgör säkerheten.
        fun usualLength(elbow: Int, wrist: Int): Float {
            val lengths = (0 until size).filter { present[it] && v(it, elbow) > 0.5f && v(it, wrist) > 0.5f }
                .map { dist(it, elbow, it, wrist) }.sorted()
            return if (lengths.size < 10) 0f else lengths[(lengths.size * 0.8f).toInt().coerceAtMost(lengths.lastIndex)]
        }
        val foreL = usualLength(13, Landmark.WRIST_L)
        val foreR = usualLength(14, Landmark.WRIST_R)
        for (f in 0 until size) {
            if (!present[f]) continue
            val torso = hypot(
                ((x(f, Landmark.SHOULDER_L) + x(f, Landmark.SHOULDER_R)) - (x(f, Landmark.HIP_L) + x(f, Landmark.HIP_R))) / 2 * width,
                ((y(f, Landmark.SHOULDER_L) + y(f, Landmark.SHOULDER_R)) - (y(f, Landmark.HIP_L) + y(f, Landmark.HIP_R))) / 2 * height,
            ) / scale
            val l = Landmark.WRIST_L
            val r = Landmark.WRIST_R
            val apart = dist(f, l, f, r) > 0.4f * torso
            var (weak, strong) = if (v(f, l) < v(f, r)) l to r else r to l
            if (foreL > 0f && foreR > 0f) {
                val offL = abs(1f - dist(f, 13, f, l) / foreL)
                val offR = abs(1f - dist(f, 14, f, r) / foreR)
                if (abs(offL - offR) > 0.25f) {
                    weak = if (offL > offR) l else r
                    strong = if (weak == l) r else l
                }
            }
            if ((v(f, weak) < 0.5f && v(f, strong) > v(f, weak) + 0.2f) || apart) {
                for (k in 0..3) { // handleden och handens tre punkter
                    val w = weak + 2 * k
                    val s = strong + 2 * k
                    set(f, w, x(f, s), y(f, s), maxOf(0.5f, minOf(v(f, s), 0.6f)))
                    if (dz != null) dz[f * n + w] = dz[f * n + s]
                }
            }
        }

        // 3. Korta luckor där en punkt är osäker fylls i mellan de närmaste säkra bilderna
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
                        if (dz != null) dz[g * n + i] = dz[before * n + i] + (dz[after * n + i] - dz[before * n + i]) * t
                    }
                }
            }
        }
        // 4. Djupet hoppar från bild till bild, mest för händerna, och jämnas ut med medianen över sju bilder
        if (dz != null) {
            val frames = (0 until size).filter { present[it] }
            val src = dz.copyOf()
            for (i in 0 until n) for (k in frames.indices) {
                val window = (maxOf(0, k - 3)..minOf(frames.lastIndex, k + 3)).map { src[frames[it] * n + i] }.sorted()
                dz[frames[k] * n + i] = window[window.size / 2]
            }
        }
        return PoseTrack(timesUs, d, width, height, delegate, analysisMs, dz)
    }

    /**
     * Analysen för ett klipp ur den här filmen, som börjar vid [offsetUs] och slutar vid [endUs].
     * Tiderna räknas om så att klippet börjar på noll. Null om inga bilder ryms.
     */
    fun excerpt(offsetUs: Long, endUs: Long): PoseTrack? {
        val keep = (0 until size).filter { timesUs[it] in offsetUs..endUs }
        if (keep.isEmpty()) return null
        val d = FloatArray(keep.size * stride)
        keep.forEachIndexed { k, f -> data.copyInto(d, k * stride, f * stride, (f + 1) * stride) }
        val n = Landmark.COUNT
        val dz = depth?.let { z -> FloatArray(keep.size * n).also { out -> keep.forEachIndexed { k, f -> z.copyInto(out, k * n, f * n, (f + 1) * n) } } }
        return PoseTrack(LongArray(keep.size) { timesUs[keep[it]] - offsetUs }, d, width, height, delegate, depth = dz)
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
        val n = Landmark.COUNT
        val z = depth?.let { dz -> FloatArray(n) { dz[a * n + it] * (1 - t) + dz[b * n + it] * t } }
        return Pose(out, z)
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
        List(fs.size) { k -> HandPoint(timesUs[fs[k]], mean(mx, k), mean(my, k)) }
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
    /** Version 3 har djupet. Äldre analyser görs om när svingen öppnas, så att skelettet kan ritas med djup. */
    private const val VERSION = 3

    private fun file(context: Context, clip: Clip) =
        File(File(context.filesDir, "pose").apply { mkdirs() }, "${clip.uri.lastPathSegment}.bin")

    fun load(context: Context, clip: Clip): PoseTrack? = runCatching {
        val f = file(context, clip)
        if (!f.exists()) return null
        DataInputStream(f.inputStream().buffered()).use { s ->
            if (s.readInt() != MAGIC) return null
            if (s.readInt() != VERSION) return null
            val width = s.readInt()
            val height = s.readInt()
            val delegate = s.readUTF()
            val analysisMs = s.readInt()
            val n = s.readInt()
            val times = LongArray(n) { s.readLong() }
            val data = FloatArray(n * Landmark.COUNT * 3) { s.readFloat() }
            val depth = if (s.readBoolean()) FloatArray(n * Landmark.COUNT) { s.readFloat() } else null
            PoseTrack(times, data, width, height, delegate, analysisMs, depth)
        }
    }.getOrNull()

    fun delete(context: Context, clip: Clip) {
        file(context, clip).delete()
    }

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
            s.writeBoolean(track.depth != null)
            track.depth?.forEach { s.writeFloat(it) }
        }
        tmp.renameTo(f)
    }
}
