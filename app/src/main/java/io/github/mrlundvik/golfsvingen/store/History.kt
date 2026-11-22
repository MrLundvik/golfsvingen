package io.github.mrlundvik.golfsvingen.store

import android.content.Context
import android.net.Uri
import io.github.mrlundvik.golfsvingen.analysis.Coach
import io.github.mrlundvik.golfsvingen.analysis.Report
import io.github.mrlundvik.golfsvingen.analysis.Status
import io.github.mrlundvik.golfsvingen.analysis.View
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** En mätning ur en svings rapport, sparad för utvecklingen över tid. */
class SavedMetric(
    val id: String,
    val name: String,
    val title: String,
    val value: String,
    val number: Float,
    val unit: String,
    val goodLow: Float,
    val goodHigh: Float,
    val fault: Boolean,
    val score: Float,
    /** Vilket råd felet har, se [Coach.byKey]. */
    val advice: String?,
)

/** Det som behövs av en svings rapport för att följa utvecklingen, utan att analysera om den. */
class SavedReport(val uri: Uri, val takenSec: Long, val view: View, val metrics: List<SavedMetric>)

/** Rapporterna sparas som små filer i appens egna filer, en per sving, när rapporten räknas fram. */
object History {
    private fun dir(context: Context) = File(context.filesDir, "history").apply { mkdirs() }
    private fun file(context: Context, uri: Uri) = File(dir(context), "${uri.lastPathSegment}.json")

    fun save(context: Context, clip: Clip, report: Report) {
        val metrics = JSONArray()
        for (m in report.metrics) {
            val number = m.number ?: continue
            val good = m.good ?: continue
            metrics.put(
                JSONObject()
                    .put("id", m.id).put("name", m.name).put("title", m.title).put("value", m.value)
                    .put("number", number.toDouble()).put("unit", m.unit)
                    .put("goodLow", good.start.toDouble()).put("goodHigh", good.endInclusive.toDouble())
                    .put("fault", m.status == Status.FAULT).put("score", m.score.toDouble())
                    .put("advice", Coach.key(m.advice) ?: JSONObject.NULL),
            )
        }
        val json = JSONObject()
            .put("uri", clip.uri.toString()).put("takenSec", clip.takenSec).put("view", report.view.name)
            .put("metrics", metrics)
        val f = file(context, clip.uri)
        val tmp = File(f.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(f)
    }

    /** Svingen är analyserad men gav ingen rapport, till exempel för att klippet inte börjar i uppställningen. */
    fun saveFailed(context: Context, clip: Clip) {
        val json = JSONObject().put("uri", clip.uri.toString()).put("takenSec", clip.takenSec).put("failed", true)
        file(context, clip.uri).writeText(json.toString())
    }

    fun delete(context: Context, uri: Uri) {
        file(context, uri).delete()
    }

    fun has(context: Context, uri: Uri) = file(context, uri).exists()

    /** Alla sparade rapporter, äldst först. */
    fun load(context: Context): List<SavedReport> =
        (dir(context).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).mapNotNull { f ->
            runCatching {
                val json = JSONObject(f.readText())
                if (json.optBoolean("failed")) return@runCatching null
                val list = json.getJSONArray("metrics")
                SavedReport(
                    Uri.parse(json.getString("uri")),
                    json.getLong("takenSec"),
                    View.valueOf(json.getString("view")),
                    List(list.length()) { i ->
                        val m = list.getJSONObject(i)
                        SavedMetric(
                            m.getString("id"), m.getString("name"), m.getString("title"), m.getString("value"),
                            m.getDouble("number").toFloat(), m.getString("unit"),
                            m.getDouble("goodLow").toFloat(), m.getDouble("goodHigh").toFloat(),
                            m.getBoolean("fault"), m.getDouble("score").toFloat(),
                            if (m.isNull("advice")) null else m.getString("advice"),
                        )
                    },
                )
            }.getOrNull()
        }.sortedBy { it.takenSec }
}
