package io.github.mrlundvik.golfsvingen.ui

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import io.github.mrlundvik.golfsvingen.R
import io.github.mrlundvik.golfsvingen.analysis.FaultColor
import io.github.mrlundvik.golfsvingen.analysis.GoodColor
import io.github.mrlundvik.golfsvingen.analysis.Metric
import io.github.mrlundvik.golfsvingen.analysis.Report
import io.github.mrlundvik.golfsvingen.analysis.Status
import io.github.mrlundvik.golfsvingen.analysis.SwingReport
import io.github.mrlundvik.golfsvingen.analysis.TimeMap
import io.github.mrlundvik.golfsvingen.analysis.alignTo
import io.github.mrlundvik.golfsvingen.pose.PoseAnalyzer
import io.github.mrlundvik.golfsvingen.pose.PoseStore
import io.github.mrlundvik.golfsvingen.pose.PoseTrack
import io.github.mrlundvik.golfsvingen.store.Settings
import io.github.mrlundvik.golfsvingen.store.Clip
import io.github.mrlundvik.golfsvingen.store.Reference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

private val SPEEDS = listOf(1f to "1×", 0.25f to "¼", 0.125f to "⅛", 0.0625f to "1/16")
private val SV = Locale("sv", "SE")
private val dateFormat = SimpleDateFormat("EEE d MMM HH:mm", SV)

/** Felet i B:s läge, i millisekunder, som rättas genom att ändra farten. Större fel rättas med ett hopp. */
private const val SYNC_SEEK_MS = 250L

/** Kroppsanalysen för en sving: sparad sedan tidigare, eller görs nu. */
private class PoseState {
    var track by mutableStateOf<PoseTrack?>(null)
    var progress by mutableFloatStateOf(0f)
    var analyzing by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun rememberPose(context: Context, clip: Clip): PoseState {
    val state = remember(clip.uri) { PoseState() }
    LaunchedEffect(clip.uri) {
        val cached = withContext(Dispatchers.IO) { PoseStore.load(context, clip) }
        if (cached != null) {
            state.track = cached
            return@LaunchedEffect
        }
        state.analyzing = true
        try {
            val result = PoseAnalyzer.analyze(context, clip) { state.progress = it }
            withContext(Dispatchers.IO) { PoseStore.save(context, clip, result) }
            state.track = result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            state.error = e.message ?: e.javaClass.simpleName
        } finally {
            state.analyzing = false
        }
    }
    return state
}

@OptIn(UnstableApi::class)
@Composable
private fun rememberPlayer(context: Context, clip: Clip): ExoPlayer {
    val player = remember(clip.uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(clip.uri))
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_OFF
            setPlaybackSpeed(0.125f)
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

private class VideoInfo(val aspect: Float, val fps: Double)

@Composable
private fun rememberInfo(context: Context, clip: Clip): VideoInfo {
    val info by produceState(VideoInfo(0f, 240.0), clip.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, clip.uri)
                    fun num(key: Int) = r.extractMetadata(key)?.toDoubleOrNull()
                    val w = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat()
                    val h = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
                    val rot = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                    val count = num(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    val ms = num(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    VideoInfo(
                        if (rot == 90 || rot == 270) h / w else w / h,
                        if (count != null && ms != null && ms > 0) count / (ms / 1000.0) else 240.0,
                    )
                }
            }.getOrDefault(VideoInfo(0f, 240.0))
        }
    }
    return info
}

/**
 * Två svingar bredvid varandra, eller den andra svingens skelett ovanpå den första. A styr: B följer med
 * så att faserna kommer samtidigt, genom att B:s fart ändras mellan faserna.
 */
