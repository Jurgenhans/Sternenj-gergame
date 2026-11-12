package de.dateiagent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Nicht-geheime Einstellungen + verschluesselter API-Schluessel (Android Keystore). */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure: SharedPreferences = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "secure_settings", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var folderUri: String?
        get() = prefs.getString("folderUri", null)
        set(v) = prefs.edit().putString("folderUri", v).apply()

    var providerId: String
        get() = prefs.getString("providerId", "openai") ?: "openai"
        set(v) = prefs.edit().putString("providerId", v).apply()

    var baseUrl: String
        get() = prefs.getString("baseUrl", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
        set(v) = prefs.edit().putString("baseUrl", v.trim().trimEnd('/')).apply()

    var model: String
        get() = prefs.getString("model", "gpt-4o") ?: "gpt-4o"
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    /** Einwilligung: Dateiinhalte duerfen an die Cloud-KI uebertragen werden. */
    var cloudConsent: Boolean
        get() = prefs.getBoolean("cloudConsent", false)
        set(v) = prefs.edit().putBoolean("cloudConsent", v).apply()

    /** Einwilligung speziell fuer Bilder (Bildanalyse). */
    var imageConsent: Boolean
        get() = prefs.getBoolean("imageConsent", false)
        set(v) = prefs.edit().putBoolean("imageConsent", v).apply()

    var maxIterations: Int
        get() = prefs.getInt("maxIterations", 40)
        set(v) = prefs.edit().putInt("maxIterations", v.coerceIn(5, 200)).apply()

    var workingStyle: String
        get() = prefs.getString("workingStyle", "") ?: ""
        set(v) = prefs.edit().putString("workingStyle", v).apply()

    var apiKey: String?
        get() = secure.getString("apiKey", null)
        set(v) = secure.edit().apply { if (v.isNullOrBlank()) remove("apiKey") else putString("apiKey", v.trim()) }.apply()
}
