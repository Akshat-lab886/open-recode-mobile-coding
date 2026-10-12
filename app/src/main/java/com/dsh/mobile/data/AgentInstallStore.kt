package com.dsh.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

private val Context.agentInstallStore by preferencesDataStore(name = "agent_installs")

/**
 * Which agents the user has actually asked for.
 *
 * ## Why this exists
 *
 * bootstrap.sh installs `@deepseek-ai/dsh` and `opencode-ai` unconditionally,
 * on every first boot, whether or not the user ever wants them. On a phone that
 * is hundreds of megabytes of the user's data and minutes of their time spent
 * on software they did not select. The complaint this addresses is precise:
 * the app decided what gets installed.
 *
 * So installation is opt-in from here: nothing installs unless the user picked
 * it, and a completed install is recorded so it never runs again.
 *
 * ## Idempotency
 *
 * Two independent guards, because either alone has a failure mode:
 *  - [completed] is only set after a successful install, so an interrupted one
 *    retries rather than being silently treated as done.
 *  - The actual work re-checks the binary on disk, so a user who deletes an
 *    agent's files gets a working reinstall rather than a false "already
 *    installed".
 */
class AgentInstallStore(private val context: Context) {

    companion object {
        /**
         * The install decision, as a pure function.
         *
         * Separated from [pending] so the idempotency rules can be tested on the
         * JVM: DataStore needs a real Context, and this is the logic that must
         * not regress. [onDisk] is injected so a test can describe an
         * interrupted install without building a fake filesystem layout.
         */
        fun pendingLogic(
            selected: Set<String>,
            completed: Set<String>,
            agentsDir: File,
            onDisk: (AgentSpec) -> Boolean,
        ): List<AgentSpec> = AgentCatalog.all.filter { spec ->
            spec.id in selected && !(completed.contains(spec.id) && onDisk(spec))
        }
    }

    private object Keys {
        val COMPLETED = stringSetPreferencesKey("install_completed")
        val USER_SELECTED = booleanPreferencesKey("user_has_selected")
        val SKIPPED = booleanPreferencesKey("user_skipped_selection")
    }

    /** Agents whose install has finished successfully. */
    val completed: Flow<Set<String>> =
        context.agentInstallStore.data.map { it[Keys.COMPLETED] ?: emptySet() }

    /** True once the user has been shown the picker and made a choice. */
    val userHasSelected: Flow<Boolean> =
        context.agentInstallStore.data.map { it[Keys.USER_SELECTED] ?: false }

    /** True when the user explicitly chose to skip agent installation. */
    val userSkipped: Flow<Boolean> =
        context.agentInstallStore.data.map { it[Keys.SKIPPED] ?: false }

    suspend fun isCompleted(id: String): Boolean =
        completed.first().contains(id)

    suspend fun hasSelected(): Boolean = userHasSelected.first()

    /**
     * Agents that still need installing: catalogue entries the user selected
     * whose binary is not yet on disk.
     *
     * The on-disk check is what makes this honest. A recorded "completed" flag
     * that disagrees with the filesystem would otherwise report an agent as
     * installed while spawning fails with ENOENT - the blank-page bug documented
     * on AgentRuntime.agentEntryPoint.
     */
    fun pending(
        selected: Set<String>,
        completed: Set<String>,
        agentsDir: java.io.File,
    ): List<AgentSpec> = AgentCatalog.all.filter { spec ->
        spec.id in selected && !isInstalled(spec, completed, agentsDir)
    }

    /**
     * An agent counts as installed only when its install was recorded AND its
     * binary is actually present.
     *
     * Both halves matter and each covers a different failure:
     *  - recording alone would claim success for an install that never
     *    completed, or that was undone by the user deleting files;
     *  - disk alone would re-download hundreds of megabytes on every app open,
     *    which is the complaint that made this opt-in in the first place.
     */
    fun isInstalled(spec: AgentSpec, completed: Set<String>, agentsDir: java.io.File): Boolean =
        completed.contains(spec.id) && isOnDisk(spec, agentsDir)

    /** Suspending convenience wrapper around [pending] for callers in a coroutine. */
    suspend fun pending(selected: Set<String>, agentsDir: java.io.File): List<AgentSpec> =
        pending(selected, completed.first(), agentsDir)

    /** True when the agent's executable actually exists. */
    fun isOnDisk(spec: AgentSpec, agentsDir: java.io.File): Boolean =
        if (spec.id == "dsh") {
            java.io.File(agentsDir, "dsh/lib/bin.js").isFile
        } else {
            java.io.File(agentsDir, spec.relativeBinary).isFile
        }

    /** Record the user's choice. Persisted so reopening the app never re-asks. */
    suspend fun recordSelection(selected: Set<String>) {
        context.agentInstallStore.edit {
            it[Keys.USER_SELECTED] = true
            it[Keys.SKIPPED] = selected.isEmpty()
            val done = it[Keys.COMPLETED] ?: emptySet()
            // Keep completed flags only for agents still selected, so a
            // deselected agent is forgotten rather than lingering as "done".
            it[Keys.COMPLETED] = done.intersect(selected)
        }
    }

    suspend fun markCompleted(id: String) {
        context.agentInstallStore.edit {
            val done = it[Keys.COMPLETED] ?: emptySet()
            it[Keys.COMPLETED] = done + id
        }
    }

    /**
     * Clear the record for one agent so it can be reinstalled.
     *
     * Used by the installer's retry path and by "repair" in settings.
     */
    suspend fun clearCompleted(id: String) {
        context.agentInstallStore.edit {
            val done = it[Keys.COMPLETED] ?: emptySet()
            it[Keys.COMPLETED] = done - id
        }
    }
}