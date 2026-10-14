package com.dsh.mobile.security

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine

private val Context.perAgentProviderStore by preferencesDataStore(name = "per_agent_providers")

/**
 * Per-agent provider configuration: which provider each coding agent uses, plus
 * a fully custom endpoint.
 *
 * ## Why this exists
 *
 * Credentials used to be global — one key per vendor, injected into whichever
 * agent happened to be running. That made three things impossible:
 *  - using Claude Code with an Anthropic key and Codex with OpenRouter;
 *  - pointing an agent at a self-hosted or local model server;
 *  - knowing which agent would break if one vendor's key were rotated.
 *
 * ## Secret handling
 *
 * Only the *choice* of provider and the base URL live here, both non-secret and
 * safe in a plain DataStore. The key itself is encrypted through
 * [ApiKeyVault]'s EncryptedSharedPreferences under a per-agent pref key. A
 * base URL is not a credential, but it is still user configuration that should
 * not leak into logs, so it is validated rather than trusted verbatim.
 */
class PerAgentProviderStore(
    private val context: Context,
    private val vault: ApiKeyVault,
) {

    /** A user's full provider choice for one agent. */
    data class Binding(
        val providerId: String,
        val baseUrl: String? = null,
        val model: String? = null,
    )

    private fun choiceKey(agentId: String) = stringPreferencesKey("provider_$agentId")
    private fun baseKey(agentId: String) = stringPreferencesKey("baseurl_$agentId")
    private fun modelKey(agentId: String) = stringPreferencesKey("model_$agentId")

    fun binding(agentId: String): Flow<Binding> = combine(
        context.perAgentProviderStore.data.map { it[choiceKey(agentId)] },
        context.perAgentProviderStore.data.map { it[baseKey(agentId)] },
        context.perAgentProviderStore.data.map { it[modelKey(agentId)] },
    ) { provider, base, model ->
        Binding(
            providerId = provider ?: DEFAULT_PROVIDER,
            baseUrl = base?.takeIf { isValidBaseUrl(it) },
            model = model?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun setProvider(agentId: String, providerId: String) {
        context.perAgentProviderStore.edit { it[choiceKey(agentId)] = providerId }
    }

    /**
     * Set a custom OpenAI-compatible endpoint.
     *
     * A blank value clears it and falls back to the provider default, so
     * "remove custom endpoint" is reachable without a separate control.
     */
    suspend fun setBaseUrl(agentId: String, baseUrl: String) {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) {
            context.perAgentProviderStore.edit { it.remove(baseKey(agentId)) }
            return
        }
        if (!isValidBaseUrl(trimmed)) {
            throw IllegalArgumentException("Not a valid base URL: $trimmed")
        }
        context.perAgentProviderStore.edit { it[baseKey(agentId)] = trimmed }
    }

    suspend fun setModel(agentId: String, model: String) {
        val trimmed = model.trim()
        if (trimmed.isEmpty()) {
            context.perAgentProviderStore.edit { it.remove(modelKey(agentId)) }
            return
        }
        context.perAgentProviderStore.edit { it[modelKey(agentId)] = trimmed }
    }

    /** Store an agent's key under its own name, never shared with other agents. */
    fun saveKeyFor(agentId: String, key: String) = vault.saveForAgent(agentId, key)

    fun keyFor(agentId: String): String = vault.agentKey(agentId)

    /**
     * Environment for one agent's child process.
     *
     * Precedence, in order: the agent's own key, then its chosen provider's
     * default key, then nothing. An agent-specific key always wins so a user can
     * give Claude Code a different key to the same vendor without touching the
     * global one.
     */
    fun environmentFor(
        agentId: String,
        provider: String,
        baseUrl: String? = null,
    ): Map<String, String> {
        val own = vault.agentKey(agentId)
        val shared = vault.apiKey(ApiKeyVault.Provider.fromId(provider))
        val key = own.ifBlank { shared }
        // Delegates to the pure companion so the mapping is pinned by JVM tests
        // without a Context or EncryptedSharedPreferences.
        return Companion.envFor(provider, key, baseUrl)
    }

    suspend fun baseUrlFor(agentId: String): String? =
        binding(agentId).first().baseUrl

    /**
     * Reject anything that is not a plain http(s) origin.
     *
     * This string becomes a base URL that the agent sends its API key to, so a
     * permissive validator would turn a typo into credential exfiltration to
     * a typo'd host. No credentials in the URL, no custom scheme, no path.
     */
    fun isValidBaseUrl(value: String): Boolean = Companion.isValidBaseUrl(value)

    /**
     * Pure env-var mapping + base-URL validation for one agent's child process.
     *
     * Extracted here (rather than inlined in [environmentFor]) so the precedence
     * rule — agent-specific key beats shared provider key — and the provider →
     * env-var mapping are pinned by JVM tests without a Context. An empty key
     * injects nothing (rather than an empty-string var that some agents treat
     * as an explicit blank credential). OpenRouter agents also get
     * OPENAI_API_KEY because their CLI routes through the OpenAI-compatible
     * shape; DeepSeek additionally gets DSH_API_KEY for the plugin host.
     */
    internal companion object {
        const val DEEPSEEK_ID = "deepseek"
        const val OPENROUTER_ID = "openrouter"
        const val ANTHROPIC_ID = "anthropic"
        const val DEFAULT_PROVIDER = DEEPSEEK_ID

        fun providerEnvVar(provider: String): String = when (provider) {
            ANTHROPIC_ID -> "ANTHROPIC_API_KEY"
            OPENROUTER_ID -> "OPENROUTER_API_KEY"
            else -> "DEEPSEEK_API_KEY"
        }

        fun isValidBaseUrl(value: String): Boolean {
            if (!value.startsWith("https://") && !value.startsWith("http://")) return false
            val rest = value.substringAfter("://")
            if (rest.isEmpty()) return false
            if (rest.contains('/')) return false          // origin only, no path
            if (rest.contains('@')) return false          // no embedded credentials
            if (rest.contains(' ')) return false
            val host = rest.substringBefore(':')
            if (host.isEmpty()) return false
            if (host.any { it.isWhitespace() }) return false
            return host.contains('.') || host == "localhost"
        }

        fun envFor(provider: String, agentKey: String, baseUrl: String?): Map<String, String> =
            buildMap {
                if (agentKey.isNotBlank()) {
                    put(providerEnvVar(provider), agentKey)
                    if (provider == OPENROUTER_ID) put("OPENAI_API_KEY", agentKey)
                    if (provider == DEEPSEEK_ID) put("DSH_API_KEY", agentKey)
                }
                val base = baseUrl?.takeIf { isValidBaseUrl(it) }
                if (base != null) {
                    put("OPENAI_BASE_URL", base)
                    put("ANTHROPIC_BASE_URL", base)
                    put("OPENAI_API_BASE", base)
                }
            }
    }
}
