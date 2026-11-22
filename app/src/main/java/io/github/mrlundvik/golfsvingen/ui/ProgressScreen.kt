package io.github.mrlundvik.golfsvingen.ui

import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mrlundvik.golfsvingen.analysis.Advice
import io.github.mrlundvik.golfsvingen.analysis.Coach
import io.github.mrlundvik.golfsvingen.analysis.FaultColor
import io.github.mrlundvik.golfsvingen.analysis.GoodColor
import io.github.mrlundvik.golfsvingen.analysis.SwingReport
import io.github.mrlundvik.golfsvingen.analysis.View
import io.github.mrlundvik.golfsvingen.pose.PoseAnalyzer
import io.github.mrlundvik.golfsvingen.pose.PoseStore
import io.github.mrlundvik.golfsvingen.store.Clip
import io.github.mrlundvik.golfsvingen.store.ClipStore
import io.github.mrlundvik.golfsvingen.store.History
import io.github.mrlundvik.golfsvingen.store.SavedMetric
import io.github.mrlundvik.golfsvingen.store.SavedReport
import io.github.mrlundvik.golfsvingen.store.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val SV = Locale("sv", "SE")
private val pointDate = SimpleDateFormat("d MMM HH:mm", SV)

/** Hur många av de senaste svingarna som räknas när appen väljer vad du ska träna på. */
private const val RECENT = 10

/** Kortens bakgrund, som ringen runt punkterna i diagrammet har, så att de syns där de ligger på linjen. */
private val CardColor = Color(0xFF1B221E)

/** Ett fel som återkommer i de senaste svingarna. */
private class Focus(val title: String, val advice: Advice?, val faults: Int, val of: Int, val weight: Double)

/** En mätning i en sving, med svingen den hör till. */
private class Point(val report: SavedReport, val metric: SavedMetric)

/**
 * Utvecklingen över tid: överst det som återkommer i de senaste svingarna, med övningar, och sedan en kurva per
 * mätning. Bygger på rapporterna som sparas när svingarna analyseras.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(onBack: () -> Unit, onOpen: (Clip) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val legCm = remember { Settings.legCm(context) }
    var clips by remember { mutableStateOf<List<Clip>>(emptyList()) }
    var reports by remember { mutableStateOf<List<SavedReport>>(emptyList()) }
    var missing by remember { mutableStateOf<List<Clip>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val reload: suspend () -> Unit = {
        val (c, r, m) = withContext(Dispatchers.IO) {
            val all = ClipStore.list(context)
            val existing = all.map { it.uri }.toSet()
            // Rapporter räknade med en annan längd visas inte, utan räknas om
            val current = History.load(context).filter { it.uri in existing && abs(it.legCm - legCm) < 0.01f }
            Triple(all, current, all.filter { !History.isCurrent(context, it.uri, legCm) })
        }
        clips = c
        reports = r
        missing = m
        loaded = true
    }
    LaunchedEffect(Unit) { reload() }

    // Svingar som inte analyserats analyseras en i taget
    var job by remember { mutableStateOf<Job?>(null) }
    var batchIndex by remember { mutableIntStateOf(0) }
    var batchTotal by remember { mutableIntStateOf(0) }
    var batchProgress by remember { mutableFloatStateOf(0f) }
    val analyzeMissing: () -> Unit = {
        val todo = missing
        batchTotal = todo.size
        job = scope.launch {
            try {
                for ((i, clip) in todo.withIndex()) {
                    batchIndex = i
                    batchProgress = 0f
                    try {
                        val track = withContext(Dispatchers.IO) { PoseStore.load(context, clip) }
                            ?: PoseAnalyzer.analyze(context, clip) { batchProgress = it }
                                .also { withContext(Dispatchers.IO) { PoseStore.save(context, clip, it) } }
                        val report = withContext(Dispatchers.Default) { SwingReport.build(track.cleaned(), null, legCm) }
                        withContext(Dispatchers.IO) {
                            if (report != null) History.save(context, clip, report) else History.saveFailed(context, clip)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        withContext(Dispatchers.IO) { runCatching { History.saveFailed(context, clip) } }
                    }
                    reload()
                }
            } finally {
                job = null
            }
        }
    }

    val views = reports.groupBy { it.view }
    var view by remember { mutableStateOf<View?>(null) }
    val shown = view?.takeIf { it in views } ?: views.maxByOrNull { it.value.size }?.key
    val focus = remember(reports) { focusOf(reports) }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Mina svingar") }
            Text("Utveckling", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (missing.isNotEmpty() || job != null) item {
                Card {
                    if (job != null) {
                        Text("Analyserar ${batchIndex + 1} av $batchTotal · ${(batchProgress * 100).toInt()} %", fontSize = 14.sp)
                        Text("Stanna kvar på sidan tills det är klart.", fontSize = 12.sp, color = Muted)
                        TextButton(onClick = { job?.cancel() }) { Text("Avbryt") }
                    } else {
                        val n = missing.size
                        Text(
                            if (n == 1) "En sving saknar en aktuell rapport." else "$n svingar saknar en aktuell rapport.",
                            fontSize = 14.sp,
                        )
                        Text(
                            "Antingen är de inte analyserade än, vilket tar ungefär en halv minut per sving, " +
                                "eller så har längden ändrats, och då räknas de om på någon sekund.",
                            fontSize = 12.sp,
                            color = Muted,
                        )
                        Button(onClick = analyzeMissing, modifier = Modifier.padding(top = 6.dp)) { Text("Analysera dem nu") }
                    }
                }
            }
            if (loaded && reports.isEmpty() && job == null) item {
                Text(
                    "Här syns hur svingen utvecklas när du har analyserat några svingar. " +
                        "Öppna en sving, eller analysera alla ovan.",
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            if (reports.isNotEmpty()) {
                item { SectionTitle("Att träna på", "Det som återkommer i de ${min(RECENT, reports.size)} senaste svingarna") }
                if (focus.isEmpty()) item {
                    Card { Text("Inget fel återkommer i de senaste svingarna.", fontSize = 14.sp) }
                }
                items(focus.size) { i -> FocusCard(i + 1, focus[i]) }

                item {
                    SectionTitle("Mätningarna över tid", "En punkt per sving, äldst till vänster. Fylld punkt är bra, ring är fel, och det gröna fältet är målet.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (v in View.entries) {
                            val n = views[v]?.size ?: 0
                            if (n > 0) FilterChip(selected = v == shown, onClick = { view = v }, label = { Text("${v.label} · $n") })
                        }
                    }
                    if (views.size == 1) {
                        Text(
                            "Mätningarna skiljer sig mellan framifrån och bakifrån, så svingarna visas var för sig.",
                            fontSize = 12.sp,
                            color = Muted,
                        )
                    }
                }
                val series = shown?.let { seriesOf(views[it].orEmpty()) }.orEmpty()
                items(series, key = { "${shown?.name}-${it.first}" }) { (_, points) ->
                    TrendCard(points) { uri -> clips.firstOrNull { it.uri == uri }?.let(onOpen) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private val Muted = Color.White.copy(alpha = 0.55f)

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) { content() }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(top = 12.dp, bottom = 2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, fontSize = 12.sp, color = Muted)
    }
}

/**
 * Fel som finns i minst två av de senaste svingarna och i minst tre av tio av dem som kunde mätas,
 * rangordnade efter hur ofta de kommer och hur allvarliga de är. Högst tre, för att ha något att fokusera på.
 */
