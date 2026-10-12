package com.dsh.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.runtimeDataStore by preferencesDataStore(name = "runtime_config")

/**
 * Which agent backend is currently bound to the WebView.
 *
 * The install/run specifics live in [AgentCatalog]; this enum is the persisted,
 * stable identity of an agent choice, so adding a catalogue entry never
 * invalidates a user's saved config.
 */
enum class ActiveAgent(
    val label: String,
    val defaultPort: Int,
    /**
     * Stable id used to look this agent up in [AgentCatalog].
     *
     * Declared explicitly rather than derived from the enum name: the catalogue
     * ids are short (dsh, claude, codex) and do not match the enum constants,
     * so deriving silently produced ids that resolved to nothing.
     */
    val id: String,
) {
    DEEPSEEK_HARNESS("DeepSeek Harness", 3080, "dsh"),
    CLAUDE_CODE("Claude Code (via gateway)", 3091, "claude"),
    CODEX("Codex (via gateway)", 3092, "codex"),
    OPENCODE("OpenCode", 3093, "opencode"),
    HERMES("Hermes Agent (via gateway)", 3094, "hermes"),
    ANTIGRAVITY("Antigravity CLI (via gateway)", 3095, "antigravity");

    /** Catalogue entry backing this agent, if this build knows it. */
    val spec: AgentSpec? get() = AgentCatalog.specFor(id)
}

/** Touch-driven replacement for CLI flags.
 *
 * Modes are enforced at the app layer (gateway approval strictness +
 * `DSH_AGENT_MODE` env), never as fictional CLI flags — so switching modes
 * can never break the child argv.
 */
enum class AgentMode(val label: String, val description: String) {
    READ_ONLY(
        "Read-Only",
        "Inspect code and answer questions. No writes, no commands.",
    ),
    PLAN(
        "Plan",
        "Design the change first, apply only after you approve.",
    ),
    CREATIVE(
        "Creative",
        "Draft freely with writes allowed, commands gated.",
    ),
    CODE_EXECUTION(
        "Code Execution",
        "Full agent loop: writes and commands with approval sheets.",
    ),
}

/** Persisted, UI-editable runtime configuration. No CLI flags required. */
data class RuntimeConfig(
    val workspacePath: String = "",
    val mode: AgentMode = AgentMode.CODE_EXECUTION,
    val agent: ActiveAgent = ActiveAgent.DEEPSEEK_HARNESS,
    val loopbackPort: Int = ActiveAgent.DEEPSEEK_HARNESS.defaultPort,
)

class RuntimeConfigStore(private val context: Context) {

    private object Keys {
        val WORKSPACE = stringPreferencesKey("workspace_path")
        val MODE = stringPreferencesKey("agent_mode")
        val AGENT = stringPreferencesKey("active_agent")
        val PORT = stringPreferencesKey("loopback_port")
    }

    val config: Flow<RuntimeConfig> = context.runtimeDataStore.data.map { prefs ->
        RuntimeConfig(
            workspacePath = prefs[Keys.WORKSPACE].orEmpty(),
            mode = prefs[Keys.MODE]?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() }
                ?: AgentMode.CODE_EXECUTION,
            agent = prefs[Keys.AGENT]?.let { runCatching { ActiveAgent.valueOf(it) }.getOrNull() }
                ?: ActiveAgent.DEEPSEEK_HARNESS,
            loopbackPort = prefs[Keys.PORT]?.toIntOrNull()
                ?: ActiveAgent.DEEPSEEK_HARNESS.defaultPort,
        )
    }

    suspend fun snapshot(): RuntimeConfig = config.first()

    suspend fun setWorkspace(absolutePath: String) {
        context.runtimeDataStore.edit { it[Keys.WORKSPACE] = absolutePath }
    }

    suspend fun setMode(mode: AgentMode) {
        context.runtimeDataStore.edit { it[Keys.MODE] = mode.name }
    }

    suspend fun setAgent(agent: ActiveAgent) {
        context.runtimeDataStore.edit {
            it[Keys.AGENT] = agent.name
            // Keep the port pinned per agent unless the user was given a dynamic one.
            if ((it[Keys.PORT]?.toIntOrNull() ?: 0) <= 0) {
                it[Keys.PORT] = agent.defaultPort.toString()
            }
        }
    }

    suspend fun setPort(port: Int) {
        context.runtimeDataStore.edit { it[Keys.PORT] = port.toString() }
    }
}
