package io.github.mrlundvik.golfsvingen

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.mrlundvik.golfsvingen.store.Clip
import io.github.mrlundvik.golfsvingen.ui.CameraScreen
import io.github.mrlundvik.golfsvingen.ui.GolfTheme
import io.github.mrlundvik.golfsvingen.ui.LibraryScreen
import io.github.mrlundvik.golfsvingen.ui.PlayerScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class MainActivity : ComponentActivity() {
    private val _shutter = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Volymknapparna och Bluetooth-fjärrutlösare sparar senaste svingen när kameran visas. */
    val shutter: SharedFlow<Unit> get() = _shutter
    var shutterEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { GolfTheme { App(this) } }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val isShutterKey = keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_CAMERA ||
            keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        if (shutterEnabled && isShutterKey) {
            if (event.repeatCount == 0) _shutter.tryEmit(Unit)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

sealed interface Screen {
    data object Camera : Screen
    data object Library : Screen
    data class Player(val clip: Clip) : Screen
}

@Composable
fun App(activity: MainActivity) {
    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
    SideEffect { activity.shutterEnabled = screen == Screen.Camera }
    BackHandler(enabled = screen != Screen.Camera) {
        screen = if (screen is Screen.Player) Screen.Library else Screen.Camera
    }
    when (val s = screen) {
        Screen.Camera -> CameraScreen(activity, onOpenLibrary = { screen = Screen.Library })
        Screen.Library -> LibraryScreen(onOpen = { screen = Screen.Player(it) }, onBack = { screen = Screen.Camera })
        is Screen.Player -> PlayerScreen(s.clip, onBack = { screen = Screen.Library })
    }
}
