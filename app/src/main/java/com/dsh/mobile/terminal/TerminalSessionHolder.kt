package com.dsh.mobile.terminal

import java.io.File

/**
 * Keeps one [ShellSession] alive for the app, per workspace.
 *
 * The session must outlive the Terminal tab's composition, or `cd` would be lost
 * every time the user visits another tab - which is the same bug as running each
 * command in a fresh process, just less obvious. A holder also means the
 * workspace is the key: switching workspaces starts a new session rather than
 * leaving a shell pointed at the old directory.
 *
 * Single-threaded by contract: callers use it from the UI's coroutine scope.
 */
object TerminalSessionHolder {

    private var session: ShellSession? = null
    private var key: String? = null

    /** The session for [workspace], replacing one started in another directory. */
    @Synchronized
    fun sessionFor(workspace: File): ShellSession {
        val wanted = workspace.absolutePath
        val existing = session
        // Reuse while the session is still OPEN. `isOpen` (not `isAlive`) is the
        // right test: a child that died mid-session is restarted by the session
        // on the next command and reports `restarted`, so discarding it here
        // would needlessly lose the working directory. A CLOSED session is
        // genuinely finished, though - close() is final, so it must be replaced.
        if (existing != null && key == wanted && existing.isOpen) return existing
        // A dead session or a different workspace: retire the old one first so
        // two `sh` children never run against the same tab.
        existing?.close()
        return ShellSession(workspace).also {
            session = it
            key = wanted
        }
    }

    /** Closes the current session; the next [sessionFor] starts a fresh one. */
    @Synchronized
    fun reset() {
        session?.close()
        session = null
        key = null
    }

    /** Whether a session is currently live. */
    @Synchronized
    fun isActive(): Boolean = session?.isOpen == true

    /** The workspace the live session is pointed at, if any. */
    @Synchronized
    fun currentWorkspace(): String? = if (session?.isOpen == true) key else null
}
