package io.github.mrlundvik.golfsvingen.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.mrlundvik.golfsvingen.analysis.Advice
import io.github.mrlundvik.golfsvingen.analysis.FaultColor
import io.github.mrlundvik.golfsvingen.analysis.GoodColor
import io.github.mrlundvik.golfsvingen.analysis.Metric
import io.github.mrlundvik.golfsvingen.analysis.Report
import io.github.mrlundvik.golfsvingen.analysis.Status
import io.github.mrlundvik.golfsvingen.analysis.View
import java.util.Locale

private val SV = Locale("sv", "SE")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportPanel(
    report: Report?,
    footer: String,
    focusId: String?,
    onView: (View) -> Unit,
    onSeek: (Long) -> Unit,
    onShow: (Metric) -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Svingrapport", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onExport) { Text("Dela mätdata") }
            TextButton(onClick = onClose) { Text("Stäng") }
        }
        if (report == null) {
            Text(
                "Hittar ingen hel sving i klippet. Klippet behöver börja i uppställningen och sluta efter träffen, och hela kroppen ska synas.",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.8f),
            )
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (v in View.entries) {
                FilterChip(selected = report.view == v, onClick = { onView(v) }, label = { Text(v.label) })
            }
            Text(
                if (report.view == report.viewGuessed) "gissat från bilden" else "ändrat av dig",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.5f),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (phase in report.phases) {
                AssistChip(
                    onClick = { onSeek(phase.timeUs) },
                    label = { Text(String.format(SV, "%s %.2f s", phase.name, phase.timeUs / 1e6)) },
                )
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            val faults = report.faults
            if (faults.isEmpty()) {
                Text(
                    "Inga av de uppmätta felen syns i den här svingen.",
                    fontSize = 13.sp,
                    color = GoodColor,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else {
                Text(
                    if (faults.size == 1) "Det här är viktigast att jobba med" else "Viktigast att jobba med, i ordning",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                faults.forEachIndexed { i, m -> MetricCard(m, i + 1, focusId == m.id, onShow) }
            }
            Text(
                "Övriga mätningar",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
            for (m in report.others) MetricCard(m, null, focusId == m.id, onShow)
            Text(
                "Mätningarna görs i den platta bilden och är ungefärliga. Längderna räknas om till centimeter med benlängden, " +
                    "höft till fotled, som antas vara 80 cm. Jämför helst svingar som filmats från samma ställe. $footer",
                fontSize = 11.sp,
                color = Color.White.copy(alpha = 0.45f),
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
        }
    }
}

@Composable
private fun MetricCard(m: Metric, rank: Int?, focused: Boolean, onShow: (Metric) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val color = when (m.status) {
        Status.FAULT -> FaultColor
        Status.GOOD -> GoodColor
        Status.UNKNOWN -> Color.White.copy(alpha = 0.5f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(Color.White.copy(alpha = if (focused) 0.12f else 0.06f), RoundedCornerShape(10.dp))
            .clickable(enabled = m.advice != null || m.status != Status.UNKNOWN) { open = !open }
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val mark = when (m.status) {
                Status.FAULT -> rank?.let { "$it." } ?: "!"
                Status.GOOD -> "✓"
                Status.UNKNOWN -> "–"
            }
            Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(m.value, fontSize = 13.sp, color = color)
            }
            if (m.status != Status.UNKNOWN) {
                TextButton(onClick = { onShow(m) }) { Text("Visa") }
            }
        }
        if (open) {
            if (m.goal.isNotEmpty()) Text(m.goal, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            m.advice?.let { AdviceDetails(it, fault = m.status == Status.FAULT) }
            Spacer(Modifier.padding(top = 4.dp))
            OutlinedButton(onClick = { onShow(m) }, modifier = Modifier.padding(top = 6.dp)) { Text("Visa i videon") }
        } else if (m.status == Status.FAULT) {
            Text("Tryck för förklaring och övningar", fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f))
        }
    }
}

/** Förklaringen till ett råd: vad felet innebär, hur det ska se ut, och övningar med länk till filmer. */
@Composable
fun AdviceDetails(advice: Advice, fault: Boolean) {
    val context = LocalContext.current
    if (fault) Paragraph(advice.why)
    Paragraph("Så ska det se ut: " + advice.ideal)
    advice.caveat?.let { Paragraph(it, dim = true) }
    if (!fault) return
    Text("Övningar", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
    for (d in advice.drills) {
        Column(Modifier.padding(top = 6.dp)) {
            Text(d.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(d.how, fontSize = 13.sp, color = Color.White.copy(alpha = 0.8f))
            d.search?.let { q ->
                Text(
                    "Se filmer om övningen ↗",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable {
                            val url = "https://www.youtube.com/results?search_query=" + Uri.encode(q)
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        },
                )
            }
        }
    }
}

@Composable
private fun Paragraph(text: String, dim: Boolean = false) {
    Text(
        text,
        fontSize = 13.sp,
        color = Color.White.copy(alpha = if (dim) 0.55f else 0.85f),
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * En enda mätning, i stället för hela rapporten, så att videon får plats. Pilarna går till föregående
 * och nästa mätning, och chipsen väljer vad som ritas på videon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FocusCard(
    report: Report,
    metric: Metric,
    layers: List<Triple<String, Boolean, () -> Unit>>,
    onPick: (Metric) -> Unit,
    onBack: () -> Unit,
) {
    val list = report.faults + report.others.filter { it.status != Status.UNKNOWN }
    val i = list.indexOfFirst { it.id == metric.id }
    val m = list.getOrNull(i) ?: metric
    val color = when (m.status) {
        Status.FAULT -> FaultColor
        Status.GOOD -> GoodColor
        Status.UNKNOWN -> Color.White.copy(alpha = 0.5f)
    }
    Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Rapporten") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onPick(list[i - 1]) }, enabled = i > 0) { Text("◀ Förra") }
            TextButton(onClick = { onPick(list[i + 1]) }, enabled = i >= 0 && i < list.lastIndex) { Text("Nästa ▶") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            val rank = report.faults.indexOfFirst { it.id == m.id }
            val mark = when (m.status) {
                Status.FAULT -> if (rank >= 0) "${rank + 1}." else "!"
                Status.GOOD -> "✓"
                Status.UNKNOWN -> "–"
            }
            Text(mark, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(m.value, fontSize = 13.sp, color = color)
                if (m.goal.isNotEmpty()) Text(m.goal, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f))
            }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((label, on, toggle) in layers) {
                FilterChip(selected = on, onClick = toggle, label = { Text(label) })
            }
        }
    }
}
