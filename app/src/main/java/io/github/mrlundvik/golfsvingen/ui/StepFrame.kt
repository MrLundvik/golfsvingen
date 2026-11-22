package io.github.mrlundvik.golfsvingen.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import io.github.mrlundvik.golfsvingen.pose.FrameDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Avkodade bilder runt en tid, [window] bilder åt varje håll. Videospelaren avkodar från närmaste nyckelbild vid
 * varje steg, upp till en halv sekund, och börjar om när man stegar igen, så bilden hänger inte med. Bilderna här
 * avkodas en gång i följd och visas direkt. De är i analysens storlek, 960 bildpunkter på längden, och sparas med
 * 16 bitars färg, ungefär 1 MB styck.
 */
private class FrameCache(private val context: Context, private val uri: Uri, private val window: Int) {
    private val frames = TreeMap<Long, Bitmap>()
    private var center = Long.MIN_VALUE
    private var job: Job? = null
    private var rotation: Int? = null

    /** Ökar när nya bilder finns, så att den som visar dem ritar om. */
    var version by mutableIntStateOf(0)
        private set

    /** Bilden närmast [timeUs], om den ligger inom [toleranceUs]. */
    fun frameAt(timeUs: Long, toleranceUs: Long): Bitmap? = synchronized(frames) {
        val best = listOfNotNull(frames.floorEntry(timeUs), frames.ceilingEntry(timeUs)).minByOrNull { abs(it.key - timeUs) }
        best?.takeIf { abs(it.key - timeUs) <= toleranceUs }?.value
    }

    /** Avkodar bilderna runt [timeUs]. Görs om först när man flyttat sig mer än ett halvt fönster. */
    fun prefetch(scope: CoroutineScope, timeUs: Long, frameUs: Long) {
        if (center != Long.MIN_VALUE && abs(timeUs - center) <= window / 2 * frameUs) return
        center = timeUs
        val from = max(0L, timeUs - window * frameUs)
        val to = timeUs + window * frameUs
        val margin = 4 * frameUs
        synchronized(frames) {
            frames.headMap(from - margin).clear()
            frames.tailMap(to + margin).clear()
        }
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            val turn = rotation ?: runCatching {
                MediaMetadataRetriever().use { r ->
                    r.setDataSource(context, uri)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                }
            }.getOrDefault(0).also { rotation = it }
            runCatching {
                FrameDecoder.decode(
                    context, uri, turn, 1,
                    check = { ensureActive() },
                    fromUs = from,
                    toUs = to,
                    want = { pts -> synchronized(frames) { !frames.containsKey(pts) } },
                ) { bitmap, pts ->
                    val copy = bitmap.copy(Bitmap.Config.RGB_565, false)
                    synchronized(frames) { frames[pts] = copy }
                    version++
                }
            }
        }
    }

    fun release() {
        job?.cancel()
        synchronized(frames) { frames.clear() }
    }
}

/** Om spelaren visar bilden för [targetUs]. Spelaren kan räkna sina tider med en fast förskjutning, som räknas bort. */
private fun showing(shownUs: Long, targetUs: Long, toleranceUs: Long): Boolean {
    if (shownUs == Long.MIN_VALUE) return false
    var d = shownUs - targetUs
    if (abs(d) > 100_000_000_000L) d -= (d / 1e12).roundToLong() * 1_000_000_000_000L
    return abs(d) <= toleranceUs
}

/**
 * Visar bilden för [positionMs] ovanpå spelaren när filmen står still och spelaren inte har hunnit visa den själv,
 * så att bilden följer med när man stegar. När spelaren hunnit ikapp syns dess skarpare bild igen.
 */
@OptIn(UnstableApi::class)
@Composable
fun StepFrame(modifier: Modifier, player: ExoPlayer, uri: Uri, positionMs: Long, paused: Boolean, fps: Double, window: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cache = remember(uri) { FrameCache(context, uri, window) }
    DisposableEffect(cache) { onDispose { cache.release() } }

    // Bilden spelaren senast visade
    val rendered = remember(player) { AtomicLong(Long.MIN_VALUE) }
    DisposableEffect(player) {
        val listener = VideoFrameMetadataListener { presentationTimeUs, _, _, _ -> rendered.set(presentationTimeUs) }
        player.setVideoFrameMetadataListener(listener)
        onDispose { player.clearVideoFrameMetadataListener(listener) }
    }
    var shownUs by remember { mutableLongStateOf(Long.MIN_VALUE) }
    LaunchedEffect(rendered) {
        while (true) {
            shownUs = rendered.get()
            delay(16)
        }
    }

    val frameUs = (1_000_000.0 / fps).toLong()
    val targetUs = positionMs * 1000
    // Vänta en stund efter att man dragit i reglaget, så att det inte avkodas för varje läge på vägen
    LaunchedEffect(paused, positionMs, frameUs) {
        if (!paused) return@LaunchedEffect
        delay(120)
        cache.prefetch(scope, targetUs, frameUs)
    }
    if (!paused) return
    val frame = cache.version.let { cache.frameAt(targetUs, frameUs / 2) } ?: return
    if (showing(shownUs, targetUs, frameUs / 2)) return
    Image(frame.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
}
