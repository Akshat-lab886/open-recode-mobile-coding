package com.dsh.mobile.runtime

/**
 * One agent process the service is running.
 *
 * @param id stable handle used by the UI to switch sessions.
 * @param agentId catalogue id, e.g. `dsh`, `claude`.
 * @param port loopback port this agent answers on.
 */
data class RunningSession(
    val id: String,
    val agentId: String,
    val port: Int,
    val workspace: String,
)

/** Why a session could not start. */
sealed interface SessionRejection {
    /** The device cannot afford another agent right now. */
    data class AtCapacity(val running: Int, val allowed: Int) : SessionRejection

    /** The requested port is already taken by another session in this app. */
    data class PortTaken(val port: Int) : SessionRejection

    /** A session with this id already exists. */
    data class DuplicateId(val id: String) : SessionRejection

    /** No port could be allocated from the candidate range. */
    data class NoFreePort(val from: Int, val to: Int) : SessionRejection
}

/**
 * Tracks which agents are running and refuses to over-commit the device.
 *
 * ## Why this is separate from the service
 *
 * The capacity decision is the part that has to be *correct*, and correctness
 * here is arithmetic: how many agents fit in the memory and storage budget, and
 * which ports are free. Keeping it in a plain class means it can be tested on
 * the JVM against the awkward cases (a flagship phone, a nearly full disk, a
 * port collision) that cannot be produced reliably on a real handset.
 *
 * The service owns the processes; this owns the *bookkeeping and the refusal*.
 *
 * ## Deliberately conservative
 *
 * [DeviceCapacity] already caps concurrency using the Android 17 foreground
 * service ceiling, which cannot be queried at runtime and is enforced by cgroup.
 * Exceeding it does not degrade the app, it kills a process - and with it the
 * user's in-flight work. So the registry refuses rather than retries.
 */
class AgentSessionRegistry(
    private val portRangeStart: Int = DEFAULT_PORT_START,
    private val portRangeEnd: Int = DEFAULT_PORT_END,
) {
    private val sessions = LinkedHashMap<String, RunningSession>()

    /** Currently running sessions, in start order. */
    val running: List<RunningSession> get() = sessions.values.toList()

    val count: Int get() = sessions.size

    /** True when [agentId] already has a live session. */
    fun hasAgent(agentId: String): Boolean =
        sessions.values.any { it.agentId == agentId }

    /**
     * Reserve a session slot.
     *
     * Returns the new session, or the reason it was refused. Allocation is
     * atomic: nothing is recorded unless every check passes, so a rejected call
     * cannot leave a half-registered session behind.
     */
    fun start(
        id: String,
        agentId: String,
        workspace: String,
        allowed: Int,
        isPortFree: (Int) -> Boolean = { true },
    ): Result<RunningSession> {
        if (sessions.containsKey(id)) {
            return Result.failure(SessionRefusal(SessionRejection.DuplicateId(id)))
        }
        if (sessions.size >= allowed) {
            return Result.failure(SessionRefusal(SessionRejection.AtCapacity(sessions.size, allowed)))
        }
        val used = sessions.values.map { it.port }.toSet()
        val port = (portRangeStart..portRangeEnd).firstOrNull { candidate ->
            candidate !in used && isPortFree(candidate)
        } ?: return Result.failure(SessionRefusal(SessionRejection.NoFreePort(portRangeStart, portRangeEnd)))

        val session = RunningSession(id, agentId, port, workspace)
        sessions[id] = session
        return Result.success(session)
    }

    /** Remove a session, returning it if it was running. */
    fun stop(id: String): RunningSession? = sessions.remove(id)

    /** Stop every session; used when the service shuts down. */
    fun stopAll(): List<RunningSession> {
        val all = running
        sessions.clear()
        return all
    }

    /** Runtime failure carrying the specific refusal. */
    class SessionRefusal(val reason: SessionRejection) :
        RuntimeException(reason.toString())

    companion object {
        const val DEFAULT_PORT_START = 3081
        const val DEFAULT_PORT_END = 3120
    }
}