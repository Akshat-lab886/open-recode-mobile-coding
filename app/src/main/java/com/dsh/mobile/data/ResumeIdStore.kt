package com.dsh.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.resumeIdDataStore by preferencesDataStore(name = "resume_ids")

/**
 * Remembers the last conversation/session id each agent reported, so a mode
 * change or a crash-respawn can resume that conversation instead of starting a
 * fresh one.
 *
 * This is the persistence half of the Mobile-Harness `bindResume` pattern; the
 * capture half is [com.dsh.mobile.runtime.ResumeIdParser] and the argv half is
 * [AgentCatalog.ControlAdapter.resumeIdArgFor].
 *
 * Keyed per agent (`id_<agentId>`) because each backend has its own id space and
 * its own resume flag. Loopback-only, on-device, never synced — an id is not a
 * secret but it is conversation-scoped state, and nothing here leaves the device.
 */
class ResumeIdStore(private val context: Context) {

    /** The stored id for [agentId], or null when none was ever captured. */
    suspend fun get(agentId: String): String? =
        context.resumeIdDataStore.data.first()[key(agentId)]

    /** Overwrites the stored id. Callers dedupe; this always writes. */
    suspend fun set(agentId: String, resumeId: String) {
        if (resumeId.isBlank()) return
        context.resumeIdDataStore.edit { it[key(agentId)] = resumeId }
    }

    /**
     * Forgets [agentId]'s id.
     *
     * Called when the user starts a NEW conversation: keeping the old id would
     * silently resume the previous thread on the next boot, which is the
     * opposite of what "new chat" means.
     */
    suspend fun clear(agentId: String) {
        context.resumeIdDataStore.edit { it.remove(key(agentId)) }
    }

    private fun key(agentId: String) = stringPreferencesKey("id_$agentId")
}