private fun focusOf(reports: List<SavedReport>): List<Focus> {
    val recent = reports.takeLast(RECENT)
    val ids = recent.flatMap { r -> r.metrics.map { it.id } }.distinct()
    return ids.mapNotNull { id ->
        val measured = recent.mapNotNull { r -> r.metrics.firstOrNull { it.id == id } }
        val faulty = measured.filter { it.fault }
        if (faulty.size < 2 || faulty.size * 10 < measured.size * 3) return@mapNotNull null
        val last = faulty.last()
        val weight = faulty.size.toDouble() / measured.size * faulty.map { it.score.toDouble() }.average()
        Focus(last.title, Coach.byKey(last.advice), faulty.size, measured.size, weight)
    }.sortedByDescending { it.weight }.take(3)
}

/** Mätningarna i svingarna från ett håll, i samma ordning som i den senaste rapporten. */
private fun seriesOf(reports: List<SavedReport>): List<Pair<String, List<Point>>> {
    val order = reports.reversed().flatMap { r -> r.metrics.map { it.id } }.distinct()
    return order.map { id -> id to reports.mapNotNull { r -> r.metrics.firstOrNull { it.id == id }?.let { Point(r, it) } } }
        .filter { it.second.isNotEmpty() }
}

@Composable
private fun FocusCard(rank: Int, f: Focus) {
    var open by remember { mutableStateOf(rank == 1) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(10.dp))
            .clickable { open = !open }
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$rank.", color = FaultColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(f.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("I ${f.faults} av ${f.of} svingar där det kunde mätas", fontSize = 13.sp, color = Muted)
            }
        }
        if (open) f.advice?.let { AdviceDetails(it, fault = true) }
        else Text("Tryck för förklaring och övningar", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
    }
}

/** Hur långt ett värde är utanför målet. Noll inom målet. Gränser på ±90 eller mer betyder att målet är öppet åt det hållet. */
private fun outside(m: SavedMetric) = max(0f, max(m.goodLow - m.number, m.number - m.goodHigh))

