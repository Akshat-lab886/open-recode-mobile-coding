package com.dsh.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val Context.sessionStore by preferencesDataStore(name = "sessions")

/**
 * Session history, stored on-device only.
 *
 * ## Local-only, deliberately
 *
 * The requirement is that session logs never leave the device, so this writes
 * exclusively to app-private storage (`filesDir`). There is no network code
 * anywhere in this class, and none may be added: it is the whole point.
 *
 * ## Unbounded growth is the real risk
 *
 * Agent transcripts are large and this is a phone. Persisting every session
 * forever would fill the user's storage — the same storage the agents themselves
 * live on. So history is capped by BOTH count and bytes, and the oldest entries
 * are pruned first. [MAX_SESSIONS] and [MAX_TOTAL_BYTES] are enforced together:
 * whichever limit binds first wins.
 */
class SessionHistoryStore(private val context: Context) {

    /** One recorded run of one agent. */
    data class Session(
        val id: String,
        val agentId: String,
        val label: String,
        val startedAt: Long,
        val endedAt: Long,
        val workspace: String,
        val preview: String,
        val logFile: String,
    ) {
        val durationMs: Long get() = (endedAt - startedAt).coerceAtLeast(0)
    }

    private val sessions: Flow<List<Session>> =
        context.sessionStore.data.map { readSessions(it[SESSIONS]) }

    /** Newest first. */
    fun history(): Flow<List<Session>> = sessions

    suspend fun all(): List<Session> = sessions.first()

    suspend fun get(id: String): Session? = all().firstOrNull { it.id == id }

    /** Record a finished session, then prune to stay inside the limits. */
    suspend fun record(session: Session) {
        context.sessionStore.edit { prefs ->
            val current = decode(prefs[SESSIONS])
            // Same id replaces rather than duplicates, so a re-recorded session
            // does not consume two slots.
            val merged = (listOf(session) + current.filterNot { it.id == session.id })
                .sortedByDescending { it.startedAt }
            prefs[SESSIONS] = encode(merged)
        }
        pruneIfNeeded()
    }

    suspend fun delete(id: String) {
        context.sessionStore.edit { prefs ->
            prefs[SESSIONS] = encode(decode(prefs[SESSIONS]).filterNot { it.id == id })
        }
    }

    suspend fun clear() {
        context.sessionStore.edit { it.remove(SESSIONS) }
    }

    /**
     * Drop the oldest sessions until both limits are satisfied.
     *
     * Also unlinks each removed session's log file: pruning the index entry but
     * leaving the transcript on disk would defeat the storage cap entirely.
     */
    suspend fun pruneIfNeeded(): Int {
        val current = all()
        if (current.size <= MAX_SESSIONS && totalBytes(current) <= MAX_TOTAL_BYTES) return 0

        // current is newest-first, so keeping a prefix keeps the newest.
        val kept = selectToKeep(current, ::estimateBytes)
        val dropped = current.size - kept.size
        current.filterNot { it in kept }.forEach { s ->
            runCatching { File(s.logFile).delete() }
        }
        if (dropped > 0) {
            context.sessionStore.edit { it[SESSIONS] = encode(kept) }
        }
        return dropped
    }

    /**
     * Storage a session occupies: the index entry plus its transcript file.
     *
     * The transcript dominates and is the reason the cap exists, so it is
     * measured from disk rather than estimated from the preview.
     */
    private fun estimateBytes(s: Session): Long {
        val log = runCatching { File(s.logFile).length() }.getOrDefault(0L)
        return log + s.preview.length + s.label.length + s.workspace.length
    }

    private fun totalBytes(list: List<Session>): Long = list.sumOf { estimateBytes(it) }

    /** First line of the agent's output, for the list preview. */
    fun buildPreview(logFile: File, maxChars: Int = 160): String {
        if (!logFile.isFile) return ""
        return runCatching {
            logFile.useLines { lines ->
                lines.firstOrNull { it.isNotBlank() }?.take(maxChars) ?: ""
            }
        }.getOrDefault("")
    }

    // -- serialisation -------------------------------------------------------

    private fun encode(list: List<Session>): String {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("agentId", s.agentId)
                    put("label", s.label)
                    put("startedAt", s.startedAt)
                    put("endedAt", s.endedAt)
                    put("workspace", s.workspace)
                    put("preview", s.preview)
                    put("logFile", s.logFile)
                },
            )
        }
        return arr.toString()
    }

    private fun decode(raw: String?): List<Session> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Session(
                    id = o.optString("id"),
                    agentId = o.optString("agentId"),
                    label = o.optString("label"),
                    startedAt = o.optLong("startedAt"),
                    endedAt = o.optLong("endedAt"),
                    workspace = o.optString("workspace"),
                    preview = o.optString("preview"),
                    logFile = o.optString("logFile"),
                ).takeIf { it.id.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    private fun readSessions(raw: String?): List<Session> =
        decode(raw).sortedByDescending { it.startedAt }

    companion object {
        private val SESSIONS = stringPreferencesKey("session_index")

        /** Most recent sessions kept. */
        const val MAX_SESSIONS = 50

        /** Combined ceiling for transcripts. Conservative for a phone. */
        const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

        /**
         * Which sessions survive a prune, as a pure function.
         *
         * Split out so the cap rules can be tested on the JVM: DataStore needs a
         * real Context and the arithmetic is the part that must not regress.
         *
         * Ordering is enforced here rather than trusted from the caller: the
         * list is sorted newest-first inside the function, so no caller mistake
         * can cause the oldest sessions to be the ones kept. The user's recent
         * work must never be what gets evicted to satisfy a storage cap.
         */
        fun selectToKeep(
            current: List<Session>,
            sizeOf: (Session) -> Long,
            maxSessions: Int = MAX_SESSIONS,
            maxBytes: Long = MAX_TOTAL_BYTES,
        ): List<Session> {
            val kept = mutableListOf<Session>()
            var bytes = 0L
            for (s in current.sortedByDescending { it.startedAt }) {
                val size = sizeOf(s)
                if (kept.size >= maxSessions || bytes + size > maxBytes) break
                kept += s
                bytes += size
            }
            return kept
        }
    }
}