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

    // -- per-agent credentials ----------------------------------------------
    //
    // Stored under the same encrypted prefs, but namespaced per agent so one
    // agent can hold a different key for the same vendor than another. This is
    // what makes "Claude Code on an Anthropic key, Codex on OpenRouter" work
    // without either agent's credential leaking into the other's process.

    fun agentKey(agentId: String): String =
        prefs.getString(agentPrefKey(agentId), "").orEmpty()

    fun saveForAgent(agentId: String, key: String) {
        val clean = key.trim()
        if (clean.isEmpty()) {
            // Empty means "clear", not "store an empty string", so clearing
            // actually removes the entry from encrypted storage.
            prefs.edit().remove(agentPrefKey(agentId)).apply()
            return
        }
        prefs.edit().putString(agentPrefKey(agentId), clean).apply()
    }

    fun clearForAgent(agentId: String) = saveForAgent(agentId, "")

    /** Agent ids are used to build a pref key, so they must be filename-safe. */
    private fun agentPrefKey(agentId: String): String {
        val safe = agentId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        require(safe.isNotEmpty()) { "invalid agent id" }
        return "agent_key_$safe"
    }

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
        OPENROUTER("OpenRouter", "key_openrouter", "OPENROUTER_API_KEY");

        companion object {
            /**
             * Resolve a stored provider id, falling back to DeepSeek.
             *
             * Must never throw: the value is read back from persisted config
             * that an older or newer build may have written, and a crash on
             * boot because of an unknown id would be far worse than defaulting.
             */
            fun fromId(id: String): Provider =
                entries.firstOrNull { it.name.equals(id, ignoreCase = true) } ?: DEEPSEEK
        }
    }

    private companion object {
        const val PREFS_NAME = "dsh_encrypted_keys"
    }
}
