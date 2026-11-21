package io.github.mrlundvik.golfsvingen.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

val Green = Color(0xFF7BD389)
val Sun = Color(0xFFFFD166)
val Warn = Color(0xFFFF8A65)

@Composable
fun GolfTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Green,
            onPrimary = Color(0xFF0F1512),
            secondary = Sun,
            background = Color(0xFF0F1512),
            surface = Color(0xFF17201B),
            surfaceVariant = Color(0xFF22302A),
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}
