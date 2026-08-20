package com.autotap.app

import android.content.Context
import android.content.SharedPreferences

/**
 * User-configurable run parameters.
 *
 * @param targetText   Button/label text the accessibility service looks for and taps.
 * @param questionCount How many tap cycles to perform (the "set number of questions").
 * @param delayMs      Pause after each tap so the next screen can render.
 * @param useRawTap    When true, ignore text and dispatch a raw gesture at (tapX, tapY).
 * @param tapX         X coordinate (pixels, current screen density) for raw taps.
 * @param tapY         Y coordinate for raw taps.
 */
data class AutoTapConfig(
    val targetText: String,
    val questionCount: Int,
    val delayMs: Long,
    val useRawTap: Boolean,
    val tapX: Int,
    val tapY: Int
)

object ConfigStore {
    private const val PREFS = "autotap_config"

    fun load(context: Context): AutoTapConfig {
        val p: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return AutoTapConfig(
            targetText = p.getString("targetText", "Next") ?: "Next",
            questionCount = p.getInt("questionCount", 10),
            delayMs = p.getLong("delayMs", 1500L),
            useRawTap = p.getBoolean("useRawTap", false),
            tapX = p.getInt("tapX", 0),
            tapY = p.getInt("tapY", 0)
        )
    }

    fun save(context: Context, config: AutoTapConfig) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString("targetText", config.targetText)
            putInt("questionCount", config.questionCount)
            putLong("delayMs", config.delayMs)
            putBoolean("useRawTap", config.useRawTap)
            putInt("tapX", config.tapX)
            putInt("tapY", config.tapY)
            apply()
        }
    }
}
