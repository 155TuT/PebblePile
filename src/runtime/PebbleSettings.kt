package com.pebblepile.android

import android.content.Context

enum class PebbleTextScale(val label: String, val multiplier: Float) {
    STANDARD("标准", 1f),
    LARGE("大号", 1.25f),
    EXTRA_LARGE("特大", 1.5f),
}

data class PebbleSettings(
    val autoOrganize: Boolean = true,
    val inferenceAssist: Boolean = true,
    val textScale: PebbleTextScale = PebbleTextScale.STANDARD,
)

class PebbleSettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("pebblepile_settings", Context.MODE_PRIVATE)

    fun load(): PebbleSettings = PebbleSettings(
        autoOrganize = preferences.getBoolean(KEY_AUTO_ORGANIZE, true),
        inferenceAssist = preferences.getBoolean(KEY_INFERENCE_ASSIST, true),
        textScale = PebbleTextScale.entries.firstOrNull {
            it.name == preferences.getString(KEY_TEXT_SCALE, null)
        } ?: PebbleTextScale.STANDARD,
    )

    fun save(settings: PebbleSettings) {
        preferences.edit()
            .putBoolean(KEY_AUTO_ORGANIZE, settings.autoOrganize)
            .putBoolean(KEY_INFERENCE_ASSIST, settings.inferenceAssist)
            .putString(KEY_TEXT_SCALE, settings.textScale.name)
            .apply()
    }

    private companion object {
        const val KEY_AUTO_ORGANIZE = "auto_organize"
        const val KEY_INFERENCE_ASSIST = "inference_assist"
        const val KEY_TEXT_SCALE = "text_scale"
    }
}
