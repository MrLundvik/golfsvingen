package io.github.mrlundvik.golfsvingen.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor

/** Bakre kamerans bästa höghastighetsläge: helst exakt 240 fps, och då den största upplösningen. */
data class HighSpeedConfig(
    val cameraId: String,
    val size: Size,
    val fpsRange: Range<Int>,
    val sensorOrientation: Int,
) {
    val fps: Int get() = fpsRange.upper
}

object HighSpeedSupport {
    fun find(context: Context): HighSpeedConfig? {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        var best: HighSpeedConfig? = null
        for (id in manager.cameraIdList) {
            val ch = try { manager.getCameraCharacteristics(id) } catch (e: Exception) { continue }
            if (ch.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (!caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)) continue
            val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            val orientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            for (size in map.highSpeedVideoSizes) {
                val range = map.getHighSpeedVideoFpsRangesFor(size)
                    .filter { it.lower == it.upper }
                    .maxByOrNull { it.upper } ?: continue
                val candidate = HighSpeedConfig(id, size, range, orientation)
                if (best == null || score(candidate) > score(best)) best = candidate
            }
        }
        return best
    }

    // 240 fps går före högre bildtakt i lägre upplösning, sedan avgör upplösningen
    private fun score(c: HighSpeedConfig): Long {
        val fpsScore = when {
            c.fps == 240 -> 3L
            c.fps > 240 -> 2L
            else -> 1L
        }
        return fpsScore * 1_000_000_000L + c.size.width.toLong() * c.size.height
    }
}

/** Öppnar kameran i höghastighetsläge med två ytor: förhandsvisningen och videokodaren. */
class HighSpeedCamera(private val context: Context, private val config: HighSpeedConfig) {
    private val thread = HandlerThread("kamera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    @Volatile private var closed = false
    private var onExposure: (Long, Int) -> Unit = { _, _ -> }

    // Slutartiden avgör hur suddiga händerna blir. Den läses av en gång per halvsekund
    private val results = object : CameraCaptureSession.CaptureCallback() {
        private var count = 0
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (count++ % (config.fps / 2).coerceAtLeast(1) != 0) return
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            onExposure(exposure, result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(preview: Surface, encoder: Surface, onExposure: (Long, Int) -> Unit, onError: (String) -> Unit) {
        this.onExposure = onExposure
        val manager = context.getSystemService(CameraManager::class.java)
        try {
            manager.openCamera(config.cameraId, executor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed) { camera.close(); return }
                    device = camera
                    createSession(camera, preview, encoder, onError)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    onError("Kameran kunde inte öppnas (fel $error). Stäng andra appar som använder kameran.")
                }
            })
        } catch (e: Exception) {
            onError("Kameran kunde inte öppnas: ${e.message}")
        }
    }

    private fun createSession(camera: CameraDevice, preview: Surface, encoder: Surface, onError: (String) -> Unit) {
        val outputs = listOf(OutputConfiguration(preview), OutputConfiguration(encoder))
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (closed) { s.close(); return }
                session = s
                try {
                    val highSpeed = s as CameraConstrainedHighSpeedCaptureSession
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(preview)
                        addTarget(encoder)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, config.fpsRange)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                    }.build()
                    highSpeed.setRepeatingBurst(highSpeed.createHighSpeedRequestList(request), results, handler)
                } catch (e: Exception) {
                    Log.e("Golfsvingen", "Höghastighetsläget kunde inte starta", e)
                    onError("Höghastighetsläget kunde inte starta: ${e.message}")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                onError("Kameran godtog inte ${config.fps} fps i ${config.size.width}×${config.size.height}.")
            }
        }
        try {
            camera.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_HIGH_SPEED, outputs, executor, callback))
        } catch (e: Exception) {
            onError("Kameran kunde inte konfigureras: ${e.message}")
        }
    }

    fun release() {
        closed = true
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        thread.quitSafely()
    }
}
