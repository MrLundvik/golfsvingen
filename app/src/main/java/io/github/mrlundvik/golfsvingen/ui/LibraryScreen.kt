package io.github.mrlundvik.golfsvingen.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

@Composable
fun LibraryScreen(onOpen: (Clip) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clips by remember { mutableStateOf<List<Clip>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    val reload: () -> Unit = {
        scope.launch {
            clips = withContext(Dispatchers.IO) { ClipStore.list(context) }
            loaded = true
        }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload() }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, readPermission) == PackageManager.PERMISSION_GRANTED) reload()
        else launcher.launch(readPermission)
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Kamera") }
            Text("Mina svingar", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
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
            items(clips, key = { it.uri.toString() }) { clip -> ClipRow(clip) { onOpen(clip) } }
        }
    }
}

private val dateFormat = SimpleDateFormat("EEE d MMM HH:mm", Locale("sv", "SE"))

@Composable
private fun ClipRow(clip: Clip, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(clip.uri)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(dateFormat.format(Date(clip.dateAddedSec * 1000)), style = MaterialTheme.typography.titleMedium)
            val seconds = String.format(Locale("sv", "SE"), "%.1f", clip.durationMs / 1000.0)
            Text("$seconds s · ${clip.sizeBytes / 1_000_000} MB", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f))
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
