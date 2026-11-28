package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.ApprovalKind
import dev.loosewire.hermeslight.net.GatewayEvent
import dev.loosewire.hermeslight.ui.ThreadMessageKind
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationRowsTest {
    private fun approval(choices: List<String>) =
        GatewayEvent.ApprovalRequested(
            requestId = "request:with:colons",
            kind = ApprovalKind.COMMAND,
            command = "write report",
            description = null,
            path = null,
            host = null,
            label = null,
            risk = null,
            choices = choices,
            sessionId = "a",
        )

    @Test
    fun `approval actions start on Yes and never invent Always`() {
        val conversation = Conversation().reduce(approval(listOf("once", "deny")))
        val actions = conversation.liveRows().filter { it.action?.startsWith("approval:") == true }
        assertEquals(listOf("Yes", "No"), actions.map { it.body })
        assertEquals("approval:request:with:colons:once", actions.first().action)
        val sending = conversation.markApprovalSending("request:with:colons", "once")
        assertTrue(sending.liveRows().none { it.action?.startsWith("approval:") == true })
        assertTrue(sending.liveRows().any { it.body == "sending…" })
        val acknowledged = sending.reduce(GatewayEvent.ApprovalReceived("request:with:colons", "a"))
        assertTrue(acknowledged.liveRows().any { it.body == "sending…" })
        val settled = acknowledged.settleApprovalResponse("request:with:colons")
        assertTrue(settled.liveRows().none { it.action?.startsWith("approval:") == true })
    }

    @Test
    fun `pending and rejected prompts are not rendered as delivered users`() {
        val pending = Conversation(composer = "hello").submit("p", "hello", 10)
        assertTrue(pending.liveRows().any { it.body.contains("pending: hello") })
        assertTrue(pending.liveRows().none { it.kind == ThreadMessageKind.User })
        val rejected = pending.submitResult("p", null, "Not allowed")
        assertEquals("hello", rejected.composer)
        assertTrue(rejected.liveRows().any { it.action == "retry:p" })
        assertTrue(rejected.liveRows().any { it.kind == ThreadMessageKind.Error && it.body == "Not allowed" })
        assertTrue(rejected.liveRows().none { it.kind == ThreadMessageKind.User })
    }

    @Test
    fun `conflict row offers a new chat instead of retry and hides it while recovering`() {
        val text = "  keep\n\nthis exact text  "
        val failed =
            Conversation()
                .submit("p", text)
                .submitResult("p", null, "rejected (4090)", rejectionCode = SESSION_CONFLICT_CODE)

        val rows = failed.liveRows()
        assertTrue(rows.any { it.body == "$SESSION_CONFLICT_ERROR: $text" })
        assertTrue(rows.none { it.action == "retry:p" })
        val action = rows.single { it.action?.startsWith("new-chat:") == true }
        assertEquals("start a new chat here", action.body)
        assertEquals("new-chat:p", action.action)

        val recovering =
            failed.copy(
                prompts = failed.prompts.map { it.copy(recovering = true) },
            )
        val recoveringRows = recovering.liveRows()
        assertTrue(recoveringRows.any { it.body == "$SESSION_CONFLICT_ERROR: $text" })
        assertTrue(recoveringRows.any { it.body == "creating session…" })
        assertTrue(recoveringRows.none { it.action?.startsWith("new-chat:") == true })
        assertTrue(recoveringRows.none { it.action == "retry:p" })
    }

    @Test
    fun `queue cancellation needs a receipt and streamed caret waits for gateway completion`() {
        val queued =
            Conversation(running = true)
                .submit("p", "next")
                .submitResult("p", buildJsonObject { put("status", "queued") })
        assertTrue(queued.liveRows().none { it.action == "cancel:p" })
        val receipted =
            queued.submitResult(
                "p",
                buildJsonObject {
                    put("status", "queued")
                    put("admission_id", "a1")
                },
            )
        assertTrue(receipted.liveRows().any { it.action == "cancel:p" })
        val streaming =
            Conversation()
                .reduce(GatewayEvent.MessageStart("t", "a"))
                .reduce(GatewayEvent.MessageDelta("answer", "a"))
        assertEquals("answer ▏", streaming.liveRows().single { it.kind == ThreadMessageKind.Assistant }.body)
        val interrupted = streaming.beginStop().stopResult()
        assertTrue(interrupted.running)
        assertEquals("answer ▏", interrupted.liveRows().single { it.kind == ThreadMessageKind.Assistant }.body)
        val done = interrupted.reduce(GatewayEvent.Done("a"))
        assertFalse(done.running)
        assertEquals("answer", done.liveRows().single { it.kind == ThreadMessageKind.Assistant }.body)
    }

    @Test
    fun `expired clarify has no remaining selectable options`() {
        val request = GatewayEvent.ClarifyRequested("q", "q1", "Which?", listOf("A", "B"), false, emptyList(), "a")
        val pending = Conversation().reduce(request)
        assertEquals(listOf("A", "B"), pending.liveRows().filter { it.action?.startsWith("clarify:") == true }.map { it.body })
        val expired = pending.reduce(GatewayEvent.ClarifyExpired("q", false, "a"))
        assertTrue(expired.liveRows().none { it.action?.startsWith("clarify:") == true })
        assertTrue(expired.liveRows().any { it.body == "expired — answer on desktop" })
    }
}
