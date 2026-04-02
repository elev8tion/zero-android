package com.zeroclaw.zero.data

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.zeroclaw.zero.ZeroApp
import com.zeroclaw.zero.agent.AgentLoop
import com.zeroclaw.zero.tools.ToolResult
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TaskCheckWorkerTest {

    private lateinit var context: Context
    private lateinit var mockClient: T0gglesClient
    private lateinit var mockPrefs: AppPrefs
    private lateinit var mockAgentLoop: AgentLoop

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mockClient = mockk(relaxed = true)
        mockPrefs = mockk(relaxed = true)
        mockAgentLoop = mockk(relaxed = true)

        every { mockPrefs.t0gglesBoardId } returns "test-board"
        every { mockPrefs.scheduledAutoExecute } returns false
        every { mockAgentLoop.isRunning } returns false

        // Mock ZeroApp singleton
        mockkObject(ZeroApp)
        val mockApp = mockk<ZeroApp>(relaxed = true)
        every { ZeroApp.instance } returns mockApp
        every { mockApp.prefs } returns mockPrefs
        every { mockApp.t0gglesClient } returns mockClient
        every { mockApp.scheduledAgentLoop } returns mockAgentLoop
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── suggest-next-action success ─────────────────────────────────────────

    @Test
    fun `suggest-next-action success posts notification`() = runBlocking {
        every { mockClient.callTool("suggest-next-action", any()) } returns
            ToolResult(true, data = """{"suggestion":"Fix the login bug","taskId":"t1","taskTitle":"Login fix"}""")

        val worker = TestListenableWorkerBuilder<TaskCheckWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifications = nm.activeNotifications
        assertTrue(notifications.isNotEmpty())
    }

    // ── suggest-next-action failure falls back ──────────────────────────────

    @Test
    fun `suggest-next-action failure falls back to list-overdue-tasks`() = runBlocking {
        every { mockClient.callTool("suggest-next-action", any()) } returns
            ToolResult(false, error = "Not available")
        every { mockClient.callTool("list-overdue-tasks", any()) } returns
            ToolResult(true, data = """{"tasks":[{"id":"t2","title":"Overdue task"}]}""")

        val worker = TestListenableWorkerBuilder<TaskCheckWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        verify { mockClient.callTool("list-overdue-tasks", any()) }
    }

    // ── Both calls fail → retry ─────────────────────────────────────────────

    @Test
    fun `both calls fail returns retry`() = runBlocking {
        every { mockClient.callTool("suggest-next-action", any()) } returns
            ToolResult(false, error = "Network error")
        every { mockClient.callTool("list-overdue-tasks", any()) } returns
            ToolResult(false, error = "Network error")

        val worker = TestListenableWorkerBuilder<TaskCheckWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    // ── Zero tasks → no notification, success ───────────────────────────────

    @Test
    fun `zero tasks found returns success without notification`() = runBlocking {
        every { mockClient.callTool("suggest-next-action", any()) } returns
            ToolResult(true, data = """{"suggestion":""}""")

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancelAll()

        val worker = TestListenableWorkerBuilder<TaskCheckWorker>(context).build()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, nm.activeNotifications.size)
    }

    // ── Parsing overdue tasks ───────────────────────────────────────────────

    @Test
    fun `parseOverdueTasks extracts fields correctly`() {
        val worker = TestListenableWorkerBuilder<TaskCheckWorker>(context).build()
        val json = """{"tasks":[{"id":"abc","title":"Deploy v2"},{"id":"def","title":"Write docs"}]}"""

        val result = worker.parseOverdueTasks(json)

        assertEquals(2, result.tasksFound)
        assertEquals("Deploy v2", result.topTaskTitle)
        assertEquals("abc", result.topTaskId)
        assertTrue(result.suggestion!!.contains("2 overdue"))
    }

    @Test
    fun `parseSuggestion handles plain text`() {
        val worker = TestListenableWorkerBuilder<TaskCheckWorker>(context).build()
        val text = "You should work on the login bug next"

        val result = worker.parseSuggestion(text)

        assertEquals(1, result.tasksFound)
        assertEquals(text, result.suggestion)
        assertNull(result.topTaskId)
    }

    // ── Auto-execute behavior ───────────────────────────────────────────────

    @Test
    fun `auto-execute skipped when scheduledAutoExecute is false`() = runBlocking {
        every { mockPrefs.scheduledAutoExecute } returns false
        every { mockClient.callTool("suggest-next-action", any()) } returns
            ToolResult(true, data = """{"suggestion":"Do something","taskTitle":"Task"}""")

        val worker = TestListenableWorkerBuilder<TaskCheckWorker>(context).build()
        worker.doWork()

        verify(exactly = 0) { mockAgentLoop.process(any()) }
    }

    @Test
    fun `auto-execute skipped when scheduledAgentLoop isRunning`() = runBlocking {
        every { mockPrefs.scheduledAutoExecute } returns true
        every { mockAgentLoop.isRunning } returns true
        every { mockClient.callTool("suggest-next-action", any()) } returns
            ToolResult(true, data = """{"suggestion":"Do something","taskTitle":"Task"}""")

        val worker = TestListenableWorkerBuilder<TaskCheckWorker>(context).build()
        worker.doWork()

        verify(exactly = 0) { mockAgentLoop.process(any()) }
    }

    @Test
    fun `auto-execute triggers when enabled and loop not running`() = runBlocking {
        every { mockPrefs.scheduledAutoExecute } returns true
        every { mockAgentLoop.isRunning } returns false
        every { mockClient.callTool("suggest-next-action", any()) } returns
            ToolResult(true, data = """{"suggestion":"Fix login","taskTitle":"Login fix"}""")

        val worker = TestListenableWorkerBuilder<TaskCheckWorker>(context).build()
        worker.doWork()

        verify { mockAgentLoop.process(match { it.contains("Fix login") }) }
    }
}
