package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.GatewayEvent
import dev.loosewire.hermeslight.ui.StatusStripUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StatusStripTest {
    @Test
    fun `idle has no strip and points session selection at History`() {
        val state = AppState()
        assertNull(state.statusStripUi())
        assertEquals("Use History to choose a session.", state.threadUi().emptyText)
    }

    @Test
    fun `approval in another session takes precedence over running and selected sessions`() {
        val running = AppState(selectedSession = "selected", liveSessions = setOf("running"))
        val pending = reduceGatewayEvent(running, GatewayEvent.ApprovalPending("approval-session"))
        assertEquals(StatusStripUi("1 approval · 1 running", "approval-session"), pending.statusStripUi())
        val acknowledged = reduceGatewayEvent(pending, GatewayEvent.ApprovalReceived(null, "approval-session"))
        assertEquals(pending.statusStripUi(), acknowledged.statusStripUi())
        val cancelled = reduceGatewayEvent(acknowledged, GatewayEvent.ApprovalCancelled("approval-session"))
        assertEquals(StatusStripUi("1 running", "running"), cancelled.statusStripUi())
        assertNull(reduceGatewayEvent(cancelled, GatewayEvent.Done("running")).statusStripUi())
    }

    @Test
    fun `message completion does not remove a running strip before done`() {
        val started = reduceGatewayEvent(AppState(), GatewayEvent.MessageStart("turn", "a"))
        val completed = reduceGatewayEvent(started, GatewayEvent.MessageDone("a"))
        assertEquals(StatusStripUi("1 running", "a"), completed.statusStripUi())
        assertNull(reduceGatewayEvent(completed, GatewayEvent.Done("a")).statusStripUi())
    }

    @Test
    fun `summary counts deduplicated notices and all running sessions`() {
        val state = AppState(liveSessions = setOf("a", "b"))
        val first = reduceGatewayEvent(state, GatewayEvent.ApprovalPending("a"))
        val repeated = reduceGatewayEvent(first, GatewayEvent.ApprovalPending("a"))
        val both = reduceGatewayEvent(repeated, GatewayEvent.ApprovalPending("b"))
        assertEquals(StatusStripUi("2 approvals · 2 running", "a"), both.statusStripUi())
    }

    @Test
    fun `unscoped approval opens current thread without inventing a session`() {
        val pending = reduceGatewayEvent(AppState(selectedSession = "a"), GatewayEvent.ApprovalPending(null))
        assertEquals(StatusStripUi("1 approval", null), pending.statusStripUi())
        assertEquals(
            "dashboard — session unspecified",
            pending
                .threadUi()
                .notices
                .single()
                .origin,
        )
    }

    @Test
    fun `clarification stays reachable until desktop settlement`() {
        val pending =
            reduceGatewayEvent(
                AppState(),
                GatewayEvent.ClarifyRequested("question", null, "Which?", emptyList(), false, emptyList(), "a"),
            )
        assertEquals(StatusStripUi("1 clarification", "a"), pending.statusStripUi())
        val approval = reduceGatewayEvent(pending, GatewayEvent.ApprovalPending("b"))
        assertEquals(StatusStripUi("1 approval · 1 clarification", "b"), approval.statusStripUi())
        assertNull(reduceGatewayEvent(pending, GatewayEvent.ClarifyExpired("question", true, "a")).statusStripUi())
    }
}
