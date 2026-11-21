package io.github.mrlundvik.golfsvingen.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mrlundvik.golfsvingen.MainActivity
import io.github.mrlundvik.golfsvingen.camera.CapturePipeline
import io.github.mrlundvik.golfsvingen.camera.HighSpeedSupport
import io.github.mrlundvik.golfsvingen.trigger.ImpactDetector
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SOUND_PRE_US = 3_500_000L
private const val SOUND_POST_US = 1_500_000L
private const val MANUAL_PRE_US = 5_000_000L
private const val MANUAL_POST_US = 300_000L

// Självutlösaren: väntetid före filmningen, och hur länge den filmar. Bufferten rymmer 10 s.
private val DELAYS = listOf(0, 3, 5, 10, 15, 20, 30)
private const val DEFAULT_DELAY_S = 5
private const val DEFAULT_LENGTH_S = 6
private val LENGTHS = 2..9

private fun hasPermissions(context: Context) =
    listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun rememberIsResumed(): Boolean {
    val owner = LocalLifecycleOwner.current
    var resumed by remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ ->
            resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return resumed
}

@Composable
fun CameraScreen(activity: MainActivity, onOpenLibrary: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions(context)
    }
    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    val config = remember { HighSpeedSupport.find(context) }

    if (config == null) {
        Message("Telefonens bakre kamera har inget höghastighetsläge, så Golfsvingen kan inte filma i 240 fps här.")
        return
    }
    if (!granted) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Golfsvingen behöver kameran för att filma, och mikrofonen för att höra bollträffen. Inget ljud sparas.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) {
                Text("Ge behörighet")
            }
        }
        return
    }

    val main = remember { Handler(Looper.getMainLooper()) }
    val pipeline = remember { CapturePipeline(context.applicationContext, config) }
    val detector = remember { ImpactDetector() }
    var status by remember { mutableStateOf("Startar kameran…") }
    var savedCount by remember { mutableIntStateOf(0) }
    var soundOn by remember { mutableStateOf(true) }
    var sensitivity by remember { mutableFloatStateOf(0.5f) }
    var level by remember { mutableFloatStateOf(0f) }
    var flash by remember { mutableStateOf(false) }

    // Självutlösaren
    val prefs = remember { context.getSharedPreferences("golfsvingen", Context.MODE_PRIVATE) }
    var delayIndex by remember {
        mutableIntStateOf(DELAYS.indexOf(prefs.getInt("timerDelay", DEFAULT_DELAY_S)).let { if (it < 0) DELAYS.indexOf(DEFAULT_DELAY_S) else it })
    }
    var lengthS by remember { mutableIntStateOf(prefs.getInt("timerLength", DEFAULT_LENGTH_S).coerceIn(LENGTHS)) }
    val delayS = DELAYS[delayIndex]
    val scope = rememberCoroutineScope()
    val tones = remember { runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 90) }.getOrNull() }
    DisposableEffect(tones) { onDispose { tones?.release() } }
    var timerJob by remember { mutableStateOf<Job?>(null) }
    var timerActive by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var recordingLeft by remember { mutableIntStateOf(0) }

    val save: (Boolean) -> Unit = { manual ->
        val ok = if (manual) pipeline.save(MANUAL_PRE_US, MANUAL_POST_US) else pipeline.save(SOUND_PRE_US, SOUND_POST_US)
        if (ok) {
            status = if (manual) "Sparar de senaste sekunderna…" else "Träff hörd, sparar svingen…"
            flash = true
        }
    }

    val startTimer: () -> Unit = {
        timerActive = true
        timerJob = scope.launch {
            try {
                for (s in delayS downTo 1) {
                    countdown = s
                    tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                    delay(1000)
                }
                countdown = 0
                // Sparar från nu och lengthS sekunder framåt
                if (pipeline.save(0L, lengthS * 1_000_000L)) {
                    tones?.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                    flash = true
                    status = "Filmar…"
                    for (s in lengthS downTo 1) {
                        recordingLeft = s
                        delay(1000)
                    }
                    recordingLeft = 0
                    tones?.startTone(ToneGenerator.TONE_PROP_ACK, 300)
                    // Låt tonen klinga av innan ljudtriggern lyssnar igen
                    delay(800)
                } else {
                    status = "Kameran är inte redo än"
                }
            } finally {
                countdown = 0
                recordingLeft = 0
                timerActive = false
                timerJob = null
            }
        }
    }

    DisposableEffect(pipeline) {
        pipeline.listener = object : CapturePipeline.Listener {
            override fun onReady(text: String) { status = text }
            override fun onSaved(name: String) {
                savedCount++
                flash = false
                status = "Sparad: $name"
            }
            override fun onError(text: String) {
                flash = false
                status = text
            }
        }
        onDispose {
            pipeline.listener = null
            pipeline.release()
        }
    }

    val resumed = rememberIsResumed()
    DisposableEffect(soundOn, resumed) {
        if (soundOn && resumed) {
            detector.start(
                onLevel = { v -> main.post { level = v } },
                // Självutlösarens pip får inte räknas som en bollträff
                onImpact = { main.post { if (!timerActive) save(false) } },
            )
        }
        onDispose { detector.stop() }
    }
    LaunchedEffect(sensitivity) { detector.sensitivity = sensitivity }
    LaunchedEffect(activity) { activity.shutter.collect { save(true) } }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(Modifier.aspectRatio(config.size.height.toFloat() / config.size.width)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.setFixedSize(config.size.width, config.size.height)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                var started = false
                                override fun surfaceCreated(h: SurfaceHolder) {}
                                override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) {
                                    if (!started) {
                                        started = true
                                        pipeline.start(h.surface)
                                    }
                                }
                                override fun surfaceDestroyed(h: SurfaceHolder) {
                                    started = false
                                    pipeline.stop()
                                    status = "Kameran är pausad"
                                }
                            })
                        }
                    },
                )
                // Hjälplinjer för att rikta kameran: lodlinje i mitten och linjer i tredjedelar
                Canvas(Modifier.fillMaxSize()) {
                    val c = Color.White.copy(alpha = 0.35f)
                    drawLine(c, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
                    for (k in 1..2) {
                        val y = size.height * k / 3
                        drawLine(c.copy(alpha = 0.2f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
                    }
                }
                Text(
                    status,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(if (flash) Sun.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    color = if (flash) Color.Black else Color.White,
                )
                if (countdown > 0) {
                    Text(
                        "$countdown",
                        fontSize = 110.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                            .padding(horizontal = 40.dp, vertical = 4.dp),
                    )
                }
                if (recordingLeft > 0) {
                    Text(
                        "● Filmar  $recordingLeft",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .background(Warn.copy(alpha = 0.9f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ljudtrigger", style = MaterialTheme.typography.titleSmall)
                    Text("Bollträff, eller handklapp direkt efter svingen", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                }
                Switch(checked = soundOn, onCheckedChange = { soundOn = it })
            }
            if (soundOn) {
                LevelMeter(level = level, threshold = detector.thresholdLevel(sensitivity))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Känslighet", fontSize = 12.sp, modifier = Modifier.width(76.dp))
                    Slider(value = sensitivity, onValueChange = { sensitivity = it }, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Självutlösare", style = MaterialTheme.typography.titleSmall)
                    Text("Pip varje sekund, långt pip när den filmar", fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
                }
                Stepper(
                    label = "Vänta",
                    value = "$delayS s",
                    enabled = !timerActive,
                    onMinus = {
                        delayIndex = (delayIndex - 1).coerceAtLeast(0)
                        prefs.edit().putInt("timerDelay", DELAYS[delayIndex]).apply()
                    },
                    onPlus = {
                        delayIndex = (delayIndex + 1).coerceAtMost(DELAYS.lastIndex)
                        prefs.edit().putInt("timerDelay", DELAYS[delayIndex]).apply()
                    },
                )
                Spacer(Modifier.width(6.dp))
                Stepper(
                    label = "Filma",
                    value = "$lengthS s",
                    enabled = !timerActive,
                    onMinus = {
                        lengthS = (lengthS - 1).coerceIn(LENGTHS)
                        prefs.edit().putInt("timerLength", lengthS).apply()
                    },
                    onPlus = {
                        lengthS = (lengthS + 1).coerceIn(LENGTHS)
                        prefs.edit().putInt("timerLength", lengthS).apply()
                    },
                )
            }
            Button(
                onClick = { if (!timerActive) startTimer() else if (countdown > 0) timerJob?.cancel() },
                enabled = !timerActive || countdown > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Text(
                    when {
                        countdown > 0 -> "Avbryt ($countdown)"
                        timerActive -> "Filmar…"
                        delayS == 0 -> "Filma $lengthS s nu"
                        else -> "Filma om $delayS s"
                    },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { save(true) }, modifier = Modifier.weight(1f)) { Text("Spara senaste") }
                OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.weight(1f)) {
                    Text(if (savedCount > 0) "Mina svingar (+$savedCount)" else "Mina svingar")
                }
            }
            Text(
                "Volymknappen eller en Bluetooth-fjärrutlösare sparar också de senaste sekunderna.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, enabled: Boolean, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−", enabled, onMinus)
            Text(value, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.width(38.dp))
            StepButton("+", enabled, onPlus)
        }
    }
}

@Composable
private fun StepButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 18.sp, color = Color.White.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
private fun LevelMeter(level: Float, threshold: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(10.dp)
            .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(5.dp)),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(if (level >= threshold) Sun else Green, size = size.copy(width = size.width * level))
            val x = size.width * threshold.coerceIn(0f, 1f)
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 3f)
        }
    }
}

@Composable
fun Message(text: String) {
    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text)
    }
}
