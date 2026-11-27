/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Tests for tolerant gateway frame parsing.
 */
package dev.loosewire.hermeslight.net

import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayEventParserTest {
    @Test
    fun `ready carries change-event and replay metadata`() {
        val event =
            event(
                type = "gateway.ready",
                payload = """{"change_events":true,"replay_epoch":"epoch-1"}""",
            )
        val ready = event as? GatewayEvent.Ready ?: error("expected ready, got $event")
        assertTrue(ready.changeEvents)
        assertEquals("epoch-1", ready.replayEpoch)
        assertNull(ready.sessionId)
    }

    @Test
    fun `message family maps all phases`() {
        assertEquals(
            GatewayEvent.MessageStart("turn-1", "s1"),
            event("message.start", """{"turn_id":"turn-1"}"""),
        )
        assertEquals(
            GatewayEvent.MessageDelta("hello", "s1"),
            event("message.delta", """{"text":"hello"}"""),
        )
        assertEquals(
            GatewayEvent.MessageInterim("s1"),
            event("message.interim"),
        )
        assertEquals(
            GatewayEvent.MessageComplete("answer", "because", true, "s1"),
            event(
                "message.complete",
                """{"text":"answer","reasoning":"because","paused":true}""",
            ),
        )
        assertEquals(GatewayEvent.MessageDone("s1"), event("message.done"))
        assertEquals(
            GatewayEvent.ReasoningDelta("thinking", "s1"),
            event("reasoning.delta", """{"text":"thinking"}"""),
        )
        assertEquals(
            GatewayEvent.ReasoningAvailable("done thinking", "s1"),
            event("reasoning.available", """{"text":"done thinking"}"""),
        )
    }

    @Test
    fun `tool family maps phases and detail`() {
        assertEquals(
            GatewayEvent.Tool(ToolPhase.START, "terminal", "run", "s1"),
            event("tool.start", """{"name":"terminal","description":"run"}"""),
        )
        assertEquals(
            GatewayEvent.Tool(ToolPhase.PROGRESS, "terminal", "half", "s1"),
            event("tool.progress", """{"name":"terminal","preview":"half"}"""),
        )
        assertEquals(
            GatewayEvent.Tool(ToolPhase.COMPLETE, "terminal", "ok", "s1"),
            event("tool.complete", """{"name":"terminal","summary":"ok"}"""),
        )
        assertEquals(
            GatewayEvent.Tool(ToolPhase.GENERATING, "terminal", null, "s1"),
            event("tool.generating", """{"name":"terminal"}"""),
        )
        assertEquals(
            GatewayEvent.Tool(ToolPhase.PROGRESS, "terminal", "high", "s1"),
            event("tool.output_risk", """{"name":"terminal","risk":"high"}"""),
        )
    }

    @Test
    fun `approval notices are read-only but fully described`() {
        val requested =
            event(
                "approval.request",
                """
                {
                  "request_id": "req-1",
                  "kind": "access",
                  "command": "cat secrets",
                  "description": "read a folder",
                  "path": "/etc",
                  "access": "read",
                  "risk": "sensitive",
                  "choices": ["once", "deny"]
                }
                """.trimIndent(),
            ) as? GatewayEvent.ApprovalRequested ?: error("expected approval")
        assertEquals("req-1", requested.requestId)
        assertEquals(ApprovalKind.ACCESS, requested.kind)
        assertEquals("cat secrets", requested.command)
        assertEquals("/etc", requested.path)
        assertEquals(listOf("once", "deny"), requested.choices)
        assertEquals("s1", requested.sessionId)

        val defaults =
            event(
                "approval.request",
                """{"request_id":"req-2","smart_denied":true}""",
            ) as GatewayEvent.ApprovalRequested
        assertEquals(ApprovalKind.COMMAND, defaults.kind)
        assertEquals(listOf("once", "deny"), defaults.choices)

        assertEquals(
            GatewayEvent.ApprovalPending("s1"),
            event("approval.pending"),
        )
        assertEquals(
            GatewayEvent.ApprovalReceived("req-3", "s1"),
            event("approval.received", """{"request_id":"req-3"}"""),
        )
    }

    @Test
    fun `clarify supports batch and legacy payloads`() {
        val batch =
            event(
                "clarify.request",
                """
                {
                  "request_id": "cl-1",
                  "questions": [
                    {"qid": "q1", "question": "Which?", "choices": ["a", "b"]},
                    {"qid": "q2", "question": "Why?", "multi_select": true}
                  ]
                }
                """.trimIndent(),
            ) as? GatewayEvent.ClarifyRequested ?: error("expected clarify")
        assertEquals("cl-1", batch.clarifyId)
        assertEquals("q1", batch.questionId)
        assertEquals("1. Which?\n\n2. Why?", batch.text)
        assertEquals(listOf("a", "b"), batch.options)
        assertTrue(!batch.multiSelect)
        assertEquals(2, batch.questions.size)
        assertTrue(batch.questions[1].multiSelect)

        val legacy =
            event(
                "clarify.request",
                """{"clarify_id":"cl-2","qid":"one","question":"Pick","options":["x","y"],"multi_select":true}""",
            ) as GatewayEvent.ClarifyRequested
        assertEquals("cl-2", legacy.clarifyId)
        assertEquals("one", legacy.questionId)
        assertEquals("Pick", legacy.text)
        assertEquals(listOf("x", "y"), legacy.options)
        assertTrue(legacy.multiSelect)

        assertEquals(
            GatewayEvent.ClarifyExpired("cl-3", false, "s1"),
            event("clarify.expire", """{"clarify_id":"cl-3"}"""),
        )
        assertEquals(
            GatewayEvent.ClarifyExpired("cl-4", true, "s1"),
            event("clarify.settled", """{"prompt_id":"cl-4"}"""),
        )
    }

    @Test
    fun `process, done, error, status, notice, and change are recognized`() {
        val process =
            event("process.stdout", """{"pid":7,"line":"working"}""") as? GatewayEvent.Process
                ?: error("expected process")
        assertEquals("process.stdout", process.type)
        assertEquals(7, process.payload["pid"].toString().toInt())
        assertEquals("s1", process.sessionId)

        assertEquals(GatewayEvent.Done("s1"), event("done"))
        assertEquals(
            GatewayEvent.Failure("gateway crashed", "s1"),
            event("error", """{"message":"gateway crashed"}"""),
        )
        assertEquals(
            GatewayEvent.Status("heartbeat", "alive", "s1"),
            event("status.update", """{"kind":"heartbeat","text":"alive"}"""),
        )
        assertEquals(
            GatewayEvent.Notice("heads up", "info", "toast", "s1"),
            event(
                "notification.show",
                """{"text":"heads up","level":"info","kind":"toast"}""",
            ),
        )
        assertEquals(
            GatewayEvent.Change("sessions.changed"),
            event("sessions.changed"),
        )
    }

    @Test
    fun `session live flags derive from the gateway status`() {
        val working =
            event(
                "session.info",
                """{"status":"working","title":"June"}""",
            ) as GatewayEvent.SessionInfo
        assertEquals("working", working.status)
        assertEquals("June", working.title)
        assertEquals("s1", working.sessionId)

        assertEquals(
            GatewayEvent.SessionStatus("streaming", true, "s1"),
            event("session.status", """{"status":"streaming"}"""),
        )
        assertEquals(
            GatewayEvent.SessionStatus("idle", false, "s1"),
            event("session.status", """{"status":"idle"}"""),
        )
        assertEquals(
            GatewayEvent.SessionStatus(null, null, "s1"),
            event("session.status", "{}"),
        )
    }

    @Test
    fun `json-rpc responses and server requests are classified`() {
        val response =
            GatewayEventParser.parse(
                """{"jsonrpc":"2.0","id":"7","result":{"ok":true}}""",
            ) as ParsedFrame.Response
        assertEquals("7", response.id)
        assertEquals(
            true,
            response.result
                ?.jsonObject
                ?.get("ok")
                ?.toString()
                ?.toBoolean(),
        )

        val error =
            GatewayEventParser.parse(
                """{"jsonrpc":"2.0","id":8,"error":{"code":-32601,"message":"unknown"}}""",
            ) as ParsedFrame.ResponseError
        assertEquals("8", error.id)
        assertEquals(-32601, error.error.code)
        assertEquals("unknown", error.error.message)

        val request =
            GatewayEventParser.parse(
                """{"jsonrpc":"2.0","id":"srv-1","method":"approval.request","params":{"session_id":"s1"}}""",
            ) as ParsedFrame.ServerRequest
        assertEquals("srv-1", request.id)
        assertEquals("approval.request", request.method)
        assertEquals("s1", request.params["session_id"].toString().trim('"'))
        val projected = request.event as? GatewayEvent.ApprovalRequested ?: error("expected approval projection")
        assertEquals("s1", projected.sessionId)
        assertNull(projected.requestId)

        val unhandled =
            GatewayEventParser.parse(
                """{"jsonrpc":"2.0","id":"srv-2","method":"config.get","params":{}}""",
            ) as ParsedFrame.ServerRequest
        assertNull(unhandled.event)
    }

    @Test
    fun `unknown, malformed, and incomplete frames never throw`() {
        val unknown = (event("queue.updated") as GatewayEvent.Unknown)
        assertEquals("queue.updated", unknown.type)
        assertNull(unknown.sessionId)

        assertTrue(GatewayEventParser.parse("not-json") is ParsedFrame.Invalid)
        assertTrue(GatewayEventParser.parse("{}") is ParsedFrame.Invalid)
        assertTrue(
            GatewayEventParser.parse("""{"method":"event","params":{}}""") is ParsedFrame.Invalid,
        )

        val missingPayload =
            (event("message.delta") as GatewayEvent.MessageDelta)
        assertEquals("", missingPayload.text)

        val primitivePayload =
            event(
                type = "message.delta",
                payload = "\"raw\"",
            ) as GatewayEvent.MessageDelta
        assertEquals("", primitivePayload.text)

        val noSession =
            GatewayEventParser.parse(
                """{"method":"event","params":{"type":"message.done","payload":{}}}""",
            ) as ParsedFrame.Event
        assertNull((noSession.event as GatewayEvent.MessageDone).sessionId)
    }

    @Test
    fun `backoff doubles to the ceiling`() {
        val config =
            GatewayConfig(
                initialBackoffMillis = 1_000L,
                maxBackoffMillis = 30_000L,
                factor = 2.0,
            )
        assertEquals(1_000L, backoffDelayMillis(config, 1))
        assertEquals(2_000L, backoffDelayMillis(config, 2))
        assertEquals(4_000L, backoffDelayMillis(config, 3))
        assertEquals(8_000L, backoffDelayMillis(config, 4))
        assertEquals(16_000L, backoffDelayMillis(config, 5))
        assertEquals(30_000L, backoffDelayMillis(config, 6))
        assertEquals(30_000L, backoffDelayMillis(config, 20))

        val flat = GatewayConfig(initialBackoffMillis = 1_000L, maxBackoffMillis = 30_000L, factor = 1.0)
        assertEquals(1_000L, backoffDelayMillis(flat, Int.MAX_VALUE))

        val slow = GatewayConfig(initialBackoffMillis = 1L, maxBackoffMillis = 30_000L, factor = 1.0001)
        assertTrue(backoffDelayMillis(slow, Int.MAX_VALUE) in 1L..30_000L)
    }

    private fun event(
        type: String,
        payload: String = "{}",
        sessionId: String? = "s1",
    ): GatewayEvent {
        val sessionField = if (sessionId == null) "" else ",\"session_id\":\"$sessionId\""
        val raw =
            """{"jsonrpc":"2.0","method":"event","params":{"type":"$type"$sessionField,"payload":$payload}}"""
        val parsed = GatewayEventParser.parse(raw)
        return (parsed as? ParsedFrame.Event)?.event ?: error("expected event, got $parsed")
    }
}
