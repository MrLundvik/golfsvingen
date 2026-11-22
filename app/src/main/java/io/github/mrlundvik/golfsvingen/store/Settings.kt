package io.github.mrlundvik.golfsvingen.store

import android.content.Context

/** Spelarens inställningar. */
object Settings {
    /** Benet, från höftleden till fotleden, är ungefär så stor andel av kroppslängden. */
    private const val LEG_SHARE = 0.48f

    /** Benlängden utan inställd längd, som för en person på ungefär 167 cm. */
    const val DEFAULT_LEG_CM = 80f

    private const val KEY_HEIGHT = "heightCm"

    private fun prefs(context: Context) = context.getSharedPreferences("golfsvingen", Context.MODE_PRIVATE)

    /** Längden i centimeter, eller null om den inte är inställd. */
    fun heightCm(context: Context): Int? = prefs(context).getInt(KEY_HEIGHT, 0).takeIf { it in 100..230 }

    fun setHeightCm(context: Context, cm: Int?) {
        prefs(context).edit().apply { if (cm == null) remove(KEY_HEIGHT) else putInt(KEY_HEIGHT, cm) }.apply()
    }

    /** Benlängden som bilden räknas om till centimeter med. */
    fun legCm(context: Context): Float = heightCm(context)?.let { it * LEG_SHARE } ?: DEFAULT_LEG_CM
}
