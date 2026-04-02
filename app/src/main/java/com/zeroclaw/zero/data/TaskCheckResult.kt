package com.zeroclaw.zero.data

/**
 * Result of a scheduled T0ggles task check.
 *
 * Carries the data needed to post a notification and optionally auto-execute.
 */
data class TaskCheckResult(
    val tasksFound: Int,
    val topTaskTitle: String?,
    val topTaskId: String?,
    val suggestion: String?,
    val rawResponse: String
)
