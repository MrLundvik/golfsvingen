package io.github.mrlundvik.golfsvingen.store

import android.content.Context
import android.net.Uri

/** Referenssvingen, som andra svingar helst jämförs med. Bara en åt gången. */
object Reference {
    private const val KEY = "referenceClip"

    private fun prefs(context: Context) = context.getSharedPreferences("golfsvingen", Context.MODE_PRIVATE)

    fun get(context: Context): Uri? = prefs(context).getString(KEY, null)?.let(Uri::parse)

    fun set(context: Context, uri: Uri?) {
        prefs(context).edit().apply { if (uri == null) remove(KEY) else putString(KEY, uri.toString()) }.apply()
    }
}
