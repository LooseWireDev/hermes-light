/*
 * Copyright 2026 LooseWireDev
 *
 * Deterministic tests for queued-send recovery; the wait between polls is injected, so no test
 * sleeps or depends on wall-clock timing.
 */
package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.GatewayEvent
import dev.loosewire.hermeslight.net.GatewayRpcException
import dev.loosewire.hermeslight.net.RpcFailureKind
import dev.loosewire.hermeslight.ui.ThreadMessageKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueuedPromptDeliveryTest {
    // ── Queue monitoring ─────────────────────────────────────────────────

    @Test
    fun `queued idle cancels once and promotes with exactly one direct submit`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += listOf(false, true)

        fixture.run()

        assertEquals(1, fixture.cancelCalls.size)
        assertEquals("adm-1", fixture.cancelCalls.single().admissionId)
        assertEquals(listOf("hello"), fixture.submitCalls)
        val prompt = fixture.prompt()!!
        assertEquals(PromptStatus.ACCEPTED, prompt.status)
        assertFalse(prompt.queued)
        assertTrue(prompt.promoted)
        assertNull(prompt.admissionId)
        assertNull(prompt.error)
        assertFalse(prompt.outcomeUnknown)
        // Active state confirms the start without claiming a transcript.
        assertTrue(fixture.conversation.liveRows().any { it.body.startsWith("accepted — awaiting transcript") })
        assertTrue(fixture.conversation.liveRows().none { it.action == "retry:p1" })
    }

    @Test
    fun `initial started missing or terminal rows never send`() {
        val base = queuedConversation()
        val rows =
            listOf(
                base.copy(prompts = base.prompts.map { it.copy(status = PromptStatus.STARTED) }),
                base.copy(prompts = base.prompts.map { it.copy(status = PromptStatus.FAILED) }),
                base.copy(prompts = base.prompts.map { it.copy(status = PromptStatus.CANCELLING) }),
                base.copy(prompts = base.prompts.map { it.copy(status = PromptStatus.UNKNOWN) }),
                Conversation(),
                Conversation().submit("p1", "hello"),
            )
        for (conversation in rows) {
            val fixture = Fixture(conversation)
            fixture.run()
            assertTrue(fixture.submitCalls.isEmpty())
            assertTrue(fixture.cancelCalls.isEmpty())
            assertEquals(0, fixture.pollCalls)
        }
    }

    @Test
    fun `legitimate busy queue waits and recovers only when idle`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += listOf(true, true, false, true)

        fixture.run()

        assertEquals(1, fixture.cancelCalls.size)
        assertEquals(listOf("hello"), fixture.submitCalls)
        assertEquals(4, fixture.pollCalls)
        assertEquals(PromptStatus.ACCEPTED, fixture.prompt()!!.status)
    }

    @Test
    fun `null poll keeps monitoring instead of failing`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += listOf(null, false, true)

        fixture.run()

        assertEquals(1, fixture.cancelCalls.size)
        assertEquals(listOf("hello"), fixture.submitCalls)
        assertEquals(3, fixture.pollCalls)
        assertEquals(PromptStatus.ACCEPTED, fixture.prompt()!!.status)
    }

    @Test
    fun `message start during poll wins without cancelling or resending`() {
        val fixture = Fixture(queuedConversation())
        fixture.onPoll = { current ->
            current.start()
            false
        }

        fixture.run()

        assertTrue(fixture.cancelCalls.isEmpty())
        assertTrue(fixture.submitCalls.isEmpty())
        assertEquals(PromptStatus.STARTED, fixture.prompt()!!.status)
    }

    @Test
    fun `message start during cancel wins without resending`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += false
        fixture.onCancel = { current, _ ->
            current.start()
            true
        }

        fixture.run()

        assertEquals(1, fixture.cancelCalls.size)
        assertTrue(fixture.submitCalls.isEmpty())
        assertEquals(PromptStatus.STARTED, fixture.prompt()!!.status)
    }

    @Test
    fun `refused cancellation marks unknown and never resends`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += false
        fixture.onCancel = { _, _ -> false }

        fixture.run()

        assertEquals(1, fixture.cancelCalls.size)
        assertTrue(fixture.submitCalls.isEmpty())
        val prompt = fixture.prompt()!!
        assertEquals(PromptStatus.UNKNOWN, prompt.status)
        assertTrue(prompt.outcomeUnknown)
        assertNotNull(prompt.error)
        assertTrue(fixture.conversation.liveRows().none { it.action == "retry:p1" })
    }

    @Test
    fun `missing admission receipt marks unknown without calling cancel`() {
        val fixture = Fixture(Conversation(running = true).submit("p1", "hello"))
        fixture.polls += false

        fixture.run()

        assertTrue(fixture.cancelCalls.isEmpty())
        assertTrue(fixture.submitCalls.isEmpty())
        assertEquals(PromptStatus.UNKNOWN, fixture.prompt()!!.status)
        assertTrue(fixture.prompt()!!.outcomeUnknown)
    }

    @Test
    fun `poll failure marks unknown and never resends`() {
        val fixture = Fixture(queuedConversation())
        fixture.onPoll = { _ -> error("snapshot failed") }

        fixture.run()

        assertTrue(fixture.cancelCalls.isEmpty())
        assertTrue(fixture.submitCalls.isEmpty())
        assertEquals(PromptStatus.UNKNOWN, fixture.prompt()!!.status)
        assertTrue(fixture.prompt()!!.outcomeUnknown)
    }

    @Test
    fun `cancel failure marks unknown and never resends`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += false
        fixture.onCancel = { _, _ -> error("cancel timed out") }

        fixture.run()

        assertEquals(1, fixture.cancelCalls.size)
        assertTrue(fixture.submitCalls.isEmpty())
        assertEquals(PromptStatus.UNKNOWN, fixture.prompt()!!.status)
        assertTrue(fixture.prompt()!!.outcomeUnknown)
    }

    // ── Promoted direct delivery ─────────────────────────────────────────

    @Test
    fun `promoted direct that never starts fails with the exact retry row`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += listOf(false, false)

        fixture.run()

        val prompt = fixture.prompt()!!
        assertEquals(PromptStatus.FAILED, prompt.status)
        assertEquals("could not start — retry", prompt.error)
        assertEquals(QUEUED_START_FAILURE, prompt.error)
        assertFalse(prompt.queued)
        assertFalse(prompt.outcomeUnknown)
        assertNull(prompt.admissionId)
        assertEquals(listOf("hello"), fixture.submitCalls)
        val rows = fixture.conversation.liveRows()
        assertTrue(rows.any { it.body.startsWith("could not start — retry:") })
        assertTrue(rows.any { it.action == "retry:p1" })
        assertFalse(rows.any { it.kind == ThreadMessageKind.Error })
    }

    @Test
    fun `promoted message start success never duplicates the send`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += false
        fixture.onSubmit = { current, _ ->
            current.start()
            acceptedResult()
        }

        fixture.run()

        assertEquals(listOf("hello"), fixture.submitCalls)
        assertEquals(1, fixture.pollCalls)
        val prompt = fixture.prompt()!!
        assertEquals(PromptStatus.STARTED, prompt.status)
        assertTrue(prompt.promoted)

        // A start that raced a timed-out submit still wins; the timeout must not mark it unknown.
        val timedOut = Fixture(queuedConversation())
        timedOut.polls += false
        timedOut.onSubmit = { current, _ ->
            current.start()
            throw GatewayRpcException("prompt.submit timed out", RpcFailureKind.TIMEOUT)
        }
        timedOut.run()
        assertEquals(listOf("hello"), timedOut.submitCalls)
        assertEquals(PromptStatus.STARTED, timedOut.prompt()!!.status)
        assertFalse(timedOut.prompt()!!.outcomeUnknown)
    }

    @Test
    fun `promoted transcript success never duplicates the send`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += false
        fixture.onSubmit = { current, _ ->
            current.remove()
            acceptedResult()
        }

        fixture.run()

        assertEquals(listOf("hello"), fixture.submitCalls)
        assertEquals(1, fixture.pollCalls)
        assertNull(fixture.prompt())
    }

    @Test
    fun `direct submit timeout marks unknown and never retries`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += false
        fixture.onSubmit = { _, _ ->
            throw GatewayRpcException("prompt.submit timed out", RpcFailureKind.TIMEOUT)
        }

        fixture.run()

        assertEquals(listOf("hello"), fixture.submitCalls)
        val prompt = fixture.prompt()!!
        assertEquals(PromptStatus.UNKNOWN, prompt.status)
        assertTrue(prompt.outcomeUnknown)
        assertEquals("prompt.submit timed out", prompt.error)
        assertTrue(fixture.conversation.liveRows().none { it.action == "retry:p1" })
    }

    @Test
    fun `pause while direct submit awaits its receipt never permits a blind resend`() {
        val fixture = Fixture(queuedConversation())
        fixture.polls += false
        fixture.onSubmit = { _, _ -> throw CancellationException("paused during submit") }

        assertTrue(fixture.runCancelling())
        fixture.run()

        assertEquals(listOf("hello"), fixture.submitCalls)
        assertEquals(PromptStatus.UNKNOWN, fixture.prompt()!!.status)
        assertTrue(fixture.conversation.liveRows().none { it.action == "retry:p1" })
    }

    @Test
    fun `direct submit rejection and rejection body fail with the exact retry row`() {
        val thrown = Fixture(queuedConversation())
        thrown.polls += false
        thrown.onSubmit = { _, _ ->
            throw GatewayRpcException("prompt.submit rejected", RpcFailureKind.REJECTED)
        }
        thrown.run()
        assertEquals(PromptStatus.FAILED, thrown.prompt()!!.status)
        assertEquals("could not start — retry", thrown.prompt()!!.error)
        assertNull(thrown.prompt()!!.rejectionCode)

        val body = Fixture(queuedConversation())
        body.polls += false
        body.onSubmit = { _, _ ->
            buildJsonObject {
                put("status", "rejected")
                put("message", "dashboard says no")
            }
        }
        body.run()
        assertEquals(PromptStatus.FAILED, body.prompt()!!.status)
        assertEquals("could not start — retry", body.prompt()!!.error)
        assertNull(body.prompt()!!.rejectionCode)
        assertEquals(listOf("hello"), body.submitCalls)
        assertTrue(body.conversation.liveRows().any { it.action == "retry:p1" })
    }

    @Test
    fun `promoted direct session conflict keeps the exact text and offers a new chat`() {
        val text = "  first line\n\tsecond\n\nkeep this "
        val base =
            Conversation(running = true)
                .submit("p1", text, afterRowId = 0)
                .submitResult("p1", queuedResult("adm-1"))

        val thrown = Fixture(base)
        thrown.polls += false
        thrown.onSubmit = { _, _ ->
            throw GatewayRpcException("prompt.submit rejected", RpcFailureKind.REJECTED, SESSION_CONFLICT_CODE)
        }
        thrown.run()

        assertEquals(listOf(text), thrown.submitCalls)
        val prompt = thrown.prompt()!!
        assertEquals(PromptStatus.FAILED, prompt.status)
        assertEquals(text, prompt.text)
        assertEquals(SESSION_CONFLICT_ERROR, prompt.error)
        assertEquals(SESSION_CONFLICT_CODE, prompt.rejectionCode)
        val rows = thrown.conversation.liveRows()
        assertTrue(rows.any { it.body == "$SESSION_CONFLICT_ERROR: $text" })
        assertTrue(rows.any { it.action == "new-chat:p1" })
        assertTrue(rows.none { it.action == "retry:p1" })

        val body = Fixture(base)
        body.polls += false
        body.onSubmit = { _, _ ->
            buildJsonObject {
                put("status", "rejected")
                put("code", SESSION_CONFLICT_CODE)
                put("message", "open elsewhere")
            }
        }
        body.run()

        assertEquals(SESSION_CONFLICT_ERROR, body.prompt()!!.error)
        assertEquals(SESSION_CONFLICT_CODE, body.prompt()!!.rejectionCode)
        assertEquals(text, body.prompt()!!.text)
        assertTrue(body.conversation.liveRows().any { it.action == "new-chat:p1" })
        assertTrue(body.conversation.liveRows().none { it.action == "retry:p1" })
    }

    @Test
    fun `late queued admission after promotion is cancelled before exposing retry`() {
        val cancelled = Fixture(queuedConversation())
        cancelled.polls += listOf(false, false)
        cancelled.onSubmit = { current, _ -> current.requeue("adm-2") }
        cancelled.run()
        assertEquals(2, cancelled.cancelCalls.size)
        assertEquals(listOf("hello"), cancelled.submitCalls)
        val prompt = cancelled.prompt()!!
        assertEquals(PromptStatus.FAILED, prompt.status)
        assertEquals("could not start — retry", prompt.error)
        assertNull(prompt.admissionId)
        assertTrue(cancelled.conversation.liveRows().any { it.action == "retry:p1" })

        val refused = Fixture(queuedConversation())
        refused.polls += listOf(false, false)
        refused.onSubmit = { current, _ -> current.requeue("adm-2") }
        var cancels = 0
        refused.onCancel = { _, _ -> ++cancels == 1 }
        refused.run()
        assertEquals(2, refused.cancelCalls.size)
        assertEquals(listOf("hello"), refused.submitCalls)
        val unknown = refused.prompt()!!
        assertEquals(PromptStatus.UNKNOWN, unknown.status)
        assertTrue(unknown.outcomeUnknown)
        assertTrue(refused.conversation.liveRows().none { it.action == "retry:p1" })
    }

    // ── Lifecycle rearm ──────────────────────────────────────────────────

    @Test
    fun `resume after promotion before the start check never resends`() {
        var waits = 0
        val fixture =
            Fixture(
                queuedConversation(),
                wait = {
                    waits++
                    if (waits == 2) throw CancellationException("paused")
                },
            )
        fixture.polls += false

        assertTrue(fixture.runCancelling())

        val promoted = fixture.prompt()!!
        assertEquals(PromptStatus.ACCEPTED, promoted.status)
        assertTrue(promoted.promoted)
        assertEquals(listOf("hello"), fixture.submitCalls)
        assertEquals(1, fixture.cancelCalls.size)
        assertEquals(1, fixture.pollCalls)

        fixture.polls += true
        fixture.run()

        assertEquals(listOf("hello"), fixture.submitCalls)
        assertEquals(1, fixture.cancelCalls.size)
        assertEquals(2, fixture.pollCalls)
        assertEquals(PromptStatus.ACCEPTED, fixture.prompt()!!.status)
    }

    @Test
    fun `resumed promoted queued receipt is cancelled but never promoted again`() {
        val base = queuedConversation()
        val promotedQueued =
            base.copy(
                prompts =
                    base.prompts.map {
                        it.copy(promoted = true, admissionId = "adm-2", status = PromptStatus.QUEUED)
                    },
            )
        val fixture = Fixture(promotedQueued)
        fixture.polls += false

        fixture.run()

        assertTrue(fixture.submitCalls.isEmpty())
        assertEquals(1, fixture.cancelCalls.size)
        assertEquals("adm-2", fixture.cancelCalls.single().admissionId)
        val prompt = fixture.prompt()!!
        assertEquals(PromptStatus.FAILED, prompt.status)
        assertEquals("could not start — retry", prompt.error)
        assertTrue(prompt.promoted)
        assertNull(prompt.admissionId)
        assertTrue(fixture.conversation.liveRows().any { it.action == "retry:p1" })
    }

    @Test
    fun `resumed promoted direct stays failed on idle instead of resending`() {
        var waits = 0
        val fixture =
            Fixture(
                queuedConversation(),
                wait = {
                    waits++
                    if (waits == 2) throw CancellationException("paused")
                },
            )
        fixture.polls += false
        assertTrue(fixture.runCancelling())

        fixture.polls += false
        fixture.run()

        assertEquals(listOf("hello"), fixture.submitCalls)
        assertEquals(1, fixture.cancelCalls.size)
        assertEquals(PromptStatus.FAILED, fixture.prompt()!!.status)
        assertEquals("could not start — retry", fixture.prompt()!!.error)
        assertTrue(fixture.conversation.liveRows().any { it.action == "retry:p1" })
    }

    // ── Fixture ──────────────────────────────────────────────────────────

    private class Fixture(
        initial: Conversation,
        private val wait: suspend () -> Unit = {},
    ) {
        var conversation: Conversation = initial
        val polls = mutableListOf<Boolean?>()
        var onPoll: (suspend (Fixture) -> Boolean?)? = null
        var onCancel: suspend (Fixture, PendingPrompt) -> Boolean = { _, _ -> true }
        var onSubmit: suspend (Fixture, String) -> JsonElement? = { _, _ -> acceptedResult() }
        val cancelCalls = mutableListOf<PendingPrompt>()
        val submitCalls = mutableListOf<String>()
        var pollCalls = 0
            private set

        val delivery =
            QueuedPromptDelivery(
                conversation = { conversation },
                update = { transform -> conversation = transform(conversation) },
                poll = {
                    pollCalls++
                    val action = onPoll
                    if (action != null) {
                        action(this)
                    } else if (polls.isEmpty()) {
                        false
                    } else {
                        polls.removeAt(0)
                    }
                },
                cancel = { prompt ->
                    cancelCalls += prompt
                    onCancel(this, prompt)
                },
                submit = { text ->
                    submitCalls += text
                    onSubmit(this, text)
                },
                waitForCheck = wait,
            )

        fun run(id: String = "p1") {
            runBlocking { delivery.confirm(id) }
        }

        fun runCancelling(id: String = "p1"): Boolean =
            try {
                runBlocking { delivery.confirm(id) }
                false
            } catch (error: CancellationException) {
                true
            }

        fun prompt(id: String = "p1"): PendingPrompt? = conversation.prompts.firstOrNull { it.id == id }

        fun start() {
            conversation = conversation.reduce(GatewayEvent.MessageStart(turnId = "turn-1", sessionId = "s"))
        }

        fun remove(id: String = "p1") {
            conversation = conversation.copy(prompts = conversation.prompts.filterNot { it.id == id })
        }

        fun requeue(admission: String): JsonElement {
            conversation =
                conversation.copy(
                    prompts =
                        conversation.prompts.map {
                            if (it.id == "p1") {
                                it.copy(queued = true, status = PromptStatus.QUEUED, admissionId = admission)
                            } else {
                                it
                            }
                        },
                )
            return queuedResult(admission)
        }
    }
}

private fun queuedResult(admission: String? = "adm-1"): JsonElement =
    buildJsonObject {
        put("status", "queued")
        admission?.let { put("admission_id", it) }
    }

private fun acceptedResult(admission: String? = null): JsonElement =
    buildJsonObject {
        put("status", "accepted")
        admission?.let { put("admission_id", it) }
    }

private fun queuedConversation(admission: String? = "adm-1"): Conversation =
    Conversation(running = true)
        .submit("p1", "hello", afterRowId = 0)
        .submitResult("p1", queuedResult(admission))
