package io.github.mrlundvik.golfsvingen.analysis

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import io.github.mrlundvik.golfsvingen.pose.Landmark
import io.github.mrlundvik.golfsvingen.pose.Pose
import io.github.mrlundvik.golfsvingen.pose.PoseTrack
import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

enum class View(val label: String) { FACE_ON("Framifrån"), DOWN_THE_LINE("Bakifrån") }
enum class Status { GOOD, FAULT, UNKNOWN }

val GoodColor = Color(0xFF7BD389)
val FaultColor = Color(0xFFFF8A65)
val RefColor = Color.White
val DownswingColor = Color(0xFFFFA040)

/** Referenslinjer som ritas på videon. Punkterna är andelar av bildens bredd och höjd. */
data class GuideLine(val a: Offset, val b: Offset, val color: Color, val dashed: Boolean = false, val arrow: Boolean = false)
data class GuideLabel(val at: Offset, val text: String, val color: Color = Color.White)

class Guide(
    val lines: List<GuideLine> = emptyList(),
    val labels: List<GuideLabel> = emptyList(),
    /** Uppställningen, som skugga, så att det syns vad som rört sig. */
    val ghost: Pose? = null,
    /** Delar handbanan i backswing och nedsving vid den här tiden. */
    val pathSplitUs: Long? = null,
    /** Ben som ska framhävas i det aktuella skelettet. */
    val bones: List<Pair<Int, Int>> = emptyList(),
)

class Metric(
    val id: String,
    val name: String,
    val value: String,
    val goal: String,
    val status: Status,
    /** Hur allvarligt felet är, viktat efter hur grundläggande det är. 0 om det inte är ett fel. */
    val score: Float,
    val timeUs: Long,
    val guide: Guide,
    val advice: Advice?,
) {
    val title get() = if (status == Status.FAULT && advice != null) advice.faultTitle else name
}

class Phase(val name: String, val timeUs: Long)

class Report(
    val view: View,
    val viewGuessed: View,
    val phases: List<Phase>,
    val backswingS: Float,
    val downswingS: Float,
    val metrics: List<Metric>,
    /** Händerna var suddiga någon gång mellan toppen och avslutet, och deras läge är uppskattat där. */
    val handsEstimated: Boolean = false,
) {
    val faults get() = metrics.filter { it.status == Status.FAULT }.sortedByDescending { it.score }
    val others get() = metrics.filter { it.status != Status.FAULT }
}

/**
 * Hittar svingens faser och mäter de vanligaste felen. Allt mäts i den platta bilden, så
 * mätningarna är ungefärliga och beror på kamerans läge. Längder räknas om till centimeter
 * med benlängden, höft till fotled, som skala, eftersom benen står ungefär lodrätt från båda hållen.
 */
object SwingReport {
    private const val LEG_CM = 80f
    /** Händerna räknas som höga när de är så här många benlängder över uppställningen. */
    private const val HIGH = 0.55f
    /** Händerna står stilla under så här många benlängder per sekund. */
    private const val STILL = 0.15f
    private val SV = Locale("sv", "SE")

