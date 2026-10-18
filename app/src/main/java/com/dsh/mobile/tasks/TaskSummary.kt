package com.dsh.mobile.tasks

/** How urgently a task wants the user's attention. */
enum class TaskUrgency { BLOCKING, ATTENTION, INFO }

/** One row in the Tasks tab. */
data class TaskItem(
    val urgency: TaskUrgency,
    val title: String,
    val detail: String,
)

/**
 * The live inputs the Tasks tab reasons about, flattened to primitives.
 *
 * Primitives on purpose: the source types (ServiceStatus, WatchdogState,
 * BootstrapState) live in files that pull in Android, so passing them here would
 * make this untestable off-device. The composable adapts them.
 */
data class TaskInput(
    val engineRunning: Boolean = false,
    val engineError: String? = null,
    val engineUnhealthy: Boolean = false,
    val pendingApprovals: Int = 0,
    val bootstrapBusy: Boolean = false,
    val bootstrapLabel: String? = null,
)

/**
 * Turns engine state into an ordered task list.
 *
 * Order is the whole point: a pending tool approval means the agent is BLOCKED
 * right now and cannot proceed until the user answers, which outranks a
 * degraded-but-running engine, which outranks "everything is fine".
 */
object TaskSummary {

    fun build(input: TaskInput): List<TaskItem> {
        val out = ArrayList<TaskItem>()

        if (input.pendingApprovals > 0) {
            val n = input.pendingApprovals
            out += TaskItem(
                urgency = TaskUrgency.BLOCKING,
                title = if (n == 1) "1 approval waiting" else "$n approvals waiting",
                detail = "The agent is paused until you allow or deny.",
            )
        }

        input.engineError?.let { error ->
            out += TaskItem(
                urgency = TaskUrgency.BLOCKING,
                title = "Engine stopped",
                detail = error,
            )
        }

        if (!input.engineRunning && input.engineError == null && !input.bootstrapBusy) {
            out += TaskItem(
                urgency = TaskUrgency.ATTENTION,
                title = "Engine is not running",
                detail = "Start it from Chat or the Control Center.",
            )
        }

        if (input.engineUnhealthy) {
            out += TaskItem(
                urgency = TaskUrgency.ATTENTION,
                title = "Engine is unresponsive",
                detail = "It is up but not answering. Restart if it stays this way.",
            )
        }

        if (input.bootstrapBusy) {
            out += TaskItem(
                urgency = TaskUrgency.ATTENTION,
                title = "Setting up",
                detail = input.bootstrapLabel ?: "Preparing the runtime.",
            )
        }

        if (input.engineRunning && input.pendingApprovals == 0 && input.engineError == null && !input.engineUnhealthy) {
            out += TaskItem(
                urgency = TaskUrgency.INFO,
                title = "Ready",
                detail = "The engine is running and nothing is waiting on you.",
            )
        }

        return out.sortedBy { it.urgency.ordinal }
    }

    /** Whether anything in the list actually needs the user. */
    fun needsAttention(items: List<TaskItem>): Boolean =
        items.any { it.urgency != TaskUrgency.INFO }
}
