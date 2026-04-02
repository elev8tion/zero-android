package com.zeroclaw.zero.data

import org.junit.Assert.*
import org.junit.Test

class WorkflowTemplatesTest {

    @Test
    fun `all 3 templates are present`() {
        assertEquals(3, WorkflowTemplates.list().size)
    }

    @Test
    fun `each template has a unique ID`() {
        val ids = WorkflowTemplates.list().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `each template has at least one step`() {
        WorkflowTemplates.list().forEach { template ->
            assertTrue(
                "${template.id} has no steps",
                template.steps.isNotEmpty()
            )
        }
    }

    @Test
    fun `get returns correct template by ID`() {
        val deployAndTest = WorkflowTemplates.get("deploy_and_test")
        assertNotNull(deployAndTest)
        assertEquals("Deploy & Test", deployAndTest!!.displayName)
        assertEquals(4, deployAndTest.steps.size)

        val bugTriage = WorkflowTemplates.get("bug_triage")
        assertNotNull(bugTriage)
        assertEquals("Bug Triage", bugTriage!!.displayName)
        assertEquals(5, bugTriage.steps.size)

        val newTool = WorkflowTemplates.get("new_tool")
        assertNotNull(newTool)
        assertEquals("New Tool", newTool!!.displayName)
        assertEquals(5, newTool.steps.size)
    }

    @Test
    fun `get returns null for unknown ID`() {
        assertNull(WorkflowTemplates.get("nonexistent"))
        assertNull(WorkflowTemplates.get(""))
    }

    @Test
    fun `step titles are non-empty`() {
        WorkflowTemplates.list().forEach { template ->
            template.steps.forEach { step ->
                assertTrue(
                    "${template.id} has step with empty title",
                    step.title.isNotBlank()
                )
            }
        }
    }

    @Test
    fun `step priorities are valid values`() {
        val validPriorities = setOf("low", "medium", "high", "urgent")
        WorkflowTemplates.list().forEach { template ->
            template.steps.forEach { step ->
                assertTrue(
                    "${template.id} step '${step.title}' has invalid priority '${step.priority}'",
                    step.priority in validPriorities
                )
            }
        }
    }

    @Test
    fun `step descriptions are non-empty`() {
        WorkflowTemplates.list().forEach { template ->
            template.steps.forEach { step ->
                assertTrue(
                    "${template.id} step '${step.title}' has empty description",
                    step.description.isNotBlank()
                )
            }
        }
    }

    @Test
    fun `template display names are non-empty`() {
        WorkflowTemplates.list().forEach { template ->
            assertTrue(
                "${template.id} has empty displayName",
                template.displayName.isNotBlank()
            )
        }
    }

    @Test
    fun `template descriptions are non-empty`() {
        WorkflowTemplates.list().forEach { template ->
            assertTrue(
                "${template.id} has empty description",
                template.description.isNotBlank()
            )
        }
    }
}
