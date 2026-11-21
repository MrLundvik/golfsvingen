package io.github.mrlundvik.golfsvingen.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

private const val SOUND_PRE_US = 3_500_000L
private const val SOUND_POST_US = 1_500_000L
private const val MANUAL_PRE_US = 5_000_000L
private const val MANUAL_POST_US = 300_000L

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

    val save: (Boolean) -> Unit = { manual ->
        val ok = if (manual) pipeline.save(MANUAL_PRE_US, MANUAL_POST_US) else pipeline.save(SOUND_PRE_US, SOUND_POST_US)
        if (ok) {
            status = if (manual) "Sparar de senaste sekunderna…" else "Träff hörd, sparar svingen…"
            flash = true
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
                onImpact = { main.post { save(false) } },
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { save(true) }, modifier = Modifier.weight(1f)) { Text("Spara senaste") }
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
