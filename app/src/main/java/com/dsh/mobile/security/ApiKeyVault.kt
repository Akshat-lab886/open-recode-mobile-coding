package com.dsh.mobile.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Native credential vault backed by Android Keystore (AES-256 GCM).
 *
 * Users paste DeepSeek / Anthropic / OpenRouter keys into a Compose form;
 * keys are stored encrypted at rest and only ever injected as background
 * environment variables into the agent child processes — never rendered
 * into the WebView, never logged.
 */
class ApiKeyVault(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(provider: Provider, key: String) {
        prefs.edit().putString(provider.prefKey, key.trim()).apply()
    }

    /**
     * Named `apiKey`, not `get`, on purpose: inside `buildMap { }` a method
     * named `get` resolves to the MutableMap receiver, silently returning
     * `String?` and breaking inference.
     */
    fun apiKey(provider: Provider): String =
        prefs.getString(provider.prefKey, "").orEmpty()

    fun hasAnyKey(): Boolean = Provider.entries.any { apiKey(it).isNotBlank() }

    /** Snapshot injected as env vars into the agent process. Missing keys are omitted. */
    fun asEnvironment(): Map<String, String> = buildMap {
        val deepseek = apiKey(Provider.DEEPSEEK)
        val anthropic = apiKey(Provider.ANTHROPIC)
        val openrouter = apiKey(Provider.OPENROUTER)
        if (deepseek.isNotBlank()) {
            put("DEEPSEEK_API_KEY", deepseek)
            // DSH also honours a generic key for its default model route.
            put("DSH_API_KEY", deepseek)
        }
        if (anthropic.isNotBlank()) put("ANTHROPIC_API_KEY", anthropic)
        if (openrouter.isNotBlank()) {
            put("OPENROUTER_API_KEY", openrouter)
            put("OPENAI_API_KEY", openrouter)
            put("OPENAI_BASE_URL", "https://openrouter.ai/api/v1")
        }
    }

    enum class Provider(val label: String, val prefKey: String, val envVar: String) {
        DEEPSEEK("DeepSeek", "key_deepseek", "DEEPSEEK_API_KEY"),
        ANTHROPIC("Anthropic (Claude Code)", "key_anthropic", "ANTHROPIC_API_KEY"),
        OPENROUTER("OpenRouter", "key_openrouter", "OPENROUTER_API_KEY"),
    }

    private companion object {
        const val PREFS_NAME = "dsh_encrypted_keys"
    }
}