@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(a: Clip, b: Clip, onBack: () -> Unit, onOpen: (Clip) -> Unit) {
    val context = LocalContext.current
    val playerA = rememberPlayer(context, a)
    val playerB = rememberPlayer(context, b)
    val poseA = rememberPose(context, a)
    val poseB = rememberPose(context, b)
    val infoA = rememberInfo(context, a)
    val infoB = rememberInfo(context, b)
    val cleanA = remember(poseA.track) { poseA.track?.cleaned() }
    val cleanB = remember(poseB.track) { poseB.track?.cleaned() }
    val legCm = remember { Settings.legCm(context) }
    val reportA = remember(cleanA) { cleanA?.let { runCatching { SwingReport.build(it, null, legCm) }.getOrNull() } }
    val reportB = remember(cleanB) { cleanB?.let { runCatching { SwingReport.build(it, null, legCm) }.getOrNull() } }
    // En vänsterhänt och en högerhänt sving jämförs spegelvända, så att de svingar åt samma håll
    var mirrorB by remember(reportA, reportB) {
        mutableStateOf(reportA != null && reportB != null && reportA.leftHanded != reportB.leftHanded)
    }
    val trackB = remember(cleanB, mirrorB) { if (mirrorB) cleanB?.mirrored() else cleanB }
    val reference = remember { Reference.get(context) }

    var inStep by remember { mutableStateOf(true) }
    var overlay by remember { mutableStateOf(false) }
    var showSkeleton by remember { mutableStateOf(true) }
    var showPath by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(0.125f) }
    var playing by remember { mutableStateOf(false) }
    var posA by remember { mutableLongStateOf(0L) }
    var posB by remember { mutableLongStateOf(0L) }
    var durA by remember { mutableLongStateOf(0L) }
    var durB by remember { mutableLongStateOf(0L) }

    val map = remember(reportA, reportB, inStep) { TimeMap.of(reportA, reportB, inStep) }
    // B:s skelett flyttat till A:s bild och tider, för läget Ovanpå
    val aligned = remember(cleanA, trackB, reportA, reportB, map) {
        if (cleanA == null || trackB == null) return@remember null
        val addrA = reportA?.let { cleanA.at(it.phases.first().timeUs) } ?: cleanA.address
        val addrB = reportB?.let { trackB.at(it.phases.first().timeUs) } ?: trackB.address
        if (addrA == null || addrB == null) null else alignTo(trackB, addrB, cleanA, addrA, map)
    }
    // Avsnittet som spelas om och om igen: från strax före uppställningen till strax efter avslutet i A
    fun window(): Pair<Long, Long> {
        val r = reportA
        val end = if (durA > 0) durA else Long.MAX_VALUE
        return if (r == null) 0L to end
        else max(0L, r.phases.first().timeUs / 1000 - 300) to minOf(end, r.phases.last().timeUs / 1000 + 500)
    }
    fun bAt(msA: Long) = map.aToB(msA * 1000) / 1000
    fun seekBoth(msA: Long) {
        playerA.seekTo(msA)
        playerB.seekTo(bAt(msA).coerceIn(0L, if (durB > 0) durB else Long.MAX_VALUE))
    }
    val pause: () -> Unit = {
        playing = false
        playerA.pause()
        playerB.pause()
        seekBoth(playerA.currentPosition)
    }
    val play: () -> Unit = {
        playing = true
        playerA.setPlaybackSpeed(speed)
        playerA.play()
        if (!overlay) {
            playerB.setPlaybackSpeed(speed * map.slopeAt(playerA.currentPosition * 1000))
            playerB.play()
        }
    }
    val frameMs = 1000.0 / infoA.fps
    val step: (Int) -> Unit = { delta ->
        if (playing) pause()
        val index = (playerA.currentPosition / frameMs).roundToLong() + delta
        seekBoth((max(0L, index) * frameMs).roundToLong())
    }

    // Börja vid uppställningen när analysen är klar
    LaunchedEffect(reportA, map) {
        if (!playing) seekBoth(window().first)
    }
    LaunchedEffect(overlay) {
        if (overlay) playerB.pause() else if (playing) play()
    }
    // Håller B i takt med A medan de spelar, och spelar avsnittet om och om igen
    LaunchedEffect(playerA, playerB, map, overlay, speed) {
        var lastSpeed = -1f
        while (true) {
            posA = playerA.currentPosition
            posB = playerB.currentPosition
            durA = max(0L, playerA.duration)
            durB = max(0L, playerB.duration)
            if (playing) {
                val (start, end) = window()
                if (playerA.playbackState == Player.STATE_ENDED || posA >= end || posA < start - 100) {
                    seekBoth(start)
                    playerA.play()
                    if (!overlay) playerB.play()
                } else if (!overlay) {
                    val target = bAt(posA)
                    if (target < 0 || (durB > 0 && target > durB)) {
                        if (playerB.isPlaying) playerB.pause()
                    } else {
                        if (!playerB.playWhenReady) playerB.play()
                        val error = target - posB
                        if (abs(error) > SYNC_SEEK_MS) {
                            playerB.seekTo(target)
                        } else {
                            val s = (speed * map.slopeAt(posA * 1000) * (1f + (error.toFloat() / SYNC_SEEK_MS).coerceIn(-0.5f, 0.5f)))
                                .coerceAtLeast(0.01f)
                            if (abs(s - lastSpeed) > 0.03f * speed) {
                                playerB.setPlaybackSpeed(s)
                                lastSpeed = s
                            }
                        }
                    }
                }
            }
            delay(16)
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Mina svingar") }
            Text("Jämför", color = Sun, modifier = Modifier.padding(start = 4.dp))
            Spacer(Modifier.weight(1f))
            val status = when {
                poseA.analyzing -> String.format(SV, "Analyserar A … %.0f %%", poseA.progress * 100)
                poseB.analyzing -> String.format(SV, "Analyserar B … %.0f %%", poseB.progress * 100)
                poseA.error != null || poseB.error != null -> "Analysen gick inte"
                else -> ""
            }
            if (status.isNotEmpty()) Text(status, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(end = 8.dp))
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = !overlay, onClick = { overlay = false }, label = { Text("Sida vid sida") })
            FilterChip(selected = overlay, onClick = { overlay = true }, label = { Text("Ovanpå") })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = inStep, onClick = { inStep = true }, label = { Text("I takt") })
            FilterChip(selected = !inStep, onClick = { inStep = false }, label = { Text("Verklig tid") })
            Spacer(Modifier.width(6.dp))
            FilterChip(selected = mirrorB, onClick = { mirrorB = !mirrorB }, label = { Text("Spegla B") })
        }

        fun label(tag: String, clip: Clip) =
            "$tag · ${dateFormat.format(Date(clip.takenSec * 1000))}" + if (clip.uri == reference) " ★" else ""
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (overlay) {
                VideoPane(
                    Modifier.fillMaxSize(), playerA, infoA.aspect, cleanA, posA, showSkeleton, showPath, aligned,
                    label("A", a) + "  ·  B i lila", onLabel = { onOpen(a) }, uri = a.uri, paused = !playing, fps = infoA.fps,
                )
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    VideoPane(
                        Modifier.weight(1f).fillMaxSize(), playerA, infoA.aspect, cleanA, posA, showSkeleton, showPath, null,
                        label("A", a), onLabel = { onOpen(a) }, uri = a.uri, paused = !playing, fps = infoA.fps,
                    )
                    VideoPane(
                        Modifier.weight(1f).fillMaxSize(), playerB, infoB.aspect, trackB, posB, showSkeleton, showPath, null,
                        label("B", b) + if (mirrorB) " · spegelvänd" else "", onLabel = { onOpen(b) }, uri = b.uri,
                        paused = !playing, fps = infoB.fps, mirror = mirrorB,
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            val (start, end) = window()
            val range = start.toFloat()..max(start + 1, if (end == Long.MAX_VALUE) start + 1 else end).toFloat()
            Slider(
                value = posA.toFloat().coerceIn(range),
                onValueChange = { if (playing) pause(); seekBoth(it.toLong()) },
                valueRange = range,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { step(-1) }, modifier = Modifier.weight(1f)) { Text("◀ Bild") }
                Button(onClick = { if (playing) pause() else play() }, modifier = Modifier.weight(1f)) { Text(if (playing) "Paus" else "Spela") }
                OutlinedButton(onClick = { step(1) }, modifier = Modifier.weight(1f)) { Text("Bild ▶") }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for ((value, text) in SPEEDS) {
                    FilterChip(
                        selected = speed == value,
                        onClick = {
                            speed = value
                            playerA.setPlaybackSpeed(value)
                            playerB.setPlaybackSpeed(value * map.slopeAt(playerA.currentPosition * 1000))
                        },
                        label = { Text(text) },
                    )
                }
                Spacer(Modifier.width(6.dp))
                FilterChip(selected = showSkeleton, onClick = { showSkeleton = !showSkeleton }, label = { Text("Skelett") })
                FilterChip(selected = showPath, onClick = { showPath = !showPath }, label = { Text("Handbana") })
            }
            reportA?.let { r ->
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (phase in r.phases) {
                        AssistChip(onClick = { if (playing) pause(); seekBoth(phase.timeUs / 1000) }, label = { Text(phase.name) })
                    }
                }
            }
            CompareTable(reportA, reportB)
        }
    }
}

