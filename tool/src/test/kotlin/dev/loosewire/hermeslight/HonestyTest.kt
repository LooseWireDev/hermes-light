package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.ApprovalKind
import dev.loosewire.hermeslight.net.GatewayEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HonestyTest {
    @Test
    fun `message completion cannot pretend the turn is done`() {
        val started = reduceGatewayEvent(AppState(), GatewayEvent.MessageStart("turn", "a"))
        val completed = reduceGatewayEvent(started, GatewayEvent.MessageComplete("answer", null, false, "a"))
        val messageDone = reduceGatewayEvent(completed, GatewayEvent.MessageDone("a"))
        assertTrue("a" in messageDone.liveSessions)
        assertTrue(messageDone.messages.isEmpty())
        assertFalse("a" in reduceGatewayEvent(messageDone, GatewayEvent.Done("a")).liveSessions)
    }

    @Test
    fun `failure clears only its session and keeps transcript content`() {
        val state = AppState(liveSessions = setOf("a", "b"), selectedSession = "b")
        val failed = reduceGatewayEvent(state, GatewayEvent.Failure("provider unavailable", "a"))
        assertEquals(setOf("b"), failed.liveSessions)
        assertEquals("b", failed.selectedSession)
        assertEquals("error: provider unavailable", failed.operationError)
    }

    @Test
    fun `pending approval survives display acknowledgement until confirmed resolution`() {
        val event =
            GatewayEvent.ApprovalRequested(
                requestId = "request",
                kind = ApprovalKind.COMMAND,
                command = "rm report",
                description = null,
                path = null,
                host = null,
                label = null,
                risk = null,
                choices = emptyList(),
                sessionId = "a",
            )
        val pending = reduceGatewayEvent(AppState(), GatewayEvent.ApprovalPending("a"))
        val once = reduceGatewayEvent(pending, event)
        val repeated = reduceGatewayEvent(once, event)
        val twice = reduceGatewayEvent(repeated, GatewayEvent.ApprovalPending("a"))
        val notice = twice.desktopNotices.getValue("a").single()
        assertEquals("approval pending", notice.title)
        assertEquals("a", notice.origin)
        assertEquals("rm report", notice.detail)
        val acknowledged = reduceGatewayEvent(twice, GatewayEvent.ApprovalReceived("request", "a"))
        assertEquals(twice.desktopNotices, acknowledged.desktopNotices)
        val resolved = acknowledged.settleApproval("a", "request")
        assertTrue(resolved.desktopNotices.getValue("a").isEmpty())
    }

    @Test
    fun `unscoped event never acquires the currently selected session identity`() {
        val state = AppState(selectedSession = "a")
        val pending = reduceGatewayEvent(state, GatewayEvent.ApprovalPending(null))
        assertTrue(pending.desktopNotices["a"].isNullOrEmpty())
        assertEquals(
            "dashboard — session unspecified",
            pending.desktopNotices
                .getValue(GLOBAL_NOTICE)
                .single()
                .origin,
        )
        assertTrue(reduceGatewayEvent(pending, GatewayEvent.MessageStart(null, null)).liveSessions.isEmpty())
    }

    @Test
    fun `clarify settlement keeps other sessions waiting`() {
        fun request(
            id: String,
            session: String,
        ) = GatewayEvent.ClarifyRequested(
            id,
            null,
            "Which project?",
            emptyList(),
            false,
            emptyList(),
            session,
        )
        val first = reduceGatewayEvent(AppState(), request("q1", "a"))
        val both = reduceGatewayEvent(first, request("q2", "b"))
        val settled = reduceGatewayEvent(both, GatewayEvent.ClarifyExpired("q1", true, "a"))
        assertTrue(settled.desktopNotices.getValue("a").isEmpty())
        assertTrue(
            settled.desktopNotices
                .getValue("b")
                .single()
                .clarification,
        )
    }
}
