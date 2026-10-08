package com.blockyou.firestick

import android.content.Context

/** Qualidade máxima escolhida pelo usuário (altura em pixels); 0 = automática. */
object QualityPreference {
    private const val PREFS = "player"
    private const val KEY_MAX_HEIGHT = "max_height"

    fun get(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MAX_HEIGHT, 0)

    fun set(context: Context, height: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MAX_HEIGHT, height)
            .apply()
    }
}

/** Idioma de áudio escolhido pelo usuário (ex.: "pt-BR"); null = áudio original do vídeo. */
object AudioLanguagePreference {
    private const val PREFS = "player"
    private const val KEY_LANGUAGE = "audio_language"

    fun get(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)

    fun set(context: Context, language: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
    }
}
