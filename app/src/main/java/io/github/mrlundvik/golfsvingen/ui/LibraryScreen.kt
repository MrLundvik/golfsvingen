package io.github.mrlundvik.golfsvingen.ui

import android.Manifest
import android.app.Activity
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import io.github.mrlundvik.golfsvingen.pose.PoseStore
import io.github.mrlundvik.golfsvingen.store.Reference
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.mrlundvik.golfsvingen.store.Clip
import io.github.mrlundvik.golfsvingen.store.ClipStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val readPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

/**
 * Listan med svingar. Med [pickFor] väljer man i stället en sving att jämföra med, och referenssvingen står först.
 */
@Composable
fun LibraryScreen(onOpen: (Clip) -> Unit, onBack: () -> Unit, onCompare: (Clip, Clip) -> Unit, pickFor: Clip? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clips by remember { mutableStateOf<List<Clip>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reference by remember { mutableStateOf<Uri?>(null) }
    val reload: () -> Unit = {
        scope.launch {
            val all = withContext(Dispatchers.IO) { ClipStore.list(context) }
            reference = Reference.get(context)
            clips = if (pickFor == null) all
            else all.filter { it.uri != pickFor.uri }.sortedByDescending { it.uri == reference }
            loaded = true
        }
    }
    // Markerade svingar. Håll inne en sving för att börja markera
    var selected by remember { mutableStateOf<Set<Uri>>(emptySet()) }
    val selecting = selected.isNotEmpty()
    val toggle: (Clip) -> Unit = { c -> selected = if (c.uri in selected) selected - c.uri else selected + c.uri }
    BackHandler(enabled = selecting) { selected = emptySet() }
    // Telefonen frågar själv om svingarna får läggas i papperskorgen
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val gone = clips.filter { it.uri in selected }
            scope.launch(Dispatchers.IO) {
                for (c in gone) {
                    runCatching { PoseStore.delete(context, c) }
                    runCatching { MarkStore.delete(context, c) }
                }
            }
            val text = if (gone.size == 1) "Svingen ligger i papperskorgen i 30 dagar" else "${gone.size} svingar ligger i papperskorgen i 30 dagar"
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            selected = emptySet()
        }
        reload()
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, readPermission) == PackageManager.PERMISSION_GRANTED) reload()
        else launcher.launch(readPermission)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (pickFor != null) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Tillbaka") }
                Text("Jämför med…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                "Välj den andra svingen. Märk en sving som referens i spelaren, så står den först här.",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 24.dp, end = 16.dp, bottom = 4.dp),
            )
        } else if (selecting) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { selected = emptySet() }) { Text("Avbryt") }
                Text("${selected.size} valda", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.weight(1f))
                if (selected.size == 2) {
                    TextButton(onClick = {
                        // Den nyare till vänster, och referenssvingen alltid till höger
                        val (a, b) = clips.filter { it.uri in selected }
                            .sortedWith(compareBy<Clip>({ it.uri == reference }, { -it.takenSec }))
                        selected = emptySet()
                        onCompare(a, b)
                    }) { Text("Jämför") }
                } else {
                    TextButton(onClick = { selected = clips.map { it.uri }.toSet() }, enabled = selected.size < clips.size) { Text("Alla") }
                }
                TextButton(onClick = {
                    val request = MediaStore.createTrashRequest(context.contentResolver, selected.toList(), true)
                    trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                }) { Text("Radera", color = Warn) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ Kamera") }
                Text("Mina svingar", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
            if (clips.isNotEmpty()) {
                Text(
                    "Håll inne på en sving för att markera. Markera två för att jämföra dem.",
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                )
            }
        }
        if (loaded && clips.isEmpty()) {
            Text(
                "Inga svingar ännu. Ställ telefonen så att hela kroppen syns och slå ett slag. Bollträffen sparar svingen.",
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(clips, key = { it.uri.toString() }) { clip ->
                ClipRow(
                    clip,
                    selected = clip.uri in selected,
                    isReference = clip.uri == reference,
                    onClick = {
                        when {
                            pickFor != null -> onCompare(pickFor, clip)
                            selecting -> toggle(clip)
                            else -> onOpen(clip)
                        }
                    },
                    onLongClick = { if (pickFor == null) toggle(clip) },
                )
            }
        }
    }
}

private val dateFormat = SimpleDateFormat("EEE d MMM HH:mm", Locale("sv", "SE"))

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipRow(clip: Clip, selected: Boolean, isReference: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Green.copy(alpha = 0.18f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumb(clip.uri)
            if (selected) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(3.dp).size(22.dp).clip(CircleShape).background(Green),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", color = Color.Black, fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(dateFormat.format(Date(clip.takenSec * 1000)), style = MaterialTheme.typography.titleMedium)
            val seconds = String.format(Locale("sv", "SE"), "%.1f", clip.durationMs / 1000.0)
            val trimmed = if (clip.name.contains("_klipp")) " · klippt" else ""
            Text("$seconds s · ${clip.sizeBytes / 1_000_000} MB$trimmed", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
            if (isReference) Text("★ Referens", fontSize = 13.sp, color = Sun)
        }
    }
}

@Composable
private fun Thumb(uri: Uri) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
        }
    }
    Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF22302A))) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
