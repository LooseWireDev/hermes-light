package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.data.SessionPage
import dev.loosewire.hermeslight.data.SessionSummary
import dev.loosewire.hermeslight.data.TranscriptMessage
import dev.loosewire.hermeslight.net.ApiErrorKind
import dev.loosewire.hermeslight.net.ApiException
import dev.loosewire.hermeslight.ui.SessionActionMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionManagementTest {
    @Test
    fun `filter matches title and preview case-insensitively`() {
        val rows =
            listOf(
                session("a", "Alpha", "first Needle"),
                session("b", "Beta launch", null),
                session("c", "gamma", "PreView nEeDlE"),
            )
        assertEquals(listOf("a", "b", "c"), filterSessions(rows, "").map { it.id })
        assertEquals(listOf("a"), filterSessions(rows, "ALPHA").map { it.id })
        assertEquals(listOf("b"), filterSessions(rows, "BETA").map { it.id })
        assertEquals(listOf("a", "c"), filterSessions(rows, "needle").map { it.id })
        assertTrue(filterSessions(rows, "   ").isEmpty())
        assertTrue(filterSessions(rows, "no such session").isEmpty())
    }

    @Test
    fun `rename stays local until the server acknowledges and then retitles both rows`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fixture =
                Fixture(
                    AppState(
                        sessions = listOf(session("a", "Old"), session("b", "Bee")),
                        menuSessions = listOf(session("a", "Old")),
                        selectedSession = "b",
                    ),
                    renameCall = { _, _ -> gate.await() },
                )
            fixture.management.showActions("a")
            fixture.management.beginRename()
            assertEquals(SessionActionMode.Rename, fixture.state.historyAction?.mode)
            val renaming = async(start = CoroutineStart.UNDISPATCHED) { fixture.management.renameSession("  New title  ") }

            assertTrue(fixture.state.historyBusy)
            assertEquals(listOf("Old", "Bee"), fixture.state.sessions.map { it.title })
            assertEquals(listOf("Old"), fixture.state.menuSessions.map { it.title })

            gate.complete(Unit)
            renaming.await()

            assertEquals(listOf("a" to "New title"), fixture.renames)
            assertEquals(listOf("a", "b"), fixture.state.sessions.map { it.id })
            assertEquals(listOf("New title", "Bee"), fixture.state.sessions.map { it.title })
            assertEquals(listOf("New title"), fixture.state.menuSessions.map { it.title })
            assertEquals("b", fixture.state.selectedSession)
            assertNull(fixture.state.historyAction)
            assertNull(fixture.state.historyError)
            assertFalse(fixture.state.historyBusy)
            assertEquals(1, fixture.changes)
        }

    @Test
    fun `rename refuses blank input without network and keeps the editor open`() =
        runBlocking {
            val fixture =
                Fixture(
                    AppState(
                        sessions = listOf(session("a", "Old")),
                        menuSessions = listOf(session("a", "Old")),
                    ),
                )
            fixture.management.showActions("a")
            fixture.management.beginRename()

            fixture.management.renameSession(" \n ")

            assertTrue(fixture.renames.isEmpty())
            assertEquals("title cannot be empty", fixture.state.historyError)
            assertEquals(SessionActionMode.Rename, fixture.state.historyAction?.mode)
            assertEquals(listOf("Old"), fixture.state.sessions.map { it.title })
            assertFalse(fixture.state.historyBusy)
        }

    @Test
    fun `rename is refused outside the rename stage`() =
        runBlocking {
            val fixture = Fixture(AppState(sessions = listOf(session("a", "Old"))))
            fixture.management.showActions("a")

            fixture.management.renameSession("New")

            assertTrue(fixture.renames.isEmpty())
            assertEquals(SessionActionMode.Actions, fixture.state.historyAction?.mode)
            assertNull(fixture.state.historyError)
        }

    @Test
    fun `rename failure closes the sheet with a reason and preserves the old title`() =
        runBlocking {
            val fixture =
                Fixture(
                    AppState(
                        sessions = listOf(session("a", "Old")),
                        menuSessions = listOf(session("a", "Old")),
                    ),
                    renameCall = { _, _ -> error("dashboard refused") },
                )
            fixture.management.showActions("a")
            fixture.management.beginRename()

            fixture.management.renameSession("New")

            assertEquals(listOf("Old"), fixture.state.sessions.map { it.title })
            assertEquals(listOf("Old"), fixture.state.menuSessions.map { it.title })
            assertNull(fixture.state.historyAction)
            val historyError = fixture.state.historyError.orEmpty()
            assertTrue(historyError.startsWith("rename failed"))
            assertTrue(historyError.contains("dashboard refused"))
            assertFalse(fixture.state.historyBusy)
        }

    @Test
    fun `delete requires the confirm stage`() =
        runBlocking {
            val fixture = Fixture(AppState(sessions = listOf(session("a", "Old"), session("b", "Bee"))))
            fixture.management.showActions("a")

            fixture.management.confirmSessionDelete()
            assertTrue(fixture.deletes.isEmpty())

            fixture.management.beginDelete()
            assertEquals(SessionActionMode.ConfirmDelete, fixture.state.historyAction?.mode)
            fixture.management.dismiss()
            fixture.management.confirmSessionDelete()
            assertTrue(fixture.deletes.isEmpty())
            assertEquals(listOf("a", "b"), fixture.state.sessions.map { it.id })
            fixture.management.showActions("a")
            fixture.management.beginDelete()
            fixture.management.confirmSessionDelete()
            assertEquals(listOf("a"), fixture.deletes)
        }

    @Test
    fun `no deletion is shown while the server call is blocked and duplicates are suppressed`() =
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val fixture =
                Fixture(
                    AppState(
                        sessions = listOf(session("a", "Old"), session("b", "Bee")),
                        selectedSession = "b",
                    ),
                    deleteCall = { gate.await() },
                )
            fixture.management.showActions("a")
            fixture.management.beginDelete()
            val confirming = async(start = CoroutineStart.UNDISPATCHED) { fixture.management.confirmSessionDelete() }

            assertTrue(fixture.state.historyBusy)
            assertEquals(listOf("a", "b"), fixture.state.sessions.map { it.id })
            assertEquals(SessionActionMode.ConfirmDelete, fixture.state.historyAction?.mode)
            fixture.management.confirmSessionDelete()
            assertEquals(listOf("a"), fixture.deletes)

            gate.complete(Unit)
            confirming.await()

            assertEquals(listOf("b"), fixture.state.sessions.map { it.id })
            assertNull(fixture.state.historyAction)
            assertFalse(fixture.state.historyBusy)
            assertEquals(listOf(Triple("a", false, true)), fixture.deleted)
        }

    @Test
    fun `deleting the selected session clears its thread and related state without touching others`() =
        runBlocking {
            val fixture =
                Fixture(
                    AppState(
                        sessions = listOf(session("a", "Old"), session("b", "Bee")),
                        menuSessions = listOf(session("a", "Old"), session("b", "Bee")),
                        selectedSession = "a",
                        createdSession = "a",
                        messages = listOf(message(1, "hello")),
                        threadLoading = true,
                        threadHasOlder = true,
                        threadHasNewer = true,
                        positionRow = 9,
                        positionOffset = 40,
                        conversations = mapOf("a" to Conversation(), "b" to Conversation()),
                        desktopNotices = mapOf("a" to emptyList()),
                        liveSessions = setOf("a"),
                    ),
                )
            fixture.management.showActions("a")
            fixture.management.beginDelete()

            fixture.management.confirmSessionDelete()

            assertEquals(listOf("b"), fixture.state.sessions.map { it.id })
            assertEquals(listOf("b"), fixture.state.menuSessions.map { it.id })
            assertNull(fixture.state.selectedSession)
            assertNull(fixture.state.createdSession)
            assertTrue(fixture.state.messages.isEmpty())
            assertFalse(fixture.state.threadLoading)
            assertFalse(fixture.state.threadHasOlder)
            assertFalse(fixture.state.threadHasNewer)
            assertNull(fixture.state.positionRow)
            assertEquals(0, fixture.state.positionOffset)
            assertEquals(setOf("b"), fixture.state.conversations.keys)
            assertTrue(fixture.state.desktopNotices.isEmpty())
            assertTrue(fixture.state.liveSessions.isEmpty())
            assertTrue(fixture.state.sessionsRequired)
            assertEquals(listOf(Triple("a", true, true)), fixture.deleted)
        }

    @Test
    fun `deleting another row keeps the selected thread`() =
        runBlocking {
            val messages = listOf(message(2, "still here"))
            val fixture =
                Fixture(
                    AppState(
                        sessions = listOf(session("a", "Old"), session("b", "Bee")),
                        selectedSession = "b",
                        messages = messages,
                        conversations = mapOf("b" to Conversation()),
                    ),
                )
            fixture.management.showActions("a")
            fixture.management.beginDelete()

            fixture.management.confirmSessionDelete()

            assertEquals(listOf("b"), fixture.state.sessions.map { it.id })
            assertEquals("b", fixture.state.selectedSession)
            assertEquals(messages, fixture.state.messages)
            assertEquals(setOf("b"), fixture.state.conversations.keys)
            assertFalse(fixture.state.sessionsRequired)
        }

    @Test
    fun `delete failure keeps the row and reports the reason`() =
        runBlocking {
            val fixture =
                Fixture(
                    AppState(
                        sessions = listOf(session("a", "Old"), session("b", "Bee")),
                        selectedSession = "a",
                    ),
                    deleteCall = { error("dashboard unavailable") },
                )
            fixture.management.showActions("a")
            fixture.management.beginDelete()

            fixture.management.confirmSessionDelete()

            assertEquals(listOf("a", "b"), fixture.state.sessions.map { it.id })
            assertEquals("a", fixture.state.selectedSession)
            assertNull(fixture.state.historyAction)
            val historyError = fixture.state.historyError.orEmpty()
            assertTrue(historyError.startsWith("delete failed"))
            assertTrue(historyError.contains("dashboard unavailable"))
            assertFalse(fixture.state.historyBusy)
            assertTrue(fixture.deleted.isEmpty())
            assertEquals(0, fixture.changes)
        }

    @Test
    fun `endpoint search failure falls back to local title and preview filtering`() =
        runBlocking {
            val fixture =
                Fixture(
                    AppState(
                        sessions =
                            listOf(
                                session("a", "Alpha", "first Needle"),
                                session("b", "Beta launch", null),
                            ),
                    ),
                    searchCall = { error("search unsupported") },
                )

            fixture.management.search("ALPHA")
            assertTrue(fixture.state.sessionsSearchLoading)
            assertEquals(listOf("a"), fixture.state.visibleSessions().map { it.id })
            fixture.management.loadSearch("ALPHA")

            assertNull(fixture.state.sessionsSearch)
            assertFalse(fixture.state.sessionsSearchLoading)
            assertEquals(listOf("a"), fixture.state.visibleSessions().map { it.id })
            assertEquals(2, fixture.state.sessionSearchTotal())

            fixture.management.search("needle")
            fixture.management.loadSearch("needle")

            assertNull(fixture.state.sessionsSearch)
            assertFalse(fixture.state.sessionsSearchLoading)
            assertEquals(listOf("a"), fixture.state.visibleSessions().map { it.id })
            assertEquals(listOf("ALPHA", "needle"), fixture.searches)
        }

    @Test
    fun `server search starts only for two or more characters and clears the old page`() =
        runBlocking {
            val page = searchPage(listOf(session("remote", "Remote only")), total = 1)
            val fixture =
                Fixture(
                    AppState(sessions = listOf(session("a", "Alpha"))),
                    searchCall = { page },
                    current = { false },
                )

            fixture.management.search("a")
            assertFalse(fixture.state.sessionsSearchLoading)
            assertNull(fixture.state.sessionsSearch)
            fixture.management.loadSearch("a")
            assertTrue(fixture.searches.isEmpty())
            assertNull(fixture.state.sessionsSearch)

            fixture.management.search("Al")
            assertTrue(fixture.state.sessionsSearchLoading)
            fixture.management.loadSearch("Al")
            assertEquals(listOf("remote"), fixture.state.visibleSessions().map { it.id })
            assertEquals(1, fixture.state.sessionSearchTotal())
            assertFalse(fixture.state.sessionsSearchLoading)
            assertEquals(listOf("Al"), fixture.searches)

            fixture.management.search("Alp")
            assertNull(fixture.state.sessionsSearch)
            assertTrue(fixture.state.sessionsSearchLoading)
            assertEquals(listOf("a"), fixture.state.visibleSessions().map { it.id })

            fixture.management.search("")
            assertNull(fixture.state.sessionsSearch)
            assertFalse(fixture.state.sessionsSearchLoading)
            assertEquals(listOf("a"), fixture.state.visibleSessions().map { it.id })
            assertEquals(1, fixture.state.sessionSearchTotal())
        }

    @Test
    fun `server-only search rows support actions rename delete and the thread title`() =
        runBlocking {
            val remote = session("remote", "Remote only", "needle")
            val deleteGate = CompletableDeferred<Unit>()
            val fixture =
                Fixture(
                    AppState(sessions = listOf(session("local", "Local"))),
                    deleteCall = { deleteGate.await() },
                    searchCall = { searchPage(listOf(remote), total = 7) },
                )

            fixture.management.search("needle")
            fixture.management.loadSearch("needle")
            assertEquals(listOf("remote"), fixture.state.visibleSessions().map { it.id })
            assertEquals(7, fixture.state.sessionSearchTotal())
            assertEquals(
                "Remote only",
                fixture.state
                    .copy(selectedSession = "remote")
                    .threadUi()
                    .title,
            )

            fixture.management.showActions("remote")
            assertEquals("Remote only", fixture.state.historyAction?.title)
            fixture.management.beginRename()
            fixture.management.renameSession("  Renamed remote  ")
            assertEquals(
                listOf("Renamed remote"),
                fixture.state.sessionsSearch
                    ?.sessions
                    ?.map { it.title },
            )
            assertEquals(7, fixture.state.sessionSearchTotal())
            assertEquals(
                "Renamed remote",
                fixture.state
                    .copy(selectedSession = "remote")
                    .threadUi()
                    .title,
            )

            fixture.management.showActions("remote")
            fixture.management.beginDelete()
            val confirming = async(start = CoroutineStart.UNDISPATCHED) { fixture.management.confirmSessionDelete() }
            assertTrue(fixture.state.historyBusy)
            assertEquals(listOf("Renamed remote"), fixture.state.visibleSessions().map { it.title })
            assertEquals(7, fixture.state.sessionSearchTotal())

            deleteGate.complete(Unit)
            confirming.await()

            assertTrue(fixture.state.visibleSessions().isEmpty())
            assertEquals(6, fixture.state.sessionSearchTotal())
            assertEquals(listOf(Triple("remote", false, false)), fixture.deleted)
            assertFalse(fixture.state.historyBusy)
        }

    @Test
    fun `out-of-order search responses keep only the newest query`() =
        runBlocking {
            val first = CompletableDeferred<SessionPage>()
            val second = CompletableDeferred<SessionPage>()
            val fixture =
                Fixture(
                    AppState(sessions = listOf(session("local", "Local"))),
                    searchCall = { query -> if (query == "first") first.await() else second.await() },
                )

            fixture.management.search("first")
            val firstLoad = async(start = CoroutineStart.UNDISPATCHED) { fixture.management.loadSearch("first") }
            fixture.management.search("second")
            val secondLoad = async(start = CoroutineStart.UNDISPATCHED) { fixture.management.loadSearch("second") }

            second.complete(searchPage(listOf(session("s2", "Second")), total = 1))
            secondLoad.await()
            assertEquals(listOf("s2"), fixture.state.visibleSessions().map { it.id })

            first.complete(searchPage(listOf(session("s1", "First")), total = 1))
            firstLoad.await()

            assertEquals(listOf("s2"), fixture.state.visibleSessions().map { it.id })
            assertEquals("second", fixture.state.sessionsQuery)
            assertEquals(1, fixture.state.sessionSearchTotal())
            assertFalse(fixture.state.sessionsSearchLoading)
        }

    @Test
    fun `invalidate drops a pending search and stops its loading state`() =
        runBlocking {
            val gate = CompletableDeferred<SessionPage>()
            val fixture = Fixture(AppState(), searchCall = { gate.await() })

            fixture.management.search("needle")
            val loading = async(start = CoroutineStart.UNDISPATCHED) { fixture.management.loadSearch("needle") }
            assertTrue(fixture.state.sessionsSearchLoading)

            fixture.management.invalidate()
            assertFalse(fixture.state.sessionsSearchLoading)

            gate.complete(searchPage(listOf(session("x", "Stale")), total = 1))
            loading.await()

            assertNull(fixture.state.sessionsSearch)
            assertFalse(fixture.state.sessionsSearchLoading)
        }

    @Test
    fun `cancelling a load propagates cancellation and stops its loading state`() =
        runBlocking {
            val gate = CompletableDeferred<SessionPage>()
            val fixture = Fixture(AppState(), searchCall = { gate.await() })

            fixture.management.search("needle")
            val loading = async(start = CoroutineStart.UNDISPATCHED) { fixture.management.loadSearch("needle") }
            assertTrue(fixture.state.sessionsSearchLoading)

            loading.cancel()
            loading.join()

            assertTrue(loading.isCancelled)
            assertNull(fixture.state.sessionsSearch)
            assertFalse(fixture.state.sessionsSearchLoading)
        }

    @Test
    fun `a stale search cannot resurrect an acknowledged deletion`() =
        runBlocking {
            val gate = CompletableDeferred<SessionPage>()
            val fixture =
                Fixture(
                    AppState(
                        sessionsQuery = "needle",
                        sessions = listOf(session("a", "Alpha"), session("b", "Bee")),
                        sessionsSearch = searchPage(listOf(session("a", "Alpha"), session("b", "Bee")), total = 2),
                    ),
                    searchCall = { gate.await() },
                )

            val refresh = async(start = CoroutineStart.UNDISPATCHED) { fixture.management.loadSearch("needle") }
            assertTrue(fixture.state.sessionsSearchLoading)

            fixture.management.showActions("a")
            fixture.management.beginDelete()
            fixture.management.confirmSessionDelete()

            assertEquals(listOf("b"), fixture.state.visibleSessions().map { it.id })
            assertEquals(1, fixture.state.sessionSearchTotal())
            assertFalse(fixture.state.sessionsSearchLoading)

            gate.complete(searchPage(listOf(session("a", "Alpha"), session("b", "Bee")), total = 2))
            refresh.await()

            assertEquals(listOf("b"), fixture.state.visibleSessions().map { it.id })
            assertEquals(listOf("b"), fixture.state.sessions.map { it.id })
            assertEquals(1, fixture.state.sessionSearchTotal())
            assertFalse(fixture.state.sessionsSearchLoading)
        }

    @Test
    fun `rename conflict reaches the error line and keeps the original rows`() =
        runBlocking {
            val fixture =
                Fixture(
                    AppState(
                        sessions = listOf(session("a", "Old")),
                        sessionsQuery = "old",
                        sessionsSearch = searchPage(listOf(session("a", "Old")), total = 1),
                        menuSessions = listOf(session("a", "Old")),
                    ),
                    renameCall = { _, _ ->
                        throw ApiException("changed elsewhere — retry", ApiErrorKind.HTTP, 409)
                    },
                )

            fixture.management.showActions("a")
            fixture.management.beginRename()
            fixture.management.renameSession("New")

            assertEquals(listOf("Old"), fixture.state.sessions.map { it.title })
            assertEquals(
                listOf("Old"),
                fixture.state.sessionsSearch
                    ?.sessions
                    ?.map { it.title },
            )
            assertEquals(listOf("Old"), fixture.state.menuSessions.map { it.title })
            val historyError = fixture.state.historyError.orEmpty()
            assertTrue(historyError.startsWith("rename failed"))
            assertTrue(historyError.contains("changed elsewhere — retry"))
            assertNull(fixture.state.historyAction)
            assertFalse(fixture.state.historyBusy)
            assertEquals(1, fixture.state.sessionSearchTotal())
        }

    private class Fixture(
        var state: AppState,
        private val renameCall: suspend (String, String) -> Unit = { _, _ -> },
        private val deleteCall: suspend (String) -> Unit = { _ -> },
        private val searchCall: suspend (String) -> SessionPage = { error("search not configured") },
        private val current: () -> Boolean = { true },
    ) {
        val renames = mutableListOf<Pair<String, String>>()
        val deletes = mutableListOf<String>()
        val deleted = mutableListOf<Triple<String, Boolean, Boolean>>()
        val searches = mutableListOf<String>()
        var changes = 0
        val management =
            SessionManagement(
                state = { state },
                update = { transform -> state = transform(state) },
                rename = { id, title ->
                    renames += id to title
                    renameCall(id, title)
                },
                delete = { id ->
                    deletes += id
                    deleteCall(id)
                },
                searchRequest = { query ->
                    searches += query
                    searchCall(query)
                },
                onChanged = { changes++ },
                onDeleted = { id, selected, loaded -> deleted += Triple(id, selected, loaded) },
                current = current,
            )
    }
}

private fun searchPage(
    rows: List<SessionSummary>,
    total: Int = rows.size,
) = SessionPage(
    sessions = rows,
    total = total,
    limit = 25,
    offset = 0,
    hasMore = false,
)

private fun session(
    id: String,
    title: String,
    preview: String? = null,
) = SessionSummary(
    id = id,
    title = title,
    source = "api",
    profile = null,
    preview = preview,
    displayName = null,
    model = null,
    messageCount = 1,
    status = "idle",
    updatedAtEpochMs = null,
    unread = null,
    pinned = false,
    archived = false,
)

private fun message(
    rowId: Int,
    text: String,
) = TranscriptMessage(
    rowId = rowId,
    role = "assistant",
    text = text,
    reasoning = null,
    type = null,
    toolCallId = null,
    timestampEpochMs = null,
    displayKind = null,
)
