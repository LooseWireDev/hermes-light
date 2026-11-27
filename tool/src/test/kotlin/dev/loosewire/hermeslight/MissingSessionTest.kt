package dev.loosewire.hermeslight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MissingSessionTest {
    @Test
    fun `missing remembered session clears selection attention and error and requests History`() {
        val state =
            AppState(
                selectedSession = "gone",
                operationError = "Not found on the dashboard (HTTP 404)",
                positionRow = 12,
                positionOffset = 20,
                liveSessions = setOf("gone", "other"),
                desktopNotices = mapOf("gone" to listOf(DesktopNotice("r", false, "pending", "gone"))),
                conversations = mapOf("gone" to Conversation(composer = "draft")),
                threadLoading = true,
                threadHasOlder = true,
                threadHasNewer = true,
            )
        val cleared = state.clearMissingSession("gone")
        assertNull(cleared.selectedSession)
        assertNull(cleared.operationError)
        assertNull(cleared.positionRow)
        assertTrue(cleared.sessionsRequired)
        assertFalse(cleared.threadLoading)
        assertFalse(cleared.threadHasOlder)
        assertFalse(cleared.threadHasNewer)
        assertEquals(setOf("other"), cleared.liveSessions)
        assertTrue(cleared.desktopNotices.isEmpty())
        assertTrue(cleared.conversations.isEmpty())
    }

    @Test
    fun `late missing session result cannot clear a newer selection`() {
        val state = AppState(selectedSession = "new", positionRow = 5)
        assertEquals(state, state.clearMissingSession("old"))
    }
}
