package com.dsh.mobile.tasks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSummaryTest {

    @Test
    fun `a healthy running engine reports Ready`() {
        val items = TaskSummary.build(TaskInput(engineRunning = true))
        assertEquals(1, items.size)
        assertEquals(TaskUrgency.INFO, items.first().urgency)
        assertFalse(TaskSummary.needsAttention(items))
    }

    @Test
    fun `a pending approval outranks everything and says the agent is blocked`() {
        val items = TaskSummary.build(
            TaskInput(engineRunning = true, pendingApprovals = 2, engineUnhealthy = true)
        )
        assertEquals(TaskUrgency.BLOCKING, items.first().urgency)
        assertTrue(items.first().title.contains("2"))
        assertTrue(TaskSummary.needsAttention(items))
    }

    @Test
    fun `a single approval is worded in the singular`() {
        val items = TaskSummary.build(TaskInput(engineRunning = true, pendingApprovals = 1))
        assertEquals("1 approval waiting", items.first().title)
    }

    @Test
    fun `an engine error blocks and carries its message`() {
        val items = TaskSummary.build(TaskInput(engineError = "gateway exited"))
        assertEquals(TaskUrgency.BLOCKING, items.first().urgency)
        assertEquals("gateway exited", items.first().detail)
    }

    @Test
    fun `a stopped engine is called out but not as blocking as an error`() {
        val items = TaskSummary.build(TaskInput(engineRunning = false))
        assertEquals(TaskUrgency.ATTENTION, items.first().urgency)
        assertTrue(items.none { it.title == "Ready" })
    }

    @Test
    fun `bootstrap in progress shows setup and is not also called stopped`() {
        val items = TaskSummary.build(
            TaskInput(engineRunning = false, bootstrapBusy = true, bootstrapLabel = "Downloading runtime")
        )
        assertTrue(items.any { it.title == "Setting up" })
        assertTrue(items.none { it.title.contains("not running") })
        assertTrue(items.first().detail.contains("Downloading runtime"))
    }

    @Test
    fun `a degraded running engine is attention but still running`() {
        val items = TaskSummary.build(TaskInput(engineRunning = true, engineUnhealthy = true))
        assertEquals(TaskUrgency.ATTENTION, items.first().urgency)
        assertTrue(items.none { it.title == "Ready" })
    }

    @Test
    fun `approval outranks an engine error in the ordering`() {
        val items = TaskSummary.build(
            TaskInput(pendingApprovals = 1, engineError = "boom")
        )
        assertEquals(TaskUrgency.BLOCKING, items.first().urgency)
        assertTrue(items.first().title.contains("approval"))
        assertEquals(2, items.count { it.urgency == TaskUrgency.BLOCKING })
    }

    @Test
    fun `needsAttention is false only when everything is informational`() {
        assertFalse(TaskSummary.needsAttention(TaskSummary.build(TaskInput(engineRunning = true))))
        assertTrue(TaskSummary.needsAttention(TaskSummary.build(TaskInput(pendingApprovals = 1, engineRunning = true))))
    }
}
