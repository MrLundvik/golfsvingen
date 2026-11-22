package io.github.mrlundvik.golfsvingen.ui

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.widget.Toast
import android.view.LayoutInflater
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.mrlundvik.golfsvingen.R
import io.github.mrlundvik.golfsvingen.analysis.Guide
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import io.github.mrlundvik.golfsvingen.analysis.Metric
import io.github.mrlundvik.golfsvingen.analysis.SwingReport
import io.github.mrlundvik.golfsvingen.analysis.View
import io.github.mrlundvik.golfsvingen.pose.PoseAnalyzer
import io.github.mrlundvik.golfsvingen.pose.PoseExport
import io.github.mrlundvik.golfsvingen.pose.PoseStore
import io.github.mrlundvik.golfsvingen.pose.PoseTrack
import io.github.mrlundvik.golfsvingen.store.Clip
import io.github.mrlundvik.golfsvingen.store.ClipStore
import io.github.mrlundvik.golfsvingen.store.Reference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

private val SPEEDS = listOf(1f to "1×", 0.25f to "¼", 0.125f to "⅛", 0.0625f to "1/16")
private const val MAX_ZOOM = 6f
private const val MIN_CLIP_MS = 200L
private val SV = Locale("sv", "SE")

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(clip: Clip, onBack: () -> Unit, onOpen: (Clip) -> Unit, onCompare: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(clip.uri))
            setSeekParameters(SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ONE
            setPlaybackSpeed(0.125f)
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    // Bildtakten räknas fram ur antalet bilder och längden
    val fps by produceState(240.0, clip.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, clip.uri)
                    val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toDoubleOrNull()
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()
                    if (count != null && ms != null && ms > 0) count / (ms / 1000.0) else 240.0
                }
            }.getOrDefault(240.0)
        }
    }
    // Nyckelbilderna, där ett klipp kan börja
    val keyFramesMs by produceState(emptyList<Long>(), clip.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { ClipStore.keyFrameTimesUs(context, clip.uri).map { it / 1000 } }.getOrDefault(emptyList())
        }
    }
    val snapStart: (Long) -> Long = { ms -> keyFramesMs.lastOrNull { it <= ms } ?: 0L }
    // Bildens upprätta proportioner, för att lägga kroppspunkter och markeringar rätt på videon
    val aspect by produceState(0f, clip.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, clip.uri)
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat()
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
                    val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) h / w else w / h
                }
            }.getOrDefault(0f)
        }
    }

    // Kroppsanalysen görs en gång per sving och sparas
    var track by remember { mutableStateOf<PoseTrack?>(null) }
    var analysisProgress by remember { mutableFloatStateOf(0f) }
    var analyzing by remember { mutableStateOf(false) }
    var analysisError by remember { mutableStateOf<String?>(null) }
    var analysisRun by remember { mutableIntStateOf(0) }
    LaunchedEffect(clip.uri, analysisRun) {
        analysisError = null
        val cached = withContext(Dispatchers.IO) { PoseStore.load(context, clip) }
        if (cached != null) {
            track = cached
            return@LaunchedEffect
        }
        analyzing = true
        analysisProgress = 0f
        try {
            val result = PoseAnalyzer.analyze(context, clip) { p -> analysisProgress = p }
            withContext(Dispatchers.IO) { PoseStore.save(context, clip, result) }
            track = result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            analysisError = e.message ?: e.javaClass.simpleName
        } finally {
            analyzing = false
        }
    }
    // Rättade punkter och svingrapporten, räknade ur analysen
    val clean = remember(track) { track?.cleaned() }
    var viewOverride by remember { mutableStateOf<View?>(null) }
    val report = remember(clean, viewOverride) { clean?.let { runCatching { SwingReport.build(it, viewOverride) }.getOrNull() } }
    var reportOpen by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf<Metric?>(null) }
    LaunchedEffect(report) { focus = null }
    // Efter Visa syns bara den valda mätningen, så att videon får plats. Hjälplinjer och skugga kan väljas bort
    var compact by remember { mutableStateOf(false) }
    var showLines by remember { mutableStateOf(true) }
    var showGhost by remember { mutableStateOf(true) }
    var fullscreen by remember { mutableStateOf(false) }
    // Zoom med två fingrar. Ett finger flyttar bilden när den är inzoomad, dubbeltryck återställer
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val resetZoom: () -> Unit = { zoom = 1f; pan = Offset.Zero }
    BackHandler(enabled = !fullscreen && reportOpen && compact && focus != null) { compact = false }
    BackHandler(enabled = fullscreen) { fullscreen = false }
    // I helskärm döljs telefonens statusrad och navigeringsfält. Ett svep från kanten visar dem tillfälligt
    val view = LocalView.current
    DisposableEffect(fullscreen) {
        val window = (context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        if (fullscreen) {
            bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            bars?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    var showSkeleton by remember { mutableStateOf(true) }
    var showPath by remember { mutableStateOf(true) }
    var showHead by remember { mutableStateOf(true) }
    var drawing by remember { mutableStateOf(false) }
    var tool by remember { mutableStateOf<Tool?>(null) }
    val marks = remember { mutableStateListOf<Mark>() }
    LaunchedEffect(clip.uri) {
        marks.clear()
        marks.addAll(withContext(Dispatchers.IO) { MarkStore.load(context, clip) })
    }
    val saveMarks: () -> Unit = {
        val copy = marks.toList()
        scope.launch(Dispatchers.IO) { runCatching { MarkStore.save(context, clip, copy) } }
    }

    var speed by remember { mutableFloatStateOf(0.125f) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(true) }
    var trimming by remember { mutableStateOf(false) }
    var trimStart by remember { mutableLongStateOf(0L) }
    var trimEnd by remember { mutableLongStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var replacement by remember { mutableStateOf<Clip?>(null) }
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = max(0L, player.duration)
            playing = player.isPlaying
            // I klippläget spelas bara det valda avsnittet, om och om igen
            if (trimming && playing && (position >= trimEnd || position < trimStart)) player.seekTo(trimStart)
            delay(16)
        }
    }

    val frameMs = 1000.0 / fps
    val step: (Int) -> Unit = { delta ->
        player.pause()
        val index = (player.currentPosition / frameMs).roundToLong() + delta
        player.seekTo((max(0L, index) * frameMs).roundToLong())
    }

    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { dest ->
        if (dest != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(clip.uri)!!.use { input ->
                        context.contentResolver.openOutputStream(dest)!!.use { output -> input.copyTo(output) }
                    }
                }.isSuccess
            }
            Toast.makeText(context, if (ok) "Svingen är sparad" else "Svingen kunde inte sparas", Toast.LENGTH_SHORT).show()
        }
    }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            runCatching { PoseStore.delete(context, clip) }
            runCatching { MarkStore.delete(context, clip) }
            Toast.makeText(context, "Svingen ligger i papperskorgen i 30 dagar", Toast.LENGTH_SHORT).show()
            onBack()
        }
    }
    // Om originalet inte är appens egen fil frågar telefonen först om det får läggas i papperskorgen
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val next = replacement ?: return@rememberLauncherForActivityResult
        replacement = null
        val text = if (result.resultCode == Activity.RESULT_OK) "Klippet är sparat. Originalet ligger i papperskorgen i 30 dagar."
        else "Klippet är sparat, och originalet finns kvar."
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        onOpen(next)
    }
    // Förslag på klipp: från strax före uppställningen till strax efter avslutet
    val suggestTrim: () -> Boolean = {
        val phases = report?.phases
        if (phases != null && duration > 0) {
            trimStart = snapStart(max(0L, phases.first().timeUs / 1000 - 500))
            trimEnd = minOf(duration, phases.last().timeUs / 1000 + 700).coerceAtLeast(trimStart + MIN_CLIP_MS)
            true
        } else {
            false
        }
    }
    var isReference by remember { mutableStateOf(Reference.get(context) == clip.uri) }

    val saveTrim: () -> Unit = {
        busy = true
        player.pause()
        val startUs = trimStart * 1000
        val endUs = trimEnd * 1000
        val raw = track
        val copyMarks = marks.toList()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (trimmed, offsetUs) = ClipStore.trim(context, clip, startUs, endUs)
                    // Analysen och markeringarna följer med, så att klippet inte behöver analyseras igen
                    raw?.excerpt(offsetUs, endUs)?.let { runCatching { PoseStore.save(context, trimmed, it) } }
                    if (copyMarks.isNotEmpty()) runCatching { MarkStore.save(context, trimmed, copyMarks) }
                    if (Reference.get(context) == clip.uri) Reference.set(context, trimmed.uri)
                    trimmed
                }
            }
            val next = result.getOrNull()
            if (next == null) {
                busy = false
                Toast.makeText(context, "Klippet kunde inte sparas: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
            } else if (withContext(Dispatchers.IO) { ClipStore.trash(context, clip.uri) }) {
                Toast.makeText(context, "Klippet är sparat. Originalet ligger i papperskorgen i 30 dagar.", Toast.LENGTH_LONG).show()
                onOpen(next)
            } else {
                replacement = next
                val request = MediaStore.createTrashRequest(context.contentResolver, listOf(clip.uri), true)
                trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        if (!fullscreen) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Mina svingar") }
            if (trimming) Text("Klipp svingen", color = Sun, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.weight(1f))
            val error = analysisError
            when {
                analyzing -> Text(
                    String.format(SV, "Analyserar kroppen … %.0f %%", analysisProgress * 100),
                    fontSize = 13.sp,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(end = 8.dp),
                )
                error != null -> TextButton(onClick = { analysisRun++ }) { Text("Analysen gick inte · Försök igen", color = Warn, fontSize = 13.sp) }
                track != null -> Text(
                    track?.let { t -> if (t.analysisMs > 0) "Analyserad (${t.delegate} · ${t.analysisMs / 1000} s)" else "Analyserad (${t.delegate})" } ?: "",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        if (analyzing) LinearProgressIndicator(progress = { analysisProgress }, modifier = Modifier.fillMaxWidth())
        analysisError?.let {
            Text(it, fontSize = 12.sp, color = Warn, modifier = Modifier.padding(horizontal = 16.dp))
        }
        }
        val drawingNow = drawing && !trimming && tool != null
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds()
                .pointerInput(drawingNow) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var moved = 0f
                        var active = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val fingers = event.changes.count { it.pressed }
                            if (fingers == 0) break
                            // Två fingrar zoomar alltid. Ett finger flyttar bara när bilden är inzoomad och man inte ritar
                            if (fingers >= 2 || (!drawingNow && zoom > 1.01f)) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                if (!active) {
                                    moved += panChange.getDistance()
                                    if (fingers >= 2 || moved > slop) active = true
                                }
                                if (active) {
                                    val c = event.calculateCentroid(useCurrent = true)
                                    val next = (zoom * zoomChange).coerceIn(1f, MAX_ZOOM)
                                    val raw = c - (c - pan) * (next / zoom) + panChange
                                    zoom = next
                                    pan = Offset(
                                        raw.x.coerceIn(size.width * (1f - next), 0f),
                                        raw.y.coerceIn(size.height * (1f - next), 0f),
                                    )
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { resetZoom() }) },
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                    translationX = pan.x
                    translationY = pan.y
                    transformOrigin = TransformOrigin(0f, 0f)
                },
                factory = { ctx ->
                    (LayoutInflater.from(ctx).inflate(R.layout.player_view, null) as PlayerView).apply {
                        this.player = player
                    }
                },
            )
            SwingOverlay(
                modifier = Modifier.fillMaxSize(),
                aspect = aspect,
                track = clean,
                timeUs = position * 1000,
                showSkeleton = showSkeleton,
                showPath = showPath,
                showHead = showHead,
                marks = marks,
                tool = if (drawing && !trimming) tool else null,
                onAdd = { marks += it; saveMarks() },
                guide = if (reportOpen && !trimming) focus?.guide?.let { g ->
                    Guide(
                        lines = if (showLines) g.lines else emptyList(),
                        labels = if (showLines) g.labels else emptyList(),
                        ghost = if (showGhost) g.ghost else null,
                        pathSplitUs = if (showLines) g.pathSplitUs else null,
                        bones = if (showLines) g.bones else emptyList(),
                    )
                } else null,
                zoom = zoom,
                pan = pan,
            )
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (zoom > 1.01f) {
                    VideoButton(String.format(SV, "%.1f× · Återställ", zoom), onClick = resetZoom)
                }
                VideoButton(if (fullscreen) "Avsluta helskärm" else "Helskärm", onClick = { fullscreen = !fullscreen })
            }
            val f = focus
            if (fullscreen && reportOpen && f != null) {
                Text(
                    "${f.title} · ${f.value}",
                    fontSize = 13.sp,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            if (fullscreen) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    val range = 0f..max(1L, duration).toFloat()
                    Slider(
                        value = position.toFloat().coerceIn(range),
                        onValueChange = { player.pause(); player.seekTo(it.toLong()) },
                        valueRange = range,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { step(-1) }, modifier = Modifier.weight(1f)) { Text("◀") }
                        Button(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.weight(1.4f)) {
                            Text(if (playing) "Paus" else "Spela")
                        }
                        OutlinedButton(onClick = { step(1) }, modifier = Modifier.weight(1f)) { Text("▶") }
                        // Farten byts med varje tryck
                        TextButton(onClick = {
                            val i = SPEEDS.indexOfFirst { it.first == speed }
                            speed = SPEEDS[(i + 1) % SPEEDS.size].first
                            player.setPlaybackSpeed(speed)
                        }) { Text(SPEEDS.firstOrNull { it.first == speed }?.second ?: "1×") }
                    }
                }
            }
        }
        if (!fullscreen) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            val range = 0f..max(1L, duration).toFloat()
            if (trimming) {
                RangeSlider(
                    value = trimStart.toFloat()..trimEnd.toFloat(),
                    onValueChange = { r ->
                        player.pause()
                        val s = snapStart(r.start.toLong())
                        val e = r.endInclusive.toLong()
                        if (s != trimStart) {
                            trimStart = minOf(s, snapStart(trimEnd - MIN_CLIP_MS))
                            player.seekTo(trimStart)
                        } else if (e != trimEnd) {
                            trimEnd = maxOf(trimStart + MIN_CLIP_MS, minOf(e, duration))
                            player.seekTo(trimEnd)
                        }
                    },
                    valueRange = range,
                )
            } else {
                Slider(
                    value = position.toFloat().coerceIn(range),
                    onValueChange = { player.pause(); player.seekTo(it.toLong()) },
                    valueRange = range,
                )
            }
            val frame = (position / frameMs).roundToLong() + 1
            val total = (duration / frameMs).roundToLong()
            Text(
                String.format(SV, "Bild %d av %d · %.3f s · %.0f fps", frame, total, position / 1000.0, fps),
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.8f),
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { step(-1) }, modifier = Modifier.weight(1f)) { Text("◀ Bild") }
                Button(onClick = { if (player.isPlaying) player.pause() else player.play() }, modifier = Modifier.weight(1f)) {
                    Text(if (playing) "Paus" else "Spela")
                }
                OutlinedButton(onClick = { step(1) }, modifier = Modifier.weight(1f)) { Text("Bild ▶") }
            }
            val focused = focus
            if (reportOpen && !trimming && compact && focused != null && report != null) {
                FocusCard(
                    report = report,
                    metric = focused,
                    layers = listOf(
                        Triple("Hjälplinjer", showLines) { showLines = !showLines },
                        Triple("Skugga", showGhost) { showGhost = !showGhost },
                        Triple("Skelett", showSkeleton) { showSkeleton = !showSkeleton },
                        Triple("Handbana", showPath) { showPath = !showPath },
                        Triple("Huvud", showHead) { showHead = !showHead },
                    ),
                    onPick = { m ->
                        focus = m
                        player.pause()
                        player.seekTo(m.timeUs / 1000)
                    },
                    onBack = { compact = false },
                )
            } else if (reportOpen && !trimming) {
                val t = track
                ReportPanel(
                    report = report,
                    footer = if (t != null && t.analysisMs > 0) "Analysen tog ${t.analysisMs / 1000} s med ${t.delegate}." else "",
                    focusId = focus?.id,
                    onView = { viewOverride = it },
                    onSeek = { us -> player.pause(); player.seekTo(us / 1000) },
                    onShow = { m ->
                        focus = m
                        compact = true
                        showSkeleton = true
                        player.pause()
                        player.seekTo(m.timeUs / 1000)
                    },
                    onExport = {
                        val raw = track
                        if (raw != null) scope.launch {
                            val result = withContext(Dispatchers.IO) { runCatching { PoseExport.export(context, clip, raw, clean) } }
                            result.onSuccess { uri ->
                                Toast.makeText(context, "Sparad i Hämtade filer/Golfsvingen", Toast.LENGTH_SHORT).show()
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/csv"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                runCatching { context.startActivity(Intent.createChooser(send, "Dela mätdata")) }
                            }.onFailure {
                                Toast.makeText(context, "Mätdatan kunde inte sparas: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onClose = { reportOpen = false; compact = false },
                )
            } else {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in SPEEDS) {
                    FilterChip(
                        selected = speed == value,
                        onClick = { speed = value; player.setPlaybackSpeed(value) },
                        label = { Text(label) },
                    )
                }
            }
            if (!trimming) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = false,
                        onClick = { reportOpen = true; drawing = false },
                        label = { Text(if (report != null && report.faults.isNotEmpty()) "Rapport (${report.faults.size})" else "Rapport") },
                        enabled = clean != null,
                    )
                    FilterChip(selected = showSkeleton, onClick = { showSkeleton = !showSkeleton }, label = { Text("Skelett") }, enabled = track != null)
                    FilterChip(selected = showPath, onClick = { showPath = !showPath }, label = { Text("Handbana") }, enabled = track != null)
                    FilterChip(selected = showHead, onClick = { showHead = !showHead }, label = { Text("Huvud") }, enabled = track != null)
                    FilterChip(selected = drawing, onClick = { drawing = !drawing; if (drawing && tool == null) tool = Tool.LINE }, label = { Text("Rita") })
                }
                if (drawing) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for ((t, label) in listOf(Tool.LINE to "Linje", Tool.ANGLE to "Vinkel", Tool.CIRCLE to "Cirkel")) {
                            FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(label) })
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = { if (marks.isNotEmpty()) { marks.removeAt(marks.lastIndex); saveMarks() } }, enabled = marks.isNotEmpty()) { Text("Ångra") }
                        TextButton(onClick = { marks.clear(); saveMarks() }, enabled = marks.isNotEmpty()) { Text("Rensa") }
                    }
                    Text(
                        when (tool) {
                            Tool.ANGLE -> "Tryck på tre punkter: en ände, spetsen, den andra änden."
                            Tool.CIRCLE -> "Dra från mitten och ut."
                            else -> "Dra en linje på videon."
                        },
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.6f),
                    )
                }
            }
            if (trimming) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { trimStart = snapStart(minOf(player.currentPosition, trimEnd - MIN_CLIP_MS)) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Start här") }
                    OutlinedButton(
                        onClick = { trimEnd = maxOf(player.currentPosition, trimStart + MIN_CLIP_MS) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) { Text("Slut här") }
                }
                Text(
                    String.format(
                        SV,
                        "Klippet: %.2f–%.2f s, %.2f s långt. Det börjar på närmaste nyckelbild, högst en halv sekund före den bild du väljer.",
                        trimStart / 1000.0, trimEnd / 1000.0, (trimEnd - trimStart) / 1000.0,
                    ),
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { trimming = false }, enabled = !busy) { Text("Avbryt") }
                    if (report != null) TextButton(onClick = { if (suggestTrim()) player.seekTo(trimStart) }, enabled = !busy) { Text("Föreslå") }
                    Button(onClick = saveTrim, enabled = !busy) { Text(if (busy) "Sparar…" else "Spara klippet") }
                }
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onCompare) { Text("Jämför") }
                    TextButton(onClick = {
                        isReference = !isReference
                        Reference.set(context, if (isReference) clip.uri else null)
                        Toast.makeText(
                            context,
                            if (isReference) "Referenssving. Den visas först när du jämför." else "Inte längre referenssving",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }) { Text(if (isReference) "★ Referens" else "☆ Referens") }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "video/mp4"
                            putExtra(Intent.EXTRA_STREAM, clip.uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(send, "Dela svingen"))
                    }) { Text("Dela") }
                    TextButton(onClick = {
                        player.pause()
                        if (!suggestTrim()) {
                            trimStart = 0L
                            trimEnd = duration
                        }
                        trimming = true
                        player.seekTo(trimStart)
                    }, enabled = duration > MIN_CLIP_MS) { Text("Klipp") }
                    TextButton(onClick = { saveLauncher.launch(clip.name) }) { Text("Spara till…") }
                    TextButton(onClick = {
                        val request = MediaStore.createTrashRequest(context.contentResolver, listOf(clip.uri), true)
                        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    }) { Text("Radera", color = Warn) }
                }
            }
            }
        }
    }
}

/** Liten knapp ovanpå videon. */
@Composable
private fun VideoButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 13.sp,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