/** De senaste svingarna mot dem före, i hur långt utanför målet de ligger i snitt. */
private fun trend(points: List<Point>): String? {
    if (points.size < 4) return null
    val n = min(5, points.size / 2)
    val recent = points.takeLast(n).map { outside(it.metric) }.average()
    val before = points.dropLast(n).takeLast(n).map { outside(it.metric) }.average()
    return when {
        recent < 0.5 && before < 0.5 -> "Inom målet både i de $n senaste och i de $n förra svingarna"
        recent < before * 0.8 -> "Närmare målet i de $n senaste svingarna än i de $n förra"
        recent > before * 1.2 + 0.5 -> "Längre från målet i de $n senaste svingarna än i de $n förra"
        else -> "Ungefär som förut, i de $n senaste mot de $n förra svingarna"
    }
}

@Composable
private fun TrendCard(points: List<Point>, onOpen: (Uri) -> Unit) {
    var selected by remember(points.size) { mutableStateOf<Int?>(null) }
    val last = points.last().metric
    Card {
        Text(last.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (last.fault) "! " else "✓ ", color = if (last.fault) FaultColor else GoodColor, fontWeight = FontWeight.Bold)
            Text("Senast: ${last.value}", fontSize = 13.sp)
        }
        trend(points)?.let { Text(it, fontSize = 12.sp, color = Muted) }
        TrendChart(points, selected) { selected = it }
        val pick = selected
        if (pick != null && pick in points.indices) {
            val p = points[pick]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${pointDate.format(Date(p.report.takenSec * 1000))} · ${p.metric.value}",
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onOpen(p.report.uri) }) { Text("Öppna svingen") }
            }
        } else {
            Text("Tryck på en punkt för att se den svingen", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
        }
    }
}

/**
 * En punkt per sving och en tunn linje mellan dem, med målet som ett svagt grönt fält. Fylld punkt är bra och
 * ring är fel, så att det inte bara är färgen som skiljer. Ett tryck väljer punkten närmast i sidled.
 */
@Composable
private fun TrendChart(points: List<Point>, selected: Int?, onSelect: (Int) -> Unit) {
    val values = points.map { it.metric.number }
    val low = points.last().metric.goodLow
    val high = points.last().metric.goodHigh
    val limits = listOf(low, high).filter { abs(it) < 90f }
    var yMin = (values + limits).min()
    var yMax = (values + limits).max()
    if (yMax - yMin < 1f) { yMin -= 1f; yMax += 1f }
    val pad = (yMax - yMin) * 0.12f
    yMin -= pad
    yMax += pad
    val decimals = if (points.last().metric.unit.startsWith(":")) 1 else 0
    fun label(v: Float) = String.format(SV, "%.${decimals}f", v)

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(130.dp)
            .padding(vertical = 6.dp)
            .pointerInput(points.size) {
                detectTapGestures { tap ->
                    val left = 44.dp.toPx()
                    val right = size.width - 12.dp.toPx()
                    val i = if (points.size == 1) 0
                    else (((tap.x - left) / (right - left)) * (points.size - 1)).let { Math.round(it) }.coerceIn(0, points.size - 1)
                    onSelect(i)
                }
            },
    ) {
        val left = 44.dp.toPx()
        val right = size.width - 12.dp.toPx()
        val top = 4.dp.toPx()
        val bottom = size.height - 4.dp.toPx()
        fun x(i: Int) = if (points.size == 1) (left + right) / 2 else left + (right - left) * i / (points.size - 1)
        fun y(v: Float) = bottom - (v - yMin) / (yMax - yMin) * (bottom - top)
        val text = Paint().apply {
            isAntiAlias = true
            textSize = 11.sp.toPx()
            color = Muted.toArgb()
            textAlign = Paint.Align.RIGHT
        }

        // Målet
        val bandLow = max(low, yMin)
        val bandHigh = min(high, yMax)
        if (bandHigh > bandLow) {
            drawRect(GoodColor.copy(alpha = 0.10f), Offset(left, y(bandHigh)), Size(right - left, y(bandLow) - y(bandHigh)))
        }
        // Gränserna för målet, med värdet till vänster. Annars högsta och lägsta värdet, så att skalan syns
        val marks = limits.filter { it in yMin..yMax }.ifEmpty { listOf(values.min(), values.max()).distinct() }
        for (v in marks) {
            drawLine(Color.White.copy(alpha = 0.14f), Offset(left, y(v)), Offset(right, y(v)), strokeWidth = 1f)
            drawIntoCanvas { it.nativeCanvas.drawText(label(v), left - 6.dp.toPx(), y(v) + 4.dp.toPx(), text) }
        }

        // Linjen
        if (points.size > 1) {
            val path = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(path, Color.White.copy(alpha = 0.5f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        // Punkterna, med en ring i kortets färg så att de syns där de ligger på linjen
        points.forEachIndexed { i, p ->
            val c = Offset(x(i), y(p.metric.number))
            val r = (if (i == selected) 6.dp else 4.dp).toPx()
            drawCircle(CardColor, r + 2.dp.toPx(), c)
            if (p.metric.fault) {
                drawCircle(FaultColor, r - 1.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            } else {
                drawCircle(GoodColor, r, c)
            }
        }
    }
}