    fun build(track: PoseTrack, viewOverride: View?): Report? {
        val frames = (0 until track.size).filter { track.found(it) }
        if (frames.size < 20) return null
        val w = track.width.toFloat()
        val h = track.height.toFloat()
        val n = frames.size
        val poses = frames.map { track.poseAt(it) }
        val times = LongArray(n) { track.timesUs[frames[it]] }
        val est = BooleanArray(n) { track.handsEstimated(frames[it]) }

        fun pt(p: Pose, i: Int) = Offset(p.x(i) * w, p.y(i) * h)
        fun mid(p: Pose, a: Int, b: Int) = (pt(p, a) + pt(p, b)) / 2f
        fun hipMid(p: Pose) = mid(p, Landmark.HIP_L, Landmark.HIP_R)
        fun shoulderMid(p: Pose) = mid(p, Landmark.SHOULDER_L, Landmark.SHOULDER_R)
        fun ankleMid(p: Pose) = mid(p, 27, 28)
        fun head(p: Pose): Offset {
            val ids = listOf(Landmark.NOSE, Landmark.EAR_L, Landmark.EAR_R).filter { p.v(it) > 0.5f }.ifEmpty { listOf(Landmark.NOSE) }
            return Offset(ids.map { pt(p, it).x }.average().toFloat(), ids.map { pt(p, it).y }.average().toFloat())
        }
        fun norm(o: Offset) = Offset(o.x / w, o.y / h)

        // Skala: benlängden, medianen över klippet
        val legs = poses.map { (ankleMid(it) - hipMid(it)).getDistance() }.sorted()
        val leg = legs[legs.size / 2]
        if (leg < 10f) return null
        fun cm(px: Float) = px / leg * LEG_CM

        // Händerna, utjämnade över ±2 bilder
        val handsRaw = poses.map { mid(it, Landmark.WRIST_L, Landmark.WRIST_R) }
        val hands = List(n) { i ->
            val from = maxOf(0, i - 2)
            val to = minOf(n - 1, i + 2)
            var sx = 0f
            var sy = 0f
            for (k in from..to) { sx += handsRaw[k].x; sy += handsRaw[k].y }
            Offset(sx / (to - from + 1), sy / (to - from + 1))
        }
        val speed = FloatArray(n) { i ->
            val a = maxOf(0, i - 1)
            val b = minOf(n - 1, i + 1)
            val dt = (times[b] - times[a]) / 1e6f
            if (dt <= 0f) 0f else (hands[b] - hands[a]).getDistance() / leg / dt
        }

        // Faserna: händerna går upp till toppen, ned till träffen och upp igen i avslutet
        val first = hands.take(minOf(10, n))
        val addressY = first.map { it.y }.sorted()[first.size / 2]
        val height = FloatArray(n) { (addressY - hands[it].y) / leg }
        var i = 0
        while (i < n && height[i] <= HIGH) i++
        if (i >= n) return null
        var j = i
        while (j < n && height[j] > HIGH) j++
        if (j >= n) return null
        val top = (i until j).maxBy { height[it] }
        var k = j
        while (k < n && height[k] <= HIGH) k++
        val impact = (top until k).minBy { height[it] }
        val finish = if (k < n) {
            var m = k
            while (m < n && height[m] > HIGH) m++
            (k until m).maxBy { height[it] }
        } else n - 1
        var start = top
        while (start > 2 && !(speed[start] < STILL && speed[start - 1] < STILL && speed[start - 2] < STILL)) start--
        if (!(start < top && top < impact)) return null

        val p0 = poses[start]
        val pTop = poses[top]
        val pImp = poses[impact]
        val pFin = poses[finish]
        val backswingS = (times[top] - times[start]) / 1e6f
        val downswingS = (times[impact] - times[top]) / 1e6f
        // Träffen hittas med händerna. Var de suddiga där är tiden ungefärlig
        val impactApprox = (maxOf(0, impact - 2)..minOf(n - 1, impact + 2)).any { est[it] }
        val approx = if (impactApprox) "≈ " else ""

        // Kamerans läge: framifrån syns axlarna och höfterna breda, bakifrån nästan i linje
        val wide = (abs(pt(p0, 11).x - pt(p0, 12).x) + abs(pt(p0, 23).x - pt(p0, 24).x)) / 2 / leg
        val guessed = if (wide > 0.25f) View.FACE_ON else View.DOWN_THE_LINE
        val view = viewOverride ?: guessed

        val metrics = mutableListOf<Metric>()
        fun f0(v: Float) = String.format(SV, "%.0f", v)
        fun f1(v: Float) = String.format(SV, "%.1f", v)
        fun f2(v: Float) = String.format(SV, "%.2f", v)
        fun metric(
            id: String, name: String, value: String, goal: String, fault: Boolean, severity: Float, weight: Float,
            frame: Int, guide: Guide, advice: Advice?,
        ) = Metric(
            id, name, value, goal, if (fault) Status.FAULT else Status.GOOD,
            if (fault) weight * severity.coerceIn(0.1f, 3f) else 0f, times[frame], guide, advice,
        )

        // Tempo, från båda hållen
        val ratio = backswingS / downswingS
        metrics += metric(
            "tempo", "Tempo",
            "$approx${f1(ratio)} : 1  (${f2(backswingS)} s upp, ${f2(downswingS)} s ned)",
            "Bra: omkring 3 : 1",
            ratio < 2.2f || ratio > 4.5f,
            if (ratio < 2.2f) (2.2f - ratio) / 0.8f else (ratio - 4.5f) / 1.5f,
            0.8f, top, Guide(), if (ratio > 4.5f) Coach.TEMPO_SLOW else Coach.TEMPO_FAST,
        )

        // Huvudets höjd i träffen, från båda hållen
        val h0 = head(p0)
        val hImp = head(pImp)
        val drop = cm(hImp.y - h0.y)
        val headDown = drop > 8f
        val headUp = drop < -5f
        metrics += metric(
            "head-height", "Huvudets höjd i träffen",
            if (drop >= 0) "${f0(drop)} cm ned" else "${f0(-drop)} cm upp",
            "Bra: högst 8 cm ned och 5 cm upp",
            headDown || headUp,
            if (headDown) (drop - 8f) / 6f else (-drop - 5f) / 6f,
            0.9f, impact,
            Guide(
                lines = listOf(
                    GuideLine(norm(h0 - Offset(0.25f * leg, 0f)), norm(h0 + Offset(0.25f * leg, 0f)), RefColor, dashed = true),
                    GuideLine(norm(h0), norm(Offset(h0.x, hImp.y)), if (headDown || headUp) FaultColor else GoodColor, arrow = true),
                ),
                labels = listOf(GuideLabel(norm(h0 + Offset(0.27f * leg, 0f)), "Huvudet i uppställningen")),
                ghost = p0,
            ),
            if (drop >= 0) Coach.HEAD_DOWN else Coach.HEAD_UP,
        )

        if (view == View.FACE_ON) {
            // Målet ligger åt motsatt håll mot dit händerna går i backswingen
            val target = -sign(hands[top].x - hands[start].x).let { if (it == 0f) -1f else it }
            val lead = if ((pt(p0, 11).x - pt(p0, 12).x) * target > 0) 11 else 12
            val leadAnkle = if ((pt(p0, 27).x - pt(p0, 28).x) * target > 0) 27 else 28
            val trailAnkle = if (leadAnkle == 27) 28 else 27

            // Höften i sidled vid toppen
            val hip0 = hipMid(p0)
            val hipTop = hipMid(pTop)
            val sway = cm((hipTop.x - hip0.x) * -target)
            val swayFault = sway > 8f
            metrics += metric(
                "sway", "Höften vid toppen",
                if (sway >= 0) "${f0(sway)} cm bort från målet" else "${f0(-sway)} cm mot målet",
                "Bra: högst 8 cm bort från målet",
                swayFault, (sway - 8f) / 8f, 1.0f, top,
                Guide(
                    lines = listOf(
                        GuideLine(norm(hip0 - Offset(0f, 0.6f * leg)), norm(hip0 + Offset(0f, 0.6f * leg)), RefColor, dashed = true),
                        GuideLine(norm(hip0), norm(Offset(hipTop.x, hip0.y)), if (swayFault) FaultColor else GoodColor, arrow = true),
                    ),
                    labels = listOf(GuideLabel(norm(hip0 + Offset(0f, 0.68f * leg)), "Höften i uppställningen")),
                    ghost = p0,
                ),
                Coach.SWAY,
            )

            // Huvudet i sidled i träffen
            val slide = cm((hImp.x - h0.x) * target)
            val slideFault = slide > 5f
            metrics += metric(
                "head-slide", "Huvudet i träffen",
                if (slide >= 0) "${f0(slide)} cm mot målet" else "${f0(-slide)} cm bakom",
                "Bra: högst 5 cm mot målet",
                slideFault, (slide - 5f) / 8f, 1.0f, impact,
                Guide(
                    lines = listOf(
                        GuideLine(norm(h0 - Offset(0f, 0.3f * leg)), norm(h0 + Offset(0f, 1.0f * leg)), RefColor, dashed = true),
                        GuideLine(norm(h0), norm(Offset(hImp.x, h0.y)), if (slideFault) FaultColor else GoodColor, arrow = true),
                    ),
                    labels = listOf(GuideLabel(norm(h0 - Offset(0f, 0.34f * leg)), "Huvudet i uppställningen")),
                    ghost = p0,
                ),
                Coach.HEAD_SLIDE,
            )

            // Ryggens lutning i sidled vid toppen: axlarna ska ligga bakom höfterna
            val spineTop = shoulderMid(pTop) - hipMid(pTop)
            val tilt = Math.toDegrees(atan2((spineTop.x * -target).toDouble(), (-spineTop.y).toDouble())).toFloat()
            val reverse = tilt < -2f
            metrics += metric(
                "reverse-pivot", "Ryggen vid toppen",
                if (tilt >= 0) "${f0(tilt)}° bort från målet" else "${f0(-tilt)}° mot målet",
                "Bra: lutar bort från målet",
                reverse, (-2f - tilt) / 8f, 1.2f, top,
                Guide(
                    lines = listOf(
                        GuideLine(norm(hipMid(pTop)), norm(hipMid(pTop) - Offset(0f, spineTop.getDistance())), RefColor, dashed = true),
                        GuideLine(norm(hipMid(pTop)), norm(shoulderMid(pTop)), if (reverse) FaultColor else GoodColor),
                    ),
                    labels = listOf(GuideLabel(norm(shoulderMid(pTop) + Offset(0.1f * leg, 0f)), "${f0(abs(tilt))}°")),
                    ghost = p0,
                ),
                Coach.REVERSE_PIVOT,
            )

            // Vikten i avslutet: hur långt höften kommit från bakre mot främre foten
            val trailX = pt(p0, trailAnkle).x
            val leadX = pt(p0, leadAnkle).x
            val span = (leadX - trailX) * target
            if (span > 0.05f * leg) {
                val share = ((hipMid(pFin).x - trailX) * target / span)
                val weightFault = share < 0.6f
                val ankleY = pt(p0, leadAnkle).y
                val hipY = hipMid(pFin).y
                metrics += metric(
                    "weight", "Vikten i avslutet",
                    "${(share * 100).roundToInt()} % mot främre foten",
                    "Bra: minst 60 %",
                    weightFault, (0.6f - share) / 0.25f, 0.9f, finish,
                    Guide(
                        lines = listOf(
                            GuideLine(norm(Offset(trailX, ankleY)), norm(Offset(trailX, hipY)), RefColor, dashed = true),
                            GuideLine(norm(Offset(leadX, ankleY)), norm(Offset(leadX, hipY)), RefColor, dashed = true),
                            GuideLine(norm(Offset(trailX, hipY)), norm(hipMid(pFin)), if (weightFault) FaultColor else GoodColor, arrow = true),
                        ),
                        labels = listOf(
                            GuideLabel(norm(Offset(leadX, ankleY + 0.08f * leg)), "Främre foten"),
                            GuideLabel(norm(Offset(trailX, ankleY + 0.08f * leg)), "Bakre"),
                        ),
                        ghost = p0,
                    ),
                    Coach.WEIGHT_BACK,
                )
            }

            // Ledarmen vid toppen
            val elbow = lead + 2
            val wrist = lead + 4
            if (pTop.v(lead) > 0.5f && pTop.v(elbow) > 0.5f && pTop.v(wrist) > 0.5f) {
                val armAngle = angle(pt(pTop, lead), pt(pTop, elbow), pt(pTop, wrist))
                val bent = armAngle < 140f
                metrics += metric(
                    "lead-arm", "Ledarmen vid toppen", "${f0(armAngle)}°", "Bra: minst 140°",
                    bent, (140f - armAngle) / 30f, 0.6f, top,
                    Guide(
                        labels = listOf(GuideLabel(norm(pt(pTop, elbow) + Offset(0.05f * leg, 0f)), "${f0(armAngle)}°")),
                        bones = listOf(lead to elbow, elbow to wrist),
                    ),
                    Coach.BENT_LEAD_ARM,
                )
            } else {
                metrics += unknown("lead-arm", "Ledarmen vid toppen", times[top])
            }
        } else {
            // Bakifrån: bollen ligger på den sida där händerna är i uppställningen
            val ball = sign(hands[start].x - hipMid(p0).x).let { if (it == 0f) 1f else it }

            // Höften mot bollen i träffen
            val hip0 = hipMid(p0)
            val hipImp = hipMid(pImp)
            val toBall = cm((hipImp.x - hip0.x) * ball)
            val eeFault = toBall > 5f
            metrics += metric(
                "early-extension", "Höften i träffen",
                if (toBall >= 0) "${f0(toBall)} cm mot bollen" else "${f0(-toBall)} cm bort från bollen",
                "Bra: högst 5 cm mot bollen",
                eeFault, (toBall - 5f) / 8f, 1.2f, impact,
                Guide(
                    lines = listOf(
                        GuideLine(norm(hip0 - Offset(0f, 0.7f * leg)), norm(hip0 + Offset(0f, 0.9f * leg)), RefColor, dashed = true),
                        GuideLine(norm(hip0), norm(Offset(hipImp.x, hip0.y)), if (eeFault) FaultColor else GoodColor, arrow = true),
                    ),
                    labels = listOf(GuideLabel(norm(hip0 - Offset(0f, 0.75f * leg)), "Höften i uppställningen")),
                    ghost = p0,
                ),
                Coach.EARLY_EXTENSION,
            )

            // Ryggvinkeln i träffen jämfört med uppställningen
            fun forwardTilt(p: Pose): Float {
                val s = shoulderMid(p) - hipMid(p)
                return Math.toDegrees(atan2(abs(s.x).toDouble(), (-s.y).toDouble())).toFloat()
            }
            val tilt0 = forwardTilt(p0)
            val tiltImp = forwardTilt(pImp)
            val loss = tilt0 - tiltImp
            val standUp = loss > 10f
            val spine0 = shoulderMid(p0) - hipMid(p0)
            metrics += metric(
                "spine-angle", "Ryggvinkeln i träffen",
                "${f0(tilt0)}° → ${f0(tiltImp)}°",
                "Bra: högst 10° mer upprätt",
                standUp, (loss - 10f) / 10f, 1.1f, impact,
                Guide(
                    lines = listOf(
                        GuideLine(norm(hipMid(pImp)), norm(hipMid(pImp) + spine0), RefColor, dashed = true),
                        GuideLine(norm(hipMid(pImp)), norm(shoulderMid(pImp)), if (standUp) FaultColor else GoodColor),
                    ),
                    labels = listOf(
                        GuideLabel(norm(hipMid(pImp) + spine0 + Offset(0f, -0.05f * leg)), "Uppställningen ${f0(tilt0)}°"),
                        GuideLabel(norm(shoulderMid(pImp) + Offset(0f, 0.12f * leg)), "Nu ${f0(tiltImp)}°", if (standUp) FaultColor else GoodColor),
                    ),
                    ghost = p0,
                ),
                Coach.STAND_UP,
            )

            // Händernas bana: på halva höjden mellan uppställningen och toppen,
            // jämförs var händerna är på väg upp och på väg ned
            val level = (hands[start].y + hands[top].y) / 2
            val upAt = crossing(hands, start, top, level)
            val downAt = crossing(hands, top, impact, level)
            val up = upAt?.first
            val down = downAt?.first
            val blurred = downAt != null && (maxOf(0, downAt.second - 2)..minOf(n - 1, downAt.second + 3)).any { est[it] }
            if (blurred) {
                metrics += unknown("over-the-top", "Händerna i nedsvingen", times[impact], "Kunde inte mätas: händerna är suddiga i nedsvingen")
            } else if (up != null && down != null) {
                val outside = cm((down.x - up.x) * ball)
                val ottFault = outside > 4f
                metrics += metric(
                    "over-the-top", "Händerna i nedsvingen",
                    if (outside >= 0) "${f0(outside)} cm utanför backswingen" else "${f0(-outside)} cm innanför backswingen",
                    "Bra: på eller innanför backswingen",
                    ottFault, (outside - 4f) / 8f, 1.2f, impact,
                    Guide(
                        lines = listOf(
                            GuideLine(norm(Offset(minOf(up.x, down.x) - 0.3f * leg, level)), norm(Offset(maxOf(up.x, down.x) + 0.3f * leg, level)), RefColor, dashed = true),
                            GuideLine(norm(up), norm(down), if (ottFault) FaultColor else GoodColor, arrow = true),
                        ),
                        labels = listOf(
                            GuideLabel(norm(up + Offset(0f, -0.06f * leg)), "Upp", GoodColor),
                            GuideLabel(norm(down + Offset(0f, 0.1f * leg)), "Ned", DownswingColor),
                        ),
                        pathSplitUs = times[top],
                    ),
                    Coach.OVER_THE_TOP,
                )
            } else {
                metrics += unknown("over-the-top", "Händerna i nedsvingen", times[impact])
            }
        }

        val phases = listOf(
            Phase("Uppställning", times[start]),
            Phase("Toppen", times[top]),
            Phase(if (impactApprox) "Träffen ≈" else "Träffen", times[impact]),
            Phase("Avslut", times[finish]),
        )
        return Report(view, guessed, phases, backswingS, downswingS, metrics, (top..finish).any { est[it] })
    }

    private fun unknown(id: String, name: String, timeUs: Long, value: String = "Kunde inte mätas") =
        Metric(id, name, value, "", Status.UNKNOWN, 0f, timeUs, Guide(), null)

    /** Var händerna passerar höjden [level] mellan bild [from] och [to], interpolerat, och bilden före passagen. */
    private fun crossing(hands: List<Offset>, from: Int, to: Int, level: Float): Pair<Offset, Int>? {
        for (i in from until to) {
            val a = hands[i]
            val b = hands[i + 1]
            if ((a.y - level) * (b.y - level) <= 0f && a.y != b.y) {
                val t = (level - a.y) / (b.y - a.y)
                return Offset(a.x + (b.x - a.x) * t, level) to i
            }
        }
        return null
    }

    private fun angle(a: Offset, vertex: Offset, b: Offset): Float {
        val u = a - vertex
        val v = b - vertex
        val len = hypot(u.x, u.y) * hypot(v.x, v.y)
        if (len == 0f) return 180f
        return Math.toDegrees(acos(((u.x * v.x + u.y * v.y) / len).coerceIn(-1f, 1f).toDouble())).toFloat()
    }
}
