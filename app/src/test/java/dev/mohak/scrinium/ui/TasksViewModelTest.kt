package dev.mohak.scrinium.ui

import dev.mohak.scrinium.data.remote.CalendarResponse
import dev.mohak.scrinium.data.remote.NewTaskRequest
import dev.mohak.scrinium.data.remote.TaskDto
import dev.mohak.scrinium.data.remote.TasksApi
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Every API call parks until the test answers it, so replies can arrive in
// any order. Alarms are modelled as the set of task ids Reminders.sync would
// schedule.
@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    private val main = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(main)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Call<T>(val arg: Any?) {
        val reply = CompletableDeferred<T>()
        fun ok(value: T) = reply.complete(value)
        fun fail() = reply.completeExceptionally(IOException("offline"))
    }

    private class FakeApi : TasksApi {
        val fetches = mutableListOf<Call<List<TaskDto>>>()
        val noteFetches = mutableListOf<Call<List<TaskDto>>>()
        val creates = mutableListOf<Call<TaskDto>>()
        val patches = mutableListOf<Call<TaskDto>>()
        val deletes = mutableListOf<Call<Unit>>()
        val linkFetches = mutableListOf<Call<List<String>>>()
        val linkAdds = mutableListOf<Call<List<String>>>()

        private suspend fun <T> park(list: MutableList<Call<T>>, arg: Any?): T =
            Call<T>(arg).also { list += it }.reply.await()

        override suspend fun fetchTasks() = park(fetches, null)
        override suspend fun fetchTasksForNote(path: String) = park(noteFetches, path)
        override suspend fun createTask(body: NewTaskRequest) = park(creates, body)
        override suspend fun patchTask(id: String, patch: JsonObject) = park(patches, id to patch)
        override suspend fun deleteTask(id: String) = park(deletes, id)
        override suspend fun fetchTaskLinks(id: String) = park(linkFetches, id)
        override suspend fun addTaskLink(id: String, notePath: String) = park(linkAdds, id to notePath)
        override suspend fun removeTaskLink(id: String, notePath: String): List<String> = error("unused")
        override suspend fun fetchCalendarEvents(from: Long, to: Long) = CalendarResponse()
    }

    private val api = FakeApi()
    private var alarms = setOf<String>()
    private var alarmSyncs = 0
    private val vm = TasksViewModel(api) { tasks ->
        alarmSyncs++
        alarms = tasks.filter { !it.isDone && it.remindAt != null }.map { it.id }.toSet()
    }

    private fun task(id: String, title: String = id, remindAt: Long? = null, status: String = "todo", parentId: String? = null) =
        TaskDto(id = id, title = title, status = status, remindAt = remindAt, parentId = parentId)

    private fun row(id: String) = vm.tasks.value.single { it.id == id }

    private fun TestScope.loaded(vararg tasks: TaskDto) {
        vm.refresh()
        runCurrent()
        api.fetches.last().ok(tasks.toList())
        runCurrent()
    }

    @Test
    fun emptyFirstLoadCancelsStaleAlarms() = runTest(main) {
        alarms = setOf("removed-on-web")
        loaded()
        assertEquals(emptySet(), alarms)
        assertEquals(1, alarmSyncs)
    }

    @Test
    fun olderTitleReplyKeepsNewerReminder() = runTest(main) {
        loaded(task("a"))
        vm.setTitle("a", "Renamed")
        vm.setReminder("a", 9_000)
        runCurrent()
        // One PATCH per task in flight, so the server answers in order.
        assertEquals(1, api.patches.size)
        api.patches[0].ok(task("a", "Renamed"))
        runCurrent()
        assertEquals(9_000, row("a").remindAt)
        assertEquals(setOf("a"), alarms)
        assertEquals(2, api.patches.size)
        api.patches[1].ok(task("a", "Renamed", remindAt = 9_000))
        runCurrent()
        assertEquals(task("a", "Renamed", remindAt = 9_000), row("a"))
        assertEquals(setOf("a"), alarms)
    }

    @Test
    fun repeatedStatusChangesEndOnTheLastOne() = runTest(main) {
        loaded(task("a", remindAt = 9_000))
        vm.setStatus("a", TaskStatus.Done)
        vm.setStatus("a", TaskStatus.Todo)
        runCurrent()
        api.patches[0].ok(task("a", remindAt = 9_000, status = "done"))
        runCurrent()
        assertEquals("todo", row("a").status)
        assertEquals(setOf("a"), alarms)
        api.patches[1].ok(task("a", remindAt = 9_000, status = "todo"))
        runCurrent()
        assertEquals("todo", row("a").status)
        assertEquals(setOf("a"), alarms)
    }

    @Test
    fun failedPatchKeepsAnUnrelatedCreate() = runTest(main) {
        loaded(task("a"))
        vm.setTitle("a", "Renamed")
        vm.create("c")
        runCurrent()
        api.creates.single().ok(task("c"))
        runCurrent()
        api.patches.single().fail()
        runCurrent()
        assertEquals(listOf(task("a"), task("c")), vm.tasks.value)
        assertNotNull(vm.error.value)
    }

    @Test
    fun failedPatchUndoesOnlyItsOwnField() = runTest(main) {
        loaded(task("a"))
        vm.setTitle("a", "Renamed")
        vm.setReminder("a", 9_000)
        runCurrent()
        api.patches[0].fail()
        runCurrent()
        assertEquals(task("a", remindAt = 9_000), row("a"))
        api.patches[1].ok(task("a", remindAt = 9_000))
        runCurrent()
        assertEquals(task("a", remindAt = 9_000), row("a"))
        assertEquals(setOf("a"), alarms)
    }

    @Test
    fun failedDeleteRestoresOnlyItsRows() = runTest(main) {
        loaded(task("a", remindAt = 9_000), task("a1", parentId = "a"), task("b"))
        vm.delete("a")
        vm.setTitle("b", "Renamed")
        runCurrent()
        assertEquals(emptySet(), alarms)
        api.patches.single().ok(task("b", "Renamed"))
        runCurrent()
        api.deletes.single().fail()
        runCurrent()
        assertEquals(setOf("a", "a1", "b"), vm.tasks.value.map { it.id }.toSet())
        assertEquals("Renamed", row("b").title)
        assertEquals(setOf("a"), alarms)
    }

    @Test
    fun lateLinksForAnEarlierTaskAreDropped() = runTest(main) {
        loaded(task("a"), task("b"))
        vm.openTask("a")
        runCurrent()
        vm.openTask("b")
        runCurrent()
        api.linkFetches[1].ok(listOf("b.md"))
        api.linkFetches[0].ok(listOf("a.md"))
        runCurrent()
        assertEquals("b", vm.openTaskId.value)
        assertEquals(listOf("b.md"), vm.openLinks.value)
    }

    @Test
    fun lateNoteTasksForAnEarlierNoteAreDropped() = runTest(main) {
        vm.loadNoteTasks("a.md")
        runCurrent()
        vm.loadNoteTasks("b.md")
        runCurrent()
        api.noteFetches[1].ok(listOf(task("b")))
        api.noteFetches[0].ok(listOf(task("a")))
        runCurrent()
        assertEquals(listOf("b"), vm.noteTasks.value.map { it.id })
    }

    @Test
    fun taskCreatedForANoteSkipsThePanelOfAnotherNote() = runTest(main) {
        vm.loadNoteTasks("a.md")
        runCurrent()
        api.noteFetches[0].ok(emptyList())
        vm.create("t", linkPath = "a.md")
        runCurrent()
        vm.loadNoteTasks("b.md")
        runCurrent()
        api.noteFetches[1].ok(emptyList())
        api.creates.single().ok(task("t"))
        runCurrent()
        api.linkAdds.single().ok(listOf("a.md"))
        runCurrent()
        assertEquals(emptyList(), vm.noteTasks.value)
    }

    @Test
    fun leavingATaskSavesItsDraftsOnce() = runTest(main) {
        loaded(task("a"))
        vm.openTask("a")
        vm.draft("a", TasksViewModel.TextField.Title, "Typed")
        vm.draft("a", TasksViewModel.TextField.Detail, "Notes")
        vm.draft("a", TasksViewModel.TextField.WaitingOn, "Sam")
        advanceTimeBy(100)
        vm.closeTask()
        runCurrent()
        assertEquals("Typed", row("a").title)
        assertEquals("Notes", row("a").detail)
        assertEquals("Sam", row("a").waitingOn)
        advanceTimeBy(5_000)
        runCurrent()
        // Sent one after another (one PATCH per task in flight).
        api.patches[0].ok(task("a", "Typed"))
        runCurrent()
        api.patches[1].ok(task("a", "Typed").copy(detail = "Notes"))
        runCurrent()
        api.patches[2].ok(task("a", "Typed").copy(detail = "Notes", waitingOn = "Sam"))
        advanceUntilIdle()
        assertEquals(3, api.patches.size)
        assertEquals(
            listOf("title" to "Typed", "detail" to "Notes", "waiting_on" to "Sam"),
            api.patches.map { c ->
                @Suppress("UNCHECKED_CAST")
                val body = (c.arg as Pair<String, JsonObject>).second
                body.entries.single().let { it.key to (it.value as JsonPrimitive).content }
            }
        )
    }

    @Test
    fun draftSavedByTheDebounceIsNotSavedAgainOnLeave() = runTest(main) {
        loaded(task("a"))
        vm.draft("a", TasksViewModel.TextField.Title, "Typed")
        advanceTimeBy(700)
        runCurrent()
        assertEquals(1, api.patches.size)
        vm.closeTask()
        runCurrent()
        assertEquals(1, api.patches.size)
    }

    @Test
    fun failedCreateKeepsTheFormText() = runTest(main) {
        var created = false
        vm.create("t", linkPath = "a.md", onCreated = { created = true })
        runCurrent()
        api.creates.single().fail()
        runCurrent()
        assertFalse(created)
        assertTrue(vm.error.value!!.startsWith("Couldn't add task"))
    }
}
