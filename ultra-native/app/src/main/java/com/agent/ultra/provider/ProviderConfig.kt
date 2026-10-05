package com.agent.ultra.provider

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Provider settings for the OpenAI-compatible brain endpoint.
 *
 * Source of truth: SharedPreferences. Dev seed: if no key is stored, we read
 * /sdcard/Android/data/com.agent.ultra/files/ultra_provider.json once at first
 * need — that path is adb-pushable, which keeps key entry automatable with no
 * UI taps. The file is never required at runtime afterwards.
 */
data class ProviderConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    val isUsable: Boolean get() = apiKey.isNotBlank()

    companion object {
        private const val PREFS = "ultra_provider"
        private const val K_BASE = "base_url"
        private const val K_KEY = "api_key"
        private const val K_MODEL = "model"

        private const val DEFAULT_BASE = "https://api.venice.ai/api/v1"
        private const val DEFAULT_MODEL = "llama-3.3-70b"

        fun load(context: Context): ProviderConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var cfg = ProviderConfig(
                baseUrl = prefs.getString(K_BASE, DEFAULT_BASE) ?: DEFAULT_BASE,
                apiKey = prefs.getString(K_KEY, "") ?: "",
                model = prefs.getString(K_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL,
            )
            if (cfg.apiKey.isBlank()) {
                cfg = trySeedFromFile(context) ?: cfg
                if (cfg.apiKey.isNotBlank()) save(context, cfg)
            }
            return cfg
        }

        fun save(context: Context, cfg: ProviderConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(K_BASE, cfg.baseUrl)
                .putString(K_KEY, cfg.apiKey)
                .putString(K_MODEL, cfg.model)
                .apply()
        }

        private fun trySeedFromFile(context: Context): ProviderConfig? {
            return try {
                val dir = context.getExternalFilesDir(null) ?: return null
                val f = File(dir, "ultra_provider.json")
                if (!f.exists()) return null
                val j = JSONObject(f.readText())
                val key = j.optString("apiKey", "")
                if (key.isBlank()) return null
                ProviderConfig(
                    baseUrl = j.optString("baseUrl", DEFAULT_BASE),
                    apiKey = key,
                    model = j.optString("model", DEFAULT_MODEL),
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
