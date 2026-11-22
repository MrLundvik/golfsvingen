package io.github.mrlundvik.golfsvingen.pose

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.mrlundvik.golfsvingen.store.Clip
import java.util.Locale

/**
 * Skriver kroppspunkterna som en CSV-fil i Hämtade filer/Golfsvingen, för att kunna felsöka och
 * justera mätningarna på en dator. Filen innehåller bara punkterna, ingen video.
 */
object PoseExport {
    fun export(context: Context, clip: Clip, raw: PoseTrack, clean: PoseTrack?): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, clip.name.substringBeforeLast('.') + "_matdata.csv")
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Golfsvingen")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Filen kunde inte skapas")
        try {
            resolver.openOutputStream(uri)!!.bufferedWriter().use { w ->
                w.write("# Golfsvingen mätdata. x och y är andelar av bildens bredd och höjd, v hur säkert punkten syns\n")
                w.write("# width=${raw.width} height=${raw.height} delegate=${raw.delegate} analysisMs=${raw.analysisMs}\n")
                w.write("source,t_us")
                for (i in 0 until Landmark.COUNT) w.write(",x$i,y$i,v$i")
                w.write("\n")
                fun rows(name: String, track: PoseTrack) {
                    val stride = Landmark.COUNT * 3
                    for (f in 0 until track.size) {
                        w.write(name)
                        w.write(",")
                        w.write(track.timesUs[f].toString())
                        for (k in 0 until stride) {
                            w.write(",")
                            w.write(String.format(Locale.ROOT, "%.4f", track.data[f * stride + k]))
                        }
                        w.write("\n")
                    }
                }
                rows("raw", raw)
                if (clean != null) rows("clean", clean)
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }
}
