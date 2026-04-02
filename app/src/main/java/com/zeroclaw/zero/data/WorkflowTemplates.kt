package com.zeroclaw.zero.data

/**
 * A single step in a workflow template.
 */
data class WorkflowStep(
    val title: String,
    val description: String,
    val priority: String = "medium",
    val tags: List<String> = emptyList()
)

/**
 * A predefined workflow template — a named sequence of steps
 * that can be bulk-created as tasks with sequential dependencies.
 */
data class WorkflowTemplate(
    val id: String,
    val displayName: String,
    val description: String,
    val steps: List<WorkflowStep>
)

/**
 * Registry of built-in workflow templates.
 * Always available offline — no network required.
 */
object WorkflowTemplates {

    private val templates = listOf(
        WorkflowTemplate(
            id = "deploy_and_test",
            displayName = "Deploy & Test",
            description = "Build, install, run tests, and log results",
            steps = listOf(
                WorkflowStep(
                    "Build debug APK",
                    "Run ./gradlew assembleDebug and verify clean build",
                    "high"
                ),
                WorkflowStep(
                    "Install on device",
                    "adb install the APK on the target device",
                    "high"
                ),
                WorkflowStep(
                    "Run test suite",
                    "Execute manual or automated tests on device",
                    "high"
                ),
                WorkflowStep(
                    "Log results to T0ggles",
                    "Create a note with test results and any failures found",
                    "medium"
                )
            )
        ),
        WorkflowTemplate(
            id = "bug_triage",
            displayName = "Bug Triage",
            description = "Reproduce, identify root cause, fix, test, and close",
            steps = listOf(
                WorkflowStep(
                    "Reproduce the bug",
                    "Follow steps to reproduce the issue reliably",
                    "high"
                ),
                WorkflowStep(
                    "Identify root cause",
                    "Debug and trace the issue to its source",
                    "high"
                ),
                WorkflowStep(
                    "Implement fix",
                    "Write the code change to resolve the bug",
                    "high"
                ),
                WorkflowStep(
                    "Test the fix",
                    "Verify the fix works and no regressions introduced",
                    "high"
                ),
                WorkflowStep(
                    "Close and document",
                    "Mark the bug as resolved, add notes on what was changed and why",
                    "medium"
                )
            )
        ),
        WorkflowTemplate(
            id = "new_tool",
            displayName = "New Tool",
            description = "Implement a new tool: code, unit test, instrumented test, docs, register",
            steps = listOf(
                WorkflowStep(
                    "Implement tool class",
                    "Create the Tool implementation with definition and execute()",
                    "high"
                ),
                WorkflowStep(
                    "Add unit tests",
                    "Write JVM unit tests covering success, failure, and edge cases",
                    "high"
                ),
                WorkflowStep(
                    "Add instrumented tests",
                    "Write on-device tests verifying real behavior",
                    "medium"
                ),
                WorkflowStep(
                    "Update documentation",
                    "Update CLAUDE.md and any relevant docs with the new tool",
                    "medium"
                ),
                WorkflowStep(
                    "Register in ZeroApp",
                    "Add the tool to buildToolRegistry() and update smoke test count",
                    "high"
                )
            )
        )
    )

    fun list(): List<WorkflowTemplate> = templates

    fun get(id: String): WorkflowTemplate? = templates.find { it.id == id }
}
