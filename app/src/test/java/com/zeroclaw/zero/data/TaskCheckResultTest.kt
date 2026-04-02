package com.zeroclaw.zero.data

import org.junit.Assert.*
import org.junit.Test

class TaskCheckResultTest {

    private val sample = TaskCheckResult(
        tasksFound = 3,
        topTaskTitle = "Fix login bug",
        topTaskId = "task-123",
        suggestion = "You should fix the login bug first",
        rawResponse = """{"tasks":3}"""
    )

    @Test
    fun `data class equality`() {
        val copy = sample.copy()
        assertEquals(sample, copy)
        assertEquals(sample.hashCode(), copy.hashCode())
    }

    @Test
    fun `copy with modified fields`() {
        val modified = sample.copy(tasksFound = 5, topTaskTitle = "Deploy")
        assertEquals(5, modified.tasksFound)
        assertEquals("Deploy", modified.topTaskTitle)
        assertEquals(sample.topTaskId, modified.topTaskId)
        assertEquals(sample.suggestion, modified.suggestion)
        assertNotEquals(sample, modified)
    }

    @Test
    fun `null optional fields`() {
        val minimal = TaskCheckResult(
            tasksFound = 0,
            topTaskTitle = null,
            topTaskId = null,
            suggestion = null,
            rawResponse = ""
        )
        assertNull(minimal.topTaskTitle)
        assertNull(minimal.topTaskId)
        assertNull(minimal.suggestion)
        assertEquals(0, minimal.tasksFound)
    }

    @Test
    fun `toString contains field values`() {
        val str = sample.toString()
        assertTrue(str.contains("Fix login bug"))
        assertTrue(str.contains("task-123"))
        assertTrue(str.contains("3"))
    }

    @Test
    fun `destructuring works`() {
        val (found, title, id, suggestion, raw) = sample
        assertEquals(3, found)
        assertEquals("Fix login bug", title)
        assertEquals("task-123", id)
        assertEquals("You should fix the login bug first", suggestion)
        assertEquals("""{"tasks":3}""", raw)
    }
}
