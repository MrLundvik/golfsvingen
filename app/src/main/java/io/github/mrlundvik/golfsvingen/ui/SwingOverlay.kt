package io.github.mrlundvik.golfsvingen.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import io.github.mrlundvik.golfsvingen.analysis.DownswingColor
import io.github.mrlundvik.golfsvingen.analysis.GoodColor
import io.github.mrlundvik.golfsvingen.analysis.Guide
import io.github.mrlundvik.golfsvingen.analysis.GuideLine
import io.github.mrlundvik.golfsvingen.pose.HandPoint
import io.github.mrlundvik.golfsvingen.pose.Landmark
import io.github.mrlundvik.golfsvingen.pose.Pose
import io.github.mrlundvik.golfsvingen.pose.PoseTrack
import io.github.mrlundvik.golfsvingen.store.Clip
import java.io.File
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.hypot
import kotlin.math.min

val LeftSide = Color(0xFF7BD389)
val RightSide = Color(0xFF64B5F6)
val MarkColor = Color(0xFFFF6FD8)

enum class Tool { LINE, ANGLE, CIRCLE }

/** Egna markeringar. Punkterna är andelar av bildens bredd och höjd, så de sitter kvar på samma ställe i bilden. */
sealed interface Mark {
    data class Line(val a: Offset, val b: Offset) : Mark
    data class Angle(val a: Offset, val vertex: Offset, val b: Offset) : Mark
    data class Circle(val center: Offset, val edge: Offset) : Mark
}

/** Markeringarna sparas per sving i appens egna filer. */
object MarkStore {
    private fun file(context: Context, clip: Clip) =
        File(File(context.filesDir, "marks").apply { mkdirs() }, "${clip.uri.lastPathSegment}.txt")

    fun load(context: Context, clip: Clip): List<Mark> = runCatching {
        val f = file(context, clip)
        if (!f.exists()) return emptyList()
        f.readLines().mapNotNull { line ->
            val p = line.split(' ')
            val n = p.drop(1).map { it.toFloat() }
            fun o(i: Int) = Offset(n[i], n[i + 1])
            when (p[0]) {
                "L" -> Mark.Line(o(0), o(2))
                "A" -> Mark.Angle(o(0), o(2), o(4))
                "C" -> Mark.Circle(o(0), o(2))
                else -> null
            }
        }
    }.getOrDefault(emptyList())

    fun save(context: Context, clip: Clip, marks: List<Mark>) {
        fun s(o: Offset) = "${o.x} ${o.y}"
        file(context, clip).writeText(
            marks.joinToString("\n") { m ->
                when (m) {
                    is Mark.Line -> "L ${s(m.a)} ${s(m.b)}"
                    is Mark.Angle -> "A ${s(m.a)} ${s(m.vertex)} ${s(m.b)}"
                    is Mark.Circle -> "C ${s(m.center)} ${s(m.edge)}"
                }
            },
        )
    }
}

/** Var videon hamnar i vyn när den skalas så att hela bilden syns. */
private fun videoRect(view: Size, aspect: Float): Rect {
    if (aspect <= 0f || view.width <= 0f || view.height <= 0f) return Rect(Offset.Zero, view)
    val w = min(view.width, view.height * aspect)
    val h = w / aspect
    val left = (view.width - w) / 2
    val top = (view.height - h) / 2
    return Rect(left, top, left + w, top + h)
}

/** Vinkeln vid [vertex] i grader, räknad i bildpunkter så att bredd och höjd väger lika. */
fun angleDeg(a: Offset, vertex: Offset, b: Offset, w: Float, h: Float): Float {
    val ux = (a.x - vertex.x) * w
    val uy = (a.y - vertex.y) * h
    val vx = (b.x - vertex.x) * w
    val vy = (b.y - vertex.y) * h
    val len = hypot(ux, uy) * hypot(vx, vy)
    if (len == 0f) return 0f
    return Math.toDegrees(acos(((ux * vx + uy * vy) / len).coerceIn(-1f, 1f).toDouble())).toFloat()
}