@Composable
private fun VideoPane(
    modifier: Modifier,
    player: ExoPlayer,
    aspect: Float,
    track: PoseTrack?,
    positionMs: Long,
    showSkeleton: Boolean,
    showPath: Boolean,
    other: PoseTrack?,
    label: String,
    onLabel: () -> Unit,
    uri: Uri,
    paused: Boolean,
    fps: Double,
    mirror: Boolean = false,
) {
    // Spegelvänd bild vänds runt mitten. Skelettet är redan spegelvänt
    val flip = Modifier.fillMaxSize().graphicsLayer { scaleX = if (mirror) -1f else 1f }
    Box(modifier.clipToBounds()) {
        AndroidView(
            modifier = flip,
            factory = { ctx -> LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView },
            update = { it.player = player },
            onRelease = { it.player = null },
        )
        // Två filmer på en gång, så färre bilder i minnet per film
        StepFrame(flip, player, uri, positionMs, paused, fps, window = 24)
        SwingOverlay(
            modifier = Modifier.fillMaxSize(),
            aspect = aspect,
            track = track,
            timeUs = positionMs * 1000,
            showSkeleton = showSkeleton,
            showPath = showPath,
            showHead = false,
            marks = emptyList(),
            tool = null,
            onAdd = {},
            other = other,
        )
        Text(
            label,
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
                .clickable(onClick = onLabel)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Mätningarna för båda svingarna, rad för rad. */
@Composable
private fun CompareTable(ra: Report?, rb: Report?) {
    if (ra == null && rb == null) return
    Column(Modifier.fillMaxWidth().heightIn(max = 170.dp).verticalScroll(rememberScrollState()).padding(top = 4.dp)) {
        if (ra != null && rb != null && ra.view != rb.view) {
            Text(
                "A är filmad ${ra.view.label.lowercase(SV)} och B ${rb.view.label.lowercase(SV)}, så bara tempot och huvudets höjd går att jämföra.",
                fontSize = 12.sp,
                color = Sun,
            )
        }
        if (ra == null || rb == null) {
            Text(
                "Hittar ingen hel sving i ${if (ra == null) "A" else "B"}, så svingarna spelas utan att faserna synkas.",
                fontSize = 12.sp,
                color = Sun,
            )
        }
        Row(Modifier.padding(top = 4.dp)) {
            Spacer(Modifier.weight(1.1f))
            Text("A", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text("B", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
        val ids = (ra?.metrics.orEmpty() + rb?.metrics.orEmpty()).map { it.id }.distinct()
        for (id in ids) {
            val ma = ra?.metrics?.firstOrNull { it.id == id }
            val mb = rb?.metrics?.firstOrNull { it.id == id }
            Row(Modifier.padding(vertical = 3.dp)) {
                Text((ma ?: mb)?.name.orEmpty(), fontSize = 12.sp, color = Color.White.copy(alpha = 0.75f), modifier = Modifier.weight(1.1f))
                ValueCell(ma, Modifier.weight(1f))
                ValueCell(mb, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ValueCell(m: Metric?, modifier: Modifier) {
    val color = when (m?.status) {
        Status.FAULT -> FaultColor
        Status.GOOD -> GoodColor
        else -> Color.White.copy(alpha = 0.5f)
    }
    Text(m?.value ?: "–", fontSize = 12.sp, color = color, modifier = modifier.padding(end = 4.dp))
}
