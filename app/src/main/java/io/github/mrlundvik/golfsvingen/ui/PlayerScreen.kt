package io.github.mrlundvik.golfsvingen.ui

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
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
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.mrlundvik.golfsvingen.store.Clip
import io.github.mrlundvik.golfsvingen.store.ClipStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToLong

private val SPEEDS = listOf(1f to "1×", 0.25f to "¼", 0.125f to "⅛", 0.0625f to "1/16")
private const val MIN_CLIP_MS = 200L
private val SV = Locale("sv", "SE")

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(clip: Clip, onBack: () -> Unit, onOpen: (Clip) -> Unit) {
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
            delay(33)
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
        if (it.resultCode == Activity.RESULT_OK) onBack()
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
    val saveTrim: () -> Unit = {
        busy = true
        player.pause()
        val startUs = trimStart * 1000
        val endUs = trimEnd * 1000
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { ClipStore.trim(context, clip, startUs, endUs) } }
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Mina svingar") }
            if (trimming) Text("Klipp svingen", color = Sun, modifier = Modifier.padding(start = 8.dp))
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                }
            },
        )
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
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
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in SPEEDS) {
                    FilterChip(
                        selected = speed == value,
                        onClick = { speed = value; player.setPlaybackSpeed(value) },
                        label = { Text(label) },
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
                    Button(onClick = saveTrim, enabled = !busy) { Text(if (busy) "Sparar…" else "Spara klippet") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
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
                        trimStart = 0L
                        trimEnd = duration
                        trimming = true
                    }, enabled = duration > MIN_CLIP_MS) { Text("Klipp") }
                    TextButton(onClick = { saveLauncher.launch(clip.name) }) { Text("Spara till…") }
                    TextButton(onClick = {
                        val request = MediaStore.createDeleteRequest(context.contentResolver, listOf(clip.uri))
                        deleteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                    }) { Text("Radera", color = Warn) }
                }
            }
        }
    }
}
