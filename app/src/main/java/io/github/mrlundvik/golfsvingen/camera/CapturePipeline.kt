package io.github.mrlundvik.golfsvingen.camera

import android.content.Context
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.Surface
import io.github.mrlundvik.golfsvingen.store.ClipStore
import java.util.concurrent.Executors

/** Håller ihop kameran, den löpande bufferten och sparandet av svingar. */
class CapturePipeline(private val context: Context, val config: HighSpeedConfig) {
    interface Listener {
        fun onReady(text: String)
        fun onSaved(name: String)
        fun onError(text: String)
        /** Slutartid och ljuskänslighet, ungefär en gång i halvsekunden. */
        fun onExposure(exposureNs: Long, iso: Int) {}
    }

    var listener: Listener? = null
    private var camera: HighSpeedCamera? = null
    private var encoder: RingBufferEncoder? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun start(preview: Surface) {
        stop()
        val enc = try {
            RingBufferEncoder(config.size.width, config.size.height, config.fps)
        } catch (e: Exception) {
            listener?.onError("Videokodaren klarar inte ${config.fps} fps: ${e.message}")
            return
        }
        encoder = enc
        camera = HighSpeedCamera(context, config).also { cam ->
            cam.start(
                preview, enc.inputSurface,
                onExposure = { ns, iso -> main.post { listener?.onExposure(ns, iso) } },
                onError = { msg -> main.post { listener?.onError(msg) } },
            )
        }
        listener?.onReady("Redo · ${config.fps} fps · ${config.size.width}×${config.size.height} · ${enc.codecLabel}")
    }

    fun stop() {
        camera?.release()
        camera = null
        encoder?.release()
        encoder = null
    }

    /** Sparar från [preUs] mikrosekunder före till [postUs] efter nu. */
    fun save(preUs: Long, postUs: Long): Boolean {
        val enc = encoder ?: return false
        return enc.requestSave(preUs, postUs, ::write)
    }

    /** Videons tid just nu, att starta en filmning från. -1 om kameran inte är igång. */
    fun nowUs(): Long = encoder?.currentPtsUs ?: -1L

    /** Sparar från tiden [startUs], som [nowUs] gav, till [postUs] efter nu. */
    fun saveFrom(startUs: Long, postUs: Long): Boolean {
        val enc = encoder ?: return false
        return enc.requestSaveFrom(startUs, postUs, ::write)
    }

    private fun write(frames: List<RingBufferEncoder.Frame>, format: MediaFormat) {
        writer.execute {
            try {
                val name = ClipStore.write(context, frames, format, config.sensorOrientation)
                main.post { listener?.onSaved(name) }
            } catch (e: Exception) {
                main.post { listener?.onError("Svingen kunde inte sparas: ${e.message}") }
            }
        }
    }

    fun release() {
        stop()
        writer.shutdown()
    }
}