@Composable
fun SwingOverlay(
    modifier: Modifier,
    aspect: Float,
    track: PoseTrack?,
    timeUs: Long,
    showSkeleton: Boolean,
    showPath: Boolean,
    showHead: Boolean,
    marks: List<Mark>,
    tool: Tool?,
    onAdd: (Mark) -> Unit,
    guide: Guide? = null,
) {
    val add by rememberUpdatedState(onAdd)
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val tapped = remember { mutableStateListOf<Offset>() }
    LaunchedEffect(tool) {
        tapped.clear()
        dragStart = null
        dragEnd = null
    }
    val textPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

    fun norm(p: Offset, size: IntSize): Offset {
        val r = videoRect(Size(size.width.toFloat(), size.height.toFloat()), aspect)
        return Offset(((p.x - r.left) / r.width).coerceIn(0f, 1f), ((p.y - r.top) / r.height).coerceIn(0f, 1f))
    }

    val gestures = when (tool) {
        Tool.LINE, Tool.CIRCLE -> Modifier.pointerInput(tool, aspect) {
            detectDragGestures(
                onDragStart = { dragStart = norm(it, size); dragEnd = dragStart },
                onDrag = { change, _ -> dragEnd = norm(change.position, size) },
                onDragEnd = {
                    val a = dragStart
                    val b = dragEnd
                    if (a != null && b != null && hypot(a.x - b.x, a.y - b.y) > 0.01f) {
                        add(if (tool == Tool.LINE) Mark.Line(a, b) else Mark.Circle(a, b))
                    }
                    dragStart = null
                    dragEnd = null
                },
                onDragCancel = { dragStart = null; dragEnd = null },
            )
        }
        Tool.ANGLE -> Modifier.pointerInput(tool, aspect) {
            detectTapGestures { p ->
                tapped += norm(p, size)
                if (tapped.size == 3) {
                    add(Mark.Angle(tapped[0], tapped[1], tapped[2]))
                    tapped.clear()
                }
            }
        }
        null -> Modifier
    }

    Canvas(modifier.then(gestures)) {
        val r = videoRect(size, aspect)
        val pxy: (Float, Float) -> Offset = { x, y -> Offset(r.left + x * r.width, r.top + y * r.height) }
        val pxo: (Offset) -> Offset = { o -> pxy(o.x, o.y) }
        textPaint.textSize = 15.sp.toPx()

        val pose = track?.at(timeUs)
        // Uppställningen som skugga, bakom allt annat
        guide?.ghost?.let { drawSkeleton(it, pxy, ghost = true) }
        if (track != null) {
            if (showHead) track.address?.let { start ->
                val (hx, hy, hr) = track.head(start)
                drawCircle(
                    Color.White.copy(alpha = 0.85f),
                    radius = hr * r.width,
                    center = pxy(hx, hy),
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                )
                if (pose != null) {
                    val (cx, cy, _) = track.head(pose)
                    drawCircle(Sun, radius = 7f, center = pxy(cx, cy))
                }
            }
            if (showPath || guide?.pathSplitUs != null) drawHandPath(track, timeUs, pxy, guide?.pathSplitUs)
            if (showSkeleton && pose != null) {
                drawSkeleton(pose, pxy)
                for ((i, j) in guide?.bones.orEmpty()) {
                    drawLine(Sun, pxy(pose.x(i), pose.y(i)), pxy(pose.x(j), pose.y(j)), strokeWidth = 11f, cap = StrokeCap.Round)
                }
                val tilt = track.spineTilt(pose)
                drawLabel("Ryggen ${tilt.toInt()}°", Offset(r.left + 12f, r.bottom - 16f), textPaint)
            }
        }

        if (guide != null) {
            for (line in guide.lines) drawGuideLine(line, pxo)
            for (label in guide.labels) {
                val paint = Paint(textPaint).apply { color = label.color.toArgb() }
                drawLabel(label.text, pxo(label.at), paint)
            }
        }

        val stroke = 5f
        for (m in marks) drawMark(m, r, stroke, textPaint, pxo)
        // Det som ritas just nu
        val a = dragStart
        val b = dragEnd
        if (a != null && b != null) {
            when (tool) {
                Tool.LINE -> drawMark(Mark.Line(a, b), r, stroke, textPaint, pxo)
                Tool.CIRCLE -> drawMark(Mark.Circle(a, b), r, stroke, textPaint, pxo)
                else -> {}
            }
        }
        for ((i, p) in tapped.withIndex()) {
            drawCircle(MarkColor, radius = 9f, center = pxo(p))
            if (i > 0) drawLine(MarkColor, pxo(tapped[i - 1]), pxo(p), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawSkeleton(p: Pose, px: (Float, Float) -> Offset, ghost: Boolean = false) {
    for ((i, j) in Landmark.BONES) {
        if (p.v(i) < 0.2f || p.v(j) < 0.2f) continue
        val color = when {
            ghost -> Color.White.copy(alpha = 0.35f)
            i % 2 == 1 && j % 2 == 1 -> LeftSide.copy(alpha = 0.9f)
            i % 2 == 0 && j % 2 == 0 -> RightSide.copy(alpha = 0.9f)
            else -> Color.White.copy(alpha = 0.9f)
        }
        drawLine(color, px(p.x(i), p.y(i)), px(p.x(j), p.y(j)), strokeWidth = if (ghost) 4f else 6f, cap = StrokeCap.Round)
    }
    if (ghost) return
    for (i in Landmark.JOINTS) {
        if (p.v(i) < 0.2f) continue
        drawCircle(Color.White, radius = 7f, center = px(p.x(i), p.y(i)))
    }
    // Ryggen, från höfternas mitt till axlarnas mitt
    val hip = px((p.x(Landmark.HIP_L) + p.x(Landmark.HIP_R)) / 2, (p.y(Landmark.HIP_L) + p.y(Landmark.HIP_R)) / 2)
    val sh = px((p.x(Landmark.SHOULDER_L) + p.x(Landmark.SHOULDER_R)) / 2, (p.y(Landmark.SHOULDER_L) + p.y(Landmark.SHOULDER_R)) / 2)
    drawLine(Sun, hip, sh, strokeWidth = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
}

private fun DrawScope.drawHandPath(track: PoseTrack, timeUs: Long, px: (Float, Float) -> Offset, splitUs: Long?) {
    val points = track.handPath
    if (points.size < 2) return
    if (splitUs != null) {
        // Backswingen grön och nedsvingen orange, för att jämföra banorna
        drawPathPart(points, px, GoodColor, 6f) { it.t <= splitUs }
        drawPathPart(points, px, DownswingColor, 6f) { it.t >= splitUs }
        return
    }
    drawPathPart(points, px, Sun.copy(alpha = 0.3f), 4f) { true }
    drawPathPart(points, px, Sun, 5f) { it.t <= timeUs }
}

/** Ritar de punkter i följd som [include] godtar. */
private fun DrawScope.drawPathPart(
    points: List<HandPoint>,
    px: (Float, Float) -> Offset,
    color: Color,
    width: Float,
    include: (HandPoint) -> Boolean,
) {
    val path = Path()
    var started = false
    for (p in points) {
        if (!include(p)) continue
        val o = px(p.x, p.y)
        if (!started) { path.moveTo(o.x, o.y); started = true } else path.lineTo(o.x, o.y)
    }
    if (started) drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

private fun DrawScope.drawMark(m: Mark, r: Rect, stroke: Float, paint: Paint, px: (Offset) -> Offset) {
    when (m) {
        is Mark.Line -> {
            drawLine(MarkColor, px(m.a), px(m.b), strokeWidth = stroke, cap = StrokeCap.Round)
        }
        is Mark.Circle -> {
            val c = px(m.center)
            val e = px(m.edge)
            drawCircle(MarkColor, radius = hypot(e.x - c.x, e.y - c.y), center = c, style = Stroke(width = stroke))
        }
        is Mark.Angle -> {
            val v = px(m.vertex)
            drawLine(MarkColor, px(m.a), v, strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(MarkColor, v, px(m.b), strokeWidth = stroke, cap = StrokeCap.Round)
            drawCircle(MarkColor, radius = 8f, center = v)
            val deg = angleDeg(m.a, m.vertex, m.b, r.width, r.height)
            drawLabel("${deg.toInt()}°", Offset(v.x + 14f, v.y - 14f), paint)
        }
    }
}

private fun DrawScope.drawGuideLine(line: GuideLine, px: (Offset) -> Offset) {
    val a = px(line.a)
    val b = px(line.b)
    drawLine(
        line.color, a, b, strokeWidth = 5f, cap = StrokeCap.Round,
        pathEffect = if (line.dashed) PathEffect.dashPathEffect(floatArrayOf(16f, 10f)) else null,
    )
    if (line.arrow && hypot(b.x - a.x, b.y - a.y) > 8f) {
        val angle = atan2(b.y - a.y, b.x - a.x)
        for (side in listOf(-1f, 1f)) {
            val back = angle + Math.PI.toFloat() + side * 0.45f
            drawLine(line.color, b, Offset(b.x + 22f * cos(back), b.y + 22f * sin(back)), strokeWidth = 5f, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawLabel(text: String, at: Offset, paint: Paint) {
    drawIntoCanvas { it.nativeCanvas.drawText(text, at.x, at.y, paint) }
}
