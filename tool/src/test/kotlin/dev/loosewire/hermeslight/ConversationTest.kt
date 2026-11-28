/*
 * Copyright 2026 LooseWireDev
 *
 * Tests for the pure conversation state machine; no Android or socket dependencies.
 */
package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.data.TranscriptMessage
import dev.loosewire.hermeslight.net.ApprovalKind
import dev.loosewire.hermeslight.net.ClarifyQuestion
import dev.loosewire.hermeslight.net.GatewayEvent
import dev.loosewire.hermeslight.net.GatewayEvent.ClarifyRequested
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationTest {
    // ── Submit ───────────────────────────────────────────────────────────

    @Test
    fun `submit clears composer and keeps running gateway driven`() {
        val sent = Conversation(composer = "hello").submit("p1", "hello", afterRowId = 7)
        assertEquals("", sent.composer)
        assertFalse(sent.running)
        assertEquals(1, sent.prompts.size)
        assertEquals(PromptStatus.PENDING, sent.prompts.single().status)
        assertFalse(sent.prompts.single().queued)
        assertEquals(7, sent.prompts.single().baselineRowId)
    }

    @Test
    fun `submit while running records a queued row`() {
        val sent = Conversation(composer = "next", running = true).submit("p2", "next")
        val prompt = sent.prompts.single()
        assertTrue(prompt.queued)
        assertEquals(PromptStatus.QUEUED, prompt.status)
        assertTrue(sent.running)
    }

    @Test
    fun `rejected submit restores composer and shows an error`() {
        val sent = Conversation().submit("p1", "first").copy(composer = "later draft")
        val rejected = sent.submitResult("p1", result = null, error = "not connected")
        assertEquals("first\nlater draft", rejected.composer)
        assertEquals(PromptStatus.FAILED, rejected.prompts.single().status)
        assertEquals("not connected", rejected.prompts.single().error)
        assertEquals("not connected", rejected.error)
    }

    @Test
    fun `result status rejection restores composer`() {
        val sent = Conversation().submit("p1", "first")
        val result =
            buildJsonObject {
                put("status", "rejected")
                put("message", "denied")
            }
        val rejected = sent.submitResult("p1", result)
        assertEquals("first", rejected.composer)
        assertEquals("denied", rejected.prompts.single().error)
        assertNull(rejected.prompts.single().rejectionCode)
    }

    @Test
    fun `session conflict restores the exact multiline text without losing the composer`() {
        val text = "  first line\n\tsecond  line\n\nlast line "
        val draft = "  current draft\nwith whitespace  "
        val sent = Conversation().submit("p1", text).copy(composer = draft)

        val conflicted =
            sent.submitResult(
                "p1",
                result = null,
                error = "Dashboard rejected prompt.submit (code 4090): open elsewhere",
                rejectionCode = SESSION_CONFLICT_CODE,
            )

        assertEquals("$text\n$draft", conflicted.composer)
        val prompt = conflicted.prompts.single()
        assertEquals(text, prompt.text)
        assertEquals(PromptStatus.FAILED, prompt.status)
        assertEquals(SESSION_CONFLICT_ERROR, prompt.error)
        assertEquals(SESSION_CONFLICT_CODE, prompt.rejectionCode)
        assertEquals(SESSION_CONFLICT_ERROR, conflicted.error)
    }

    @Test
    fun `conflict code from a rejection body is stored without touching accepted outcomes`() {
        val sent = Conversation().submit("p1", "first")
        val conflict =
            sent.submitResult(
                "p1",
                buildJsonObject {
                    put("status", "rejected")
                    put("code", SESSION_CONFLICT_CODE)
                    put("message", "open elsewhere")
                },
            )
        assertEquals(SESSION_CONFLICT_ERROR, conflict.prompts.single().error)
        assertEquals(SESSION_CONFLICT_CODE, conflict.prompts.single().rejectionCode)

        val accepted =
            Conversation()
                .submit("p2", "second")
                .submitResult(
                    "p2",
                    buildJsonObject {
                        put("status", "accepted")
                        put("code", SESSION_CONFLICT_CODE)
                    },
                    rejectionCode = SESSION_CONFLICT_CODE,
                )
        assertEquals(PromptStatus.ACCEPTED, accepted.prompts.single().status)
        assertNull(accepted.prompts.single().error)
        assertNull(accepted.prompts.single().rejectionCode)
        assertEquals("", accepted.composer)
    }

    @Test
    fun `accepted submit records admission without delivery and keeps running false`() {
        val sent = Conversation().submit("p1", "hello")
        val result =
            buildJsonObject {
                put("status", "accepted")
                put("admission_id", "adm-9")
            }
        val accepted = sent.submitResult("p1", result)
        val prompt = accepted.prompts.single()
        assertEquals(PromptStatus.ACCEPTED, prompt.status)
        assertEquals("adm-9", prompt.admissionId)
        assertFalse(accepted.running)
        assertNull(accepted.stream)
        assertEquals("", accepted.composer)
    }

    @Test
    fun `queued acceptance stays queued with an admission id`() {
        val sent = Conversation(running = true).submit("p1", "next")
        val result =
            buildJsonObject {
                put("status", "queued")
                put("admission_id", 12)
            }
        val queued = sent.submitResult("p1", result)
        assertEquals(PromptStatus.QUEUED, queued.prompts.single().status)
        assertEquals("12", queued.prompts.single().admissionId)
        assertTrue(queued.running)
    }

    @Test
    fun `late submit result does not demote a started prompt`() {
        val started =
            Conversation(
                prompts = listOf(PendingPrompt("p1", "hello", false, PromptStatus.PENDING)),
                running = true,
            ).reduce(GatewayEvent.MessageStart("t1", "s1"))
        val accepted = started.submitResult("p1", buildJsonObject { put("status", "accepted") })
        assertEquals(PromptStatus.STARTED, accepted.prompts.single().status)
    }

    @Test
    fun `unknown submit outcome does not restore or retry`() {
        val sent = Conversation().submit("p1", "hello")
        val unknown = sent.submitUnknown("p1", "receipt timeout")
        val prompt = unknown.prompts.single()
        assertEquals(PromptStatus.UNKNOWN, prompt.status)
        assertTrue(prompt.outcomeUnknown)
        assertEquals("", unknown.composer)
        assertEquals("receipt timeout", prompt.error)
    }

    @Test
    fun `cancel queued needs admission and only explicit success removes the row`() {
        val accepted =
            Conversation(running = true)
                .submit("p1", "next")
                .submitResult("p1", buildJsonObject { put("admission_id", "adm-1") })
        val prompt = accepted.prompts.single()
        assertEquals(
            buildJsonObject {
                put("session_id", "s1")
                put("admission_id", "adm-1")
            },
            prompt.cancelParams("s1"),
        )
        assertNull(prompt.cancelParams(null))
        assertNull(prompt.copy(admissionId = null).cancelParams("s1"))

        val cancelling = accepted.cancelQueued("p1")
        assertEquals(PromptStatus.CANCELLING, cancelling.prompts.single().status)
        val failed = cancelling.cancelQueuedResult("p1", "cancel refused")
        assertEquals(PromptStatus.QUEUED, failed.prompts.single().status)
        assertEquals("cancel refused", failed.prompts.single().error)
        assertTrue(cancelling.cancelQueuedResult("p1").prompts.isEmpty())

        val race =
            cancelling
                .reduce(GatewayEvent.MessageStart("turn-1", "s1"))
                .cancelQueuedResult("p1", "cancel refused late")
        assertEquals(PromptStatus.STARTED, race.prompts.single().status)
        assertNull(race.prompts.single().error)
    }

    // ── Stop ─────────────────────────────────────────────────────────────

    @Test
    fun `stop acknowledgement never fabricates done`() {
        val streaming =
            Conversation()
                .reduce(GatewayEvent.MessageStart("t1", "s1"))
                .reduce(GatewayEvent.MessageDelta("half", "s1"))
        val stopping = streaming.beginStop()
        assertTrue(stopping.stopping)
        assertTrue(stopping.running)
        assertEquals("half", stopping.stream?.text)

        val ack = stopping.stopResult()
        assertFalse(ack.stopping)
        assertNull(ack.stopError)
        assertTrue(ack.running)
        assertEquals("half", ack.stream?.text)
        assertTrue(ack.stream?.streaming == true)
    }

    @Test
    fun `stop failure is shown without ending the turn`() {
        val failed = Conversation(running = true).beginStop().stopResult("interrupt failed")
        assertFalse(failed.stopping)
        assertEquals("interrupt failed", failed.stopError)
        assertTrue(failed.running)
    }

    // ── Streaming ────────────────────────────────────────────────────────

    @Test
    fun `streaming folds message families and reasoning`() {
        val started = Conversation().reduce(GatewayEvent.MessageStart("t1", "s1"))
        assertTrue(started.running)
        assertEquals("", started.stream?.text)

        val streamed =
            started
                .reduce(GatewayEvent.MessageDelta("Hel", "s1"))
                .reduce(GatewayEvent.MessageDelta("lo", "s1"))
                .reduce(GatewayEvent.MessageInterim("s1", text = "Hello interim"))
                .reduce(GatewayEvent.MessageInterim("s1"))
                .reduce(GatewayEvent.ReasoningDelta("think ", "s1"))
                .reduce(GatewayEvent.ReasoningDelta("more", "s1"))
                .reduce(GatewayEvent.Tool(dev.loosewire.hermeslight.net.ToolPhase.START, "terminal", "run", "s1"))
                .reduce(GatewayEvent.Process("process.start", buildJsonObject { put("text", "indexing") }, "s1"))
        assertEquals("Hello interim", streamed.stream?.text)
        assertEquals("think more", streamed.stream?.reasoning)
        assertEquals("indexing", streamed.stream?.tool)
        assertTrue(streamed.stream?.streaming == true)

        val complete =
            streamed.reduce(
                GatewayEvent.MessageComplete("Hello final", "because", false, "s1"),
            )
        assertEquals("Hello final", complete.stream?.text)
        assertEquals("because", complete.stream?.reasoning)

        val messageDone = complete.reduce(GatewayEvent.MessageDone("s1"))
        assertFalse(messageDone.running)
        assertFalse(messageDone.stream?.streaming ?: true)
        assertEquals("Hello final", messageDone.stream.text)

        val turnDone = messageDone.reduce(GatewayEvent.Done("s1"))
        assertFalse(turnDone.running)
    }

    @Test
    fun `reasoning available and tool activity fill the live reply`() {
        val available =
            Conversation().reduce(GatewayEvent.ReasoningAvailable("full reasoning", "s1"))
        assertEquals("full reasoning", available.stream?.reasoning)

        val tool =
            Conversation().reduce(
                GatewayEvent.Tool(dev.loosewire.hermeslight.net.ToolPhase.COMPLETE, "terminal", "ok", "s1"),
            )
        assertEquals("terminal — ok", tool.stream?.tool)
    }

    @Test
    fun `gateway error shows a message and stops the caret`() {
        val failed =
            Conversation()
                .reduce(GatewayEvent.MessageStart("t1", "s1"))
                .reduce(GatewayEvent.MessageDelta("partial", "s1"))
                .reduce(GatewayEvent.Failure("boom", "s1"))
        assertEquals("boom", failed.error)
        assertFalse(failed.running)
        assertFalse(failed.stream?.streaming ?: true)
        assertEquals("partial", failed.stream.text)
    }

    @Test
    fun `unknown events are ignored`() {
        val before =
            Conversation(
                stream = LiveReply("Hello", streaming = false),
                running = true,
                streamRevision = 3,
            )
        val after = before.reduce(GatewayEvent.Unknown("future.thing", "{}"))
        assertEquals(before, after)
    }

    @Test
    fun `session status drives running and stops the caret only from false`() {
        val started = Conversation().reduce(GatewayEvent.MessageStart("t1", "s1"))
        val idle = started.reduce(GatewayEvent.SessionStatus("idle", false, "s1"))
        assertFalse(idle.running)
        assertFalse(idle.stream?.streaming ?: true)
        val live = idle.reduce(GatewayEvent.SessionStatus("working", true, "s1"))
        assertTrue(live.running)
    }

    // ── Prompt promotion ─────────────────────────────────────────────────

    @Test
    fun `message start promotes accepted original ahead of queued row`() {
        val conversation =
            Conversation(
                prompts =
                    listOf(
                        PendingPrompt("queued", "next", queued = true, status = PromptStatus.QUEUED),
                        PendingPrompt("original", "first", queued = false, status = PromptStatus.ACCEPTED),
                    ),
                running = true,
                turnId = "t0",
            )
        val started = conversation.reduce(GatewayEvent.MessageStart("t1", "s1"))
        assertEquals(PromptStatus.QUEUED, started.prompts.first { it.id == "queued" }.status)
        assertEquals(PromptStatus.STARTED, started.prompts.first { it.id == "original" }.status)

        val sameSegment = started.reduce(GatewayEvent.MessageStart("t1", "s1"))
        assertEquals(PromptStatus.STARTED, sameSegment.prompts.first { it.id == "original" }.status)
        assertEquals(PromptStatus.QUEUED, sameSegment.prompts.first { it.id == "queued" }.status)

        val newTurn = sameSegment.reduce(GatewayEvent.MessageStart("t2", "s1"))
        assertEquals(PromptStatus.STARTED, newTurn.prompts.first { it.id == "queued" }.status)
    }

    @Test
    fun `queued row is not promoted during the same running turn`() {
        val conversation =
            Conversation(
                prompts = listOf(PendingPrompt("queued", "next", true, PromptStatus.QUEUED)),
                running = true,
                turnId = "t1",
            )
        val sameTurn = conversation.reduce(GatewayEvent.MessageStart("t1", "s1"))
        assertEquals(PromptStatus.QUEUED, sameTurn.prompts.single().status)
    }

    @Test
    fun `preparing preflight row is never promoted or reconciled`() {
        val conversation =
            Conversation(
                prompts = listOf(PendingPrompt("prep", "next", false, PromptStatus.PREPARING)),
                running = true,
                turnId = "t0",
            )
        val started = conversation.reduce(GatewayEvent.MessageStart("t1", "s1"))
        assertEquals(PromptStatus.PREPARING, started.prompts.single().status)

        val reconciled =
            started.reconcile(
                listOf(user(Int.MAX_VALUE, "next")),
            )
        assertEquals(PromptStatus.PREPARING, reconciled.prompts.single().status)
    }

    // ── Reconcile ────────────────────────────────────────────────────────

    @Test
    fun `reconcile prunes started and accepted rows only with newer REST evidence`() {
        val conversation =
            Conversation(
                prompts =
                    listOf(
                        PendingPrompt("started", "hello", false, PromptStatus.STARTED, baselineRowId = 10),
                        PendingPrompt("accepted", "next", false, PromptStatus.ACCEPTED, baselineRowId = 10),
                    ),
            )
        val old = conversation.reconcile(listOf(user(10, "hello"), user(10, "next")))
        assertEquals(2, old.prompts.size)

        val fresh = conversation.reconcile(listOf(user(11, "next")))
        assertEquals(listOf("started"), fresh.prompts.map { it.id })
    }

    @Test
    fun `reconcile consumes distinct rows for repeated identical text`() {
        val conversation =
            Conversation(
                prompts =
                    listOf(
                        PendingPrompt("one", "same", false, PromptStatus.ACCEPTED, baselineRowId = 0),
                        PendingPrompt("two", "same", false, PromptStatus.ACCEPTED, baselineRowId = 0),
                    ),
            )
        val oneRow = conversation.reconcile(listOf(user(1, "same")))
        assertEquals(listOf("two"), oneRow.prompts.map { it.id })
        val twoRows = conversation.reconcile(listOf(user(1, "same"), user(2, "same")))
        assertTrue(twoRows.prompts.isEmpty())
    }

    @Test
    fun `reconcile keeps queued rows without a durable baseline`() {
        val queued =
            Conversation(
                prompts = listOf(PendingPrompt("q", "next", true, PromptStatus.QUEUED)),
                running = true,
            )
        val reconciled = queued.reconcile(listOf(user(1, "next")))
        assertEquals(1, reconciled.prompts.size)
    }

    @Test
    fun `reconcile prunes queued and unknown only with newer rows`() {
        val conversation =
            Conversation(
                prompts =
                    listOf(
                        PendingPrompt("q", "next", true, PromptStatus.QUEUED, baselineRowId = 5),
                        PendingPrompt("u", "later", false, PromptStatus.UNKNOWN, baselineRowId = 5),
                    ),
            )
        val older = conversation.reconcile(listOf(user(5, "next"), user(5, "later")))
        assertEquals(2, older.prompts.size)
        val fresh = conversation.reconcile(listOf(user(6, "next"), user(6, "later")))
        assertTrue(fresh.prompts.isEmpty())
    }

    @Test
    fun `negative synthetic baseline never proves delivery`() {
        val conversation =
            Conversation(
                prompts =
                    listOf(
                        PendingPrompt("q", "next", true, PromptStatus.QUEUED, baselineRowId = -3),
                    ),
            )
        val reconciled = conversation.reconcile(listOf(user(9, "next")))
        assertEquals(1, reconciled.prompts.size)
    }

    @Test
    fun `reconcile keeps a live stream and clears a finished one`() {
        val live =
            Conversation(
                running = true,
                stream = LiveReply("partial", streaming = true),
                streamRevision = 4,
            )
        val kept = live.reconcile(emptyList(), expectedStreamRevision = 4)
        assertEquals("partial", kept.stream?.text)

        val finished = live.copy(stream = LiveReply("final", streaming = false))
        val cleared = finished.reconcile(emptyList(), expectedStreamRevision = 4)
        assertNull(cleared.stream)
        assertEquals(5, cleared.streamRevision)
    }

    @Test
    fun `reconcile revision guard keeps a newer stream`() {
        val conversation =
            Conversation(
                running = false,
                stream = LiveReply("final", streaming = false),
                streamRevision = 5,
            )
        val newer = conversation.reconcile(emptyList(), expectedStreamRevision = 4)
        assertEquals("final", newer.stream?.text)
        assertEquals(5, newer.streamRevision)
    }

    // ── Approvals ────────────────────────────────────────────────────────

    @Test
    fun `approval choices expose only once deny and permitted always`() {
        val legacy = ApprovalDecision(approval(choices = listOf("once", "session", "deny")))
        assertEquals(listOf("once", "deny"), legacy.allowedChoices())

        val permitted =
            ApprovalDecision(
                approval(choices = listOf("once", "session", "always", "deny"), allowAll = true),
            )
        assertEquals(listOf("once", "deny", "always"), permitted.allowedChoices())

        val refused = ApprovalDecision(approval(choices = listOf("once", "always", "deny")))
        assertEquals(listOf("once", "deny"), refused.allowedChoices())
    }

    @Test
    fun `approval respond params always gate all and permission`() {
        val permitted =
            ApprovalDecision(
                approval(requestId = "req-1", choices = listOf("once", "always", "deny"), allowAll = true),
            )
        val always = permitted.respondParams("always")
        assertNotNull(always)
        assertEquals("s1", always["session_id"]?.jsonPrimitive?.content)
        assertEquals("req-1", always["request_id"]?.jsonPrimitive?.content)
        assertEquals("always", always["choice"]?.jsonPrimitive?.content)
        assertEquals("false", always["all"]?.jsonPrimitive?.content)

        assertNull(permitted.respondParams("always", all = true))
        assertNull(permitted.respondParams("session"))
        assertNull(permitted.respondParams("once", sessionId = null))
        assertNull(permitted.copy(status = DecisionStatus.SENDING).respondParams("once"))
        assertNull(permitted.copy(status = DecisionStatus.SETTLED).respondParams("deny"))
    }

    @Test
    fun `approval without permission cannot respond always or invent an id`() {
        val decision = ApprovalDecision(approval(requestId = null, choices = listOf("once", "deny")))
        assertNull(decision.respondParams("always"))
        val params = decision.respondParams("deny")
        assertNotNull(params)
        assertNull(params["request_id"])
        assertEquals("false", params["all"]?.jsonPrimitive?.content)

        val placeholder = ApprovalDecision(approval(choices = emptyList()))
        assertTrue(placeholder.allowedChoices().isEmpty())
        assertNull(placeholder.respondParams("once"))

        val noContext =
            ApprovalDecision(
                approval(choices = listOf("once", "deny")).copy(
                    command = null,
                    description = null,
                    label = null,
                ),
            )
        assertTrue(noContext.allowedChoices().isEmpty())
        assertNull(noContext.respondParams("once"))
    }

    @Test
    fun `approval sending needs explicit settlement and cancellation expires`() {
        val conversation = Conversation().reduce(approval())
        val sending = conversation.markApprovalSending("a1", "once")
        assertEquals(DecisionStatus.SENDING, sending.approvals.single().status)
        assertEquals("once", sending.approvals.single().choice)
        assertEquals(
            DecisionStatus.SENDING,
            sending
                .markApprovalSending("a1", "once")
                .approvals
                .single()
                .status,
        )

        val failed = sending.markApprovalError("a1", "socket gone")
        assertEquals(DecisionStatus.PENDING, failed.approvals.single().status)
        assertEquals("socket gone", failed.approvals.single().error)

        val acknowledged = sending.reduce(GatewayEvent.ApprovalReceived("a1", "s1"))
        assertEquals(DecisionStatus.SENDING, acknowledged.approvals.single().status)
        assertTrue(acknowledged.approvals.single().acknowledged)

        val settled = acknowledged.settleApprovalResponse("a1")
        assertEquals(DecisionStatus.SETTLED, settled.approvals.single().status)

        val expired =
            sending.reduce(
                GatewayEvent.ApprovalCancelled(
                    sessionId = "s1",
                    reason = "interrupted",
                    requestIds = listOf("a1"),
                ),
            )
        assertEquals(DecisionStatus.EXPIRED, expired.approvals.single().status)
    }

    @Test
    fun `approval cancelled without ids expires every live approval`() {
        val conversation =
            Conversation()
                .reduce(approval())
                .reduce(
                    GatewayEvent.ApprovalCancelled(sessionId = "s1", reason = "teardown"),
                )
        assertEquals(DecisionStatus.EXPIRED, conversation.approvals.single().status)
    }

    @Test
    fun `replayed approval request does not resurrect a settled one`() {
        val conversation = Conversation().reduce(approval())
        val settled = conversation.settleApprovalResponse("a1")
        val replay = settled.reduce(approval())
        assertEquals(1, replay.approvals.size)
        assertEquals(DecisionStatus.SETTLED, replay.approvals.single().status)
    }

    @Test
    fun `canonical approval uses the fenced response shape`() {
        val decision =
            ApprovalDecision(
                approval(
                    requestId = "legacy-1",
                    choices = listOf("once", "deny"),
                    promptId = "prompt-1",
                    executionGeneration = 4,
                ),
            )
        val params = decision.respondParams("deny")
        assertNotNull(params)
        assertEquals(
            setOf("session_id", "execution_generation", "prompt_id", "choice"),
            params.keys,
        )
        assertEquals("4", params["execution_generation"]?.jsonPrimitive?.content)
        assertEquals("prompt-1", params["prompt_id"]?.jsonPrimitive?.content)
        assertNull(decision.respondParams("deny", all = true))
    }

    // ── Clarify ──────────────────────────────────────────────────────────

    @Test
    fun `legacy single clarify builds one exact respond`() {
        val decision = ClarifyDecision(legacyClarify())
        val call = decision.responseCalls(answers = mapOf("q1" to "A")).single()
        assertEquals("clarify.respond", call.method)
        assertEquals(
            setOf("session_id", "response", "answer", "clarify_id", "request_id", "question_id"),
            call.params.keys,
        )
        assertEquals("s1", call.params["session_id"]?.jsonPrimitive?.content)
        assertEquals("A", call.params["answer"]?.jsonPrimitive?.content)
        assertEquals("c1", call.params["clarify_id"]?.jsonPrimitive?.content)
        assertEquals("q1", call.params["question_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `legacy batch clarify emits one respond per answered question`() {
        val request =
            ClarifyRequested(
                clarifyId = "c1",
                questionId = "q1",
                text = "Two",
                options = emptyList(),
                multiSelect = false,
                questions =
                    listOf(
                        ClarifyQuestion("q1", "First?", listOf("A", "B"), multiSelect = false),
                        ClarifyQuestion("q2", "Second?", listOf("C", "D"), multiSelect = true),
                    ),
                sessionId = "s1",
            )
        val decision = ClarifyDecision(request = request)
        val calls = decision.responseCalls(answers = mapOf("q1" to "A", "q2" to "C, D"))
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.method == "clarify.respond" })
        assertEquals(listOf("q1", "q2"), calls.map { it.params["question_id"]?.jsonPrimitive?.content })
    }

    @Test
    fun `canonical single clarify uses the fenced shape only`() {
        val request =
            legacyClarify().copy(
                promptId = "prompt-1",
                executionGeneration = 7,
            )
        val decision = ClarifyDecision(request = request)
        val call = decision.responseCalls(answers = mapOf("q1" to "A")).single()
        assertEquals("clarify.respond", call.method)
        assertEquals(
            setOf("session_id", "execution_generation", "prompt_id", "answer"),
            call.params.keys,
        )
        assertEquals("7", call.params["execution_generation"]?.jsonPrimitive?.content)
        assertEquals("prompt-1", call.params["prompt_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `canonical batch clarify locks each answered question`() {
        val request =
            ClarifyRequested(
                clarifyId = "c1",
                questionId = "q1",
                text = "Two",
                options = emptyList(),
                multiSelect = false,
                questions =
                    listOf(
                        ClarifyQuestion("q1", "First?", listOf("A", "B"), multiSelect = false),
                        ClarifyQuestion("q2", "Second?", listOf("C", "D"), multiSelect = true),
                    ),
                sessionId = "s1",
                promptId = "prompt-1",
                executionGeneration = 7,
            )
        val calls = ClarifyDecision(request = request).responseCalls(answers = mapOf("q1" to "A", "q2" to "C"))
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.method == "clarify.lock" })
        assertEquals(setOf("request_id", "question_id", "answer"), calls.first().params.keys)
        assertEquals(
            "prompt-1",
            calls
                .first()
                .params["request_id"]
                ?.jsonPrimitive
                ?.content,
        )
    }

    @Test
    fun `clarify selection supports single and multi without sending`() {
        val request =
            ClarifyRequested(
                clarifyId = "c1",
                questionId = "q1",
                text = "Pick",
                options = emptyList(),
                multiSelect = false,
                questions =
                    listOf(
                        ClarifyQuestion("q1", "Single?", listOf("A", "B"), multiSelect = false),
                        ClarifyQuestion("q2", "Multi?", listOf("C", "D"), multiSelect = true),
                    ),
                sessionId = "s1",
            )
        val decision =
            ClarifyDecision(request = request)
                .toggleChoice("q1", "A")
                .toggleChoice("q1", "B")
                .toggleChoice("q2", "C")
                .toggleChoice("q2", "D")
                .withAnswer("q2", "custom")
        assertEquals("B", decision.answerFor("q1"))
        assertEquals("C, D, custom", decision.answerFor("q2"))
        assertEquals(DecisionStatus.PENDING, decision.status)
    }

    @Test
    fun `clarify never resends locked or settled answers`() {
        val locked =
            ClarifyDecision(
                request = legacyClarify().copy(lockedAnswers = mapOf("q1" to "A")),
            )
        assertNull(locked.answerFor("q1"))
        assertTrue(locked.responseCalls(answers = mapOf("q1" to "A")).isEmpty())

        val settled = ClarifyDecision(request = legacyClarify()).copy(status = DecisionStatus.SETTLED)
        assertTrue(settled.responseCalls(answers = mapOf("q1" to "A")).isEmpty())
    }

    @Test
    fun `clarify send failure restores pending and expire stops input`() {
        val conversation = Conversation().reduce(legacyClarify())
        val sending = conversation.markClarifySending("c1")
        assertEquals(DecisionStatus.SENDING, sending.clarifications.single().status)
        val failed = sending.markClarifyError("c1", "socket gone")
        assertEquals(DecisionStatus.PENDING, failed.clarifications.single().status)
        assertEquals("socket gone", failed.clarifications.single().error)

        val expired = failed.reduce(GatewayEvent.ClarifyExpired("c1", false, "s1"))
        assertEquals(DecisionStatus.EXPIRED, expired.clarifications.single().status)
        val replay =
            expired.reduce(legacyClarify())
        assertEquals(DecisionStatus.EXPIRED, replay.clarifications.single().status)

        val settled = failed.reduce(GatewayEvent.ClarifyExpired("c1", true, "s1"))
        assertEquals(DecisionStatus.SETTLED, settled.clarifications.single().status)
    }

    @Test
    fun `clarify replay merges locked answers and resets only for a new generation`() {
        val first = Conversation().reduce(legacyClarify())
        val selected =
            first.copy(
                clarifications =
                    first.clarifications.map { it.toggleChoice("q1", "A") },
            )
        val replay =
            selected.reduce(
                legacyClarify().copy(lockedAnswers = mapOf("q1" to "server")),
            )
        assertEquals(listOf("A"), replay.clarifications.single().selectedChoices["q1"])
        assertEquals("server", replay.clarifications.single().lockedAnswers["q1"])

        val nextGeneration =
            replay.reduce(
                legacyClarify().copy(
                    promptId = "prompt-2",
                    executionGeneration = 2,
                    lockedAnswers = mapOf("q1" to "fresh"),
                ),
            )
        assertTrue(
            nextGeneration.clarifications
                .single()
                .selectedChoices
                .isEmpty(),
        )
        assertEquals("fresh", nextGeneration.clarifications.single().lockedAnswers["q1"])
        assertEquals(DecisionStatus.PENDING, nextGeneration.clarifications.single().status)
    }

    @Test
    fun `placeholder clarify has no invented choices`() {
        val placeholder =
            ClarifyRequested(
                clarifyId = "c2",
                questionId = null,
                text = "Waiting",
                options = emptyList(),
                multiSelect = false,
                questions = emptyList(),
                sessionId = "s1",
            )
        val decision = ClarifyDecision(request = placeholder)
        assertEquals(1, decision.questions().size)
        assertTrue(
            decision
                .questions()
                .single()
                .choices
                .isEmpty(),
        )
        assertTrue(decision.responseCalls().isEmpty())
    }

    private fun legacyClarify(): GatewayEvent.ClarifyRequested =
        GatewayEvent.ClarifyRequested(
            clarifyId = "c1",
            questionId = "q1",
            text = "Pick one",
            options = listOf("A", "B"),
            multiSelect = false,
            questions = emptyList(),
            sessionId = "s1",
        )

    private fun approval(
        requestId: String? = "a1",
        choices: List<String> = listOf("once", "deny"),
        allowAll: Boolean = false,
        promptId: String? = null,
        executionGeneration: Int? = null,
    ): GatewayEvent.ApprovalRequested =
        GatewayEvent.ApprovalRequested(
            requestId = requestId,
            kind = ApprovalKind.COMMAND,
            command = "ls",
            description = "list",
            path = null,
            host = null,
            label = null,
            risk = null,
            choices = choices,
            sessionId = "s1",
            allowAll = allowAll,
            executionGeneration = executionGeneration,
            promptId = promptId,
        )

    private fun user(
        rowId: Int,
        text: String,
    ): TranscriptMessage =
        TranscriptMessage(
            rowId = rowId,
            role = "user",
            text = text,
            reasoning = null,
            type = null,
            toolCallId = null,
            timestampEpochMs = null,
            displayKind = null,
        )
}
