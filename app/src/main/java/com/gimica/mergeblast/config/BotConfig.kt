package com.gimica.mergeblast.config

import android.content.Context
import android.util.Log
import com.google.gson.Gson

/**
 * Runtime settings that actually affect the screenshot/vision autoplayer.
 *
 * Keep this intentionally small: the old 4x4 Accessibility board parser and its heuristic knobs
 * are no longer part of the Merge Blast execution path, so exposing those settings would be
 * misleading.
 */
class BotConfig private constructor(
    val targetPackage: String = "com.gimica.mergeblast",
    val minMoveIntervalMs: Long = 150,
    val enableDebugLogging: Boolean = false
) {
    companion object {
        private const val TAG = "BotConfig"
        private const val PREFS_NAME = "bot_config"
        private const val KEY_CONFIG_JSON = "config_json"
        private const val DEFAULT_TARGET_PACKAGE = "com.gimica.mergeblast"
        private val gson = Gson()

        fun load(context: Context): BotConfig {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_CONFIG_JSON, "").orEmpty()
            if (json.isBlank()) return BotConfig()

            return try {
                val parsed = gson.fromJson(json, BotConfig::class.java)
                if (parsed == null) {
                    Log.w(TAG, "Stored config decoded to null; using defaults")
                    BotConfig()
                } else {
                    parsed.normalized()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invalid stored bot config; using defaults", e)
                BotConfig()
            }
        }

        fun save(context: Context, config: BotConfig) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_CONFIG_JSON, gson.toJson(config.normalized())).apply()
        }

        fun getDefaults(): BotConfig = BotConfig()
    }

    /** Gson bypasses the constructor, so persisted values still need explicit validation. */
    fun normalized(): BotConfig = BotConfig(
        targetPackage = targetPackage.trim().takeIf { it.isNotEmpty() } ?: DEFAULT_TARGET_PACKAGE,
        minMoveIntervalMs = minMoveIntervalMs.coerceIn(0L, 5_000L),
        enableDebugLogging = enableDebugLogging
    )

    fun copy(
        targetPackage: String = this.targetPackage,
        minMoveIntervalMs: Long = this.minMoveIntervalMs,
        enableDebugLogging: Boolean = this.enableDebugLogging
    ): BotConfig = BotConfig(
        targetPackage = targetPackage,
        minMoveIntervalMs = minMoveIntervalMs,
        enableDebugLogging = enableDebugLogging
    ).normalized()
}
