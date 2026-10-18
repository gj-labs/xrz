package com.bindlab.shared.net

import android.content.Context
import org.json.JSONObject

/** Backend base URL: assets/config.json {"server": "..."} unless overridden in Settings. */
class ConfigStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("bindlab_config", Context.MODE_PRIVATE)

    val assetServer: String by lazy {
        runCatching {
            context.assets.open("config.json").bufferedReader().use { JSONObject(it.readText()).optString("server") }
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER
    }

    var server: String
        get() = prefs.getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() } ?: assetServer
        set(value) {
            prefs.edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()
        }

    fun resetServer() = prefs.edit().remove(KEY_SERVER).apply()

    fun getString(key: String, default: String) = prefs.getString(key, default) ?: default
    fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()

    companion object {
        const val DEFAULT_SERVER = "http://192.168.1.100:8000"
        private const val KEY_SERVER = "server"
    }
}
