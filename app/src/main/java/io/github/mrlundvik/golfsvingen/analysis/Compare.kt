package io.github.mrlundvik.golfsvingen.analysis

import io.github.mrlundvik.golfsvingen.pose.Landmark
import io.github.mrlundvik.golfsvingen.pose.Pose
import io.github.mrlundvik.golfsvingen.pose.PoseTrack
import kotlin.math.hypot

/**
 * Räknar om tider mellan två svingar A och B. Mellan faserna är omräkningen rak, så att båda når
 * toppen och träffen samtidigt. Före den första och efter den sista fasen går tiden i samma takt.
 */
class TimeMap private constructor(private val a: LongArray, private val b: LongArray) {
    fun aToB(t: Long) = map(t, a, b)
    fun bToA(t: Long) = map(t, b, a)

    /** Hur fort B går jämfört med A vid tiden [t] i A. */
    fun slopeAt(t: Long): Float {
        val i = segment(t, a) ?: return 1f
        return (b[i + 1] - b[i]).toFloat() / (a[i + 1] - a[i])
    }

    private fun segment(t: Long, x: LongArray): Int? {
        if (x.size < 2 || t <= x.first() || t >= x.last()) return null
        for (i in 0 until x.lastIndex) if (t < x[i + 1]) return i
        return null
    }

    private fun map(t: Long, x: LongArray, y: LongArray): Long {
        if (t <= x.first()) return y.first() + (t - x.first())
        if (t >= x.last()) return y.last() + (t - x.last())
        val i = segment(t, x) ?: return y.last() + (t - x.last())
        return y[i] + ((t - x[i]).toDouble() * (y[i + 1] - y[i]) / (x[i + 1] - x[i])).toLong()
    }

    companion object {
        /**
         * I takt: alla faser synkas. I verklig tid: bara träffen, så att skillnader i tempo syns.
         * Utan faser för båda svingarna börjar de samtidigt.
         */
        fun of(ra: Report?, rb: Report?, inStep: Boolean): TimeMap {
            if (ra == null || rb == null) return TimeMap(longArrayOf(0), longArrayOf(0))
            val pa = ra.phases.map { it.timeUs }
            val pb = rb.phases.map { it.timeUs }
            if (!inStep) return TimeMap(longArrayOf(pa[2]), longArrayOf(pb[2]))
            // Bara faser som kommer i ordning i båda svingarna, så att omräkningen alltid går framåt
            val xs = ArrayList<Long>()
            val ys = ArrayList<Long>()
            for (k in pa.indices) {
                if (xs.isEmpty() || (pa[k] > xs.last() && pb[k] > ys.last())) {
                    xs += pa[k]
                    ys += pb[k]
                }
            }
            return TimeMap(xs.toLongArray(), ys.toLongArray())
        }
    }
}

/**
 * Svingen [b] flyttad till svingen [a]:s bild och tider, för att ritas ovanpå. Höfterna i uppställningen
 * läggs på samma ställe, och storleken räknas om med benlängden, så att olika avstånd till kameran inte spelar roll.
 */
fun alignTo(b: PoseTrack, bAddress: Pose, a: PoseTrack, aAddress: Pose, map: TimeMap): PoseTrack {
    fun hip(p: Pose, w: Int, h: Int) = Pair(
        (p.x(Landmark.HIP_L) + p.x(Landmark.HIP_R)) / 2 * w,
        (p.y(Landmark.HIP_L) + p.y(Landmark.HIP_R)) / 2 * h,
    )
    fun leg(p: Pose, w: Int, h: Int): Float {
        val (hx, hy) = hip(p, w, h)
        return hypot((p.x(27) + p.x(28)) / 2 * w - hx, (p.y(27) + p.y(28)) / 2 * h - hy)
    }
    val (ax, ay) = hip(aAddress, a.width, a.height)
    val (bx, by) = hip(bAddress, b.width, b.height)
    val legA = leg(aAddress, a.width, a.height)
    val legB = leg(bAddress, b.width, b.height)
    val k = if (legA > 1f && legB > 1f) legA / legB else 1f
    val stride = Landmark.COUNT * 3
    val d = FloatArray(b.data.size)
    for (f in 0 until b.size) {
        if (!b.found(f)) continue
        for (i in 0 until Landmark.COUNT) {
            val o = f * stride + i * 3
            d[o] = (ax + (b.data[o] * b.width - bx) * k) / a.width
            d[o + 1] = (ay + (b.data[o + 1] * b.height - by) * k) / a.height
            d[o + 2] = b.data[o + 2]
        }
    }
    val times = LongArray(b.size) { map.bToA(b.timesUs[it]) }
    return PoseTrack(times, d, a.width, a.height, b.delegate)
}
