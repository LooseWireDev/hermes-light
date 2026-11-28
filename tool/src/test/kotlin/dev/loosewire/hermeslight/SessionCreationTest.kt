package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.GatewayEvent
import dev.loosewire.hermeslight.net.SessionRuntime
import dev.loosewire.hermeslight.ui.ThreadMessageKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionCreationTest {
    @Test
    fun `create opens an empty thread with composer focus and an already live identity`() =
        runBlocking {
            val fixture = Fixture()
            fixture.state = fixture.state.copy(threadHasOlder = true, threadHasNewer = true, positionRow = 50, positionOffset = 70)
            fixture.creation().start()
            val thread = fixture.state.threadUi()
            val source = fixture.state.conversations.getValue("old")
            assertEquals("new/session", fixture.state.selectedSession)
            assertEquals(listOf("new/session"), fixture.opened)
            assertTrue(thread.focusComposer)
            assertTrue(thread.messages.isEmpty())
            assertEquals("", thread.composer)
            assertFalse(fixture.state.threadHasOlder)
            assertFalse(fixture.state.threadHasNewer)
            assertNull(fixture.state.positionRow)
            assertEquals(0, fixture.state.positionOffset)
            assertFalse(fixture.state.creatingSession)
            assertEquals(fixture.text, source.composer)
            assertEquals("new/session", fixture.runtime.resume("new/session"))
            assertTrue(fixture.sent.isEmpty())
        }

    @Test
    fun `4090 waits for action then sends exact retained text and routes the response into the new thread`() =
        runBlocking {
            val fixture = Fixture()
            assertEquals(0, fixture.creates)
            val initialRows = fixture.state.threadUi().messages
            assertTrue(initialRows.any { it.action == "new-chat:p" })
            fixture.creation().recover("old", "p")
            assertEquals(1, fixture.creates)
            assertEquals("new/session", fixture.state.selectedSession)
            val target = fixture.state.conversations.getValue("new/session")
            val pending = target.prompts.single()
            fixture.runtime.submit("new/session", pending.text, pending.queued)
            val sent = fixture.sent.single()
            assertEquals(fixture.text, sent["text"]?.jsonPrimitive?.content)
            assertEquals("new/session", sent["session_id"]?.jsonPrimitive?.content)
            val source = fixture.state.conversations.getValue("old")
            assertTrue(source.prompts.isEmpty())
            assertEquals(fixture.text, source.composer)
            fixture.state = reduceGatewayEvent(fixture.state, GatewayEvent.MessageStart("turn", "new/session"))
            fixture.state = reduceGatewayEvent(fixture.state, GatewayEvent.MessageDelta("new answer", "new/session"))
            fixture.state = reduceGatewayEvent(fixture.state, GatewayEvent.Done("new/session"))
            val rows = fixture.state.threadUi().messages
            assertEquals(fixture.text, rows.single { it.kind == ThreadMessageKind.User }.body)
            assertEquals("new answer", rows.single { it.kind == ThreadMessageKind.Assistant }.body)
            assertFalse(fixture.state.threadUi().running)
            assertNull(source.stream)
        }

    @Test
    fun `creation failure preserves the prompt later draft and explicit recovery action`() =
        runBlocking {
            val fixture = Fixture()
            fixture.state =
                fixture.state.copy(
                    conversations =
                        fixture.state.conversations.mapValues { (_, conversation) ->
                            conversation.copy(composer = "later draft")
                        },
                )
            fixture.creation { error("Dashboard unavailable") }.recover("old", "p")
            val source = fixture.state.conversations.getValue("old")
            val rows = fixture.state.threadUi().messages
            assertEquals("old", fixture.state.selectedSession)
            assertEquals("later draft", fixture.state.threadUi().composer)
            assertEquals(fixture.text, source.prompts.single().text)
            assertTrue(rows.any { it.action == "new-chat:p" })
            assertEquals("Dashboard unavailable", fixture.state.operationError)
            assertFalse(fixture.state.creatingSession)
            assertTrue(fixture.opened.isEmpty())
            assertTrue(fixture.sent.isEmpty())
        }

    @Test
    fun `double activation during create cannot create or submit twice and cancellation retains text`() =
        runBlocking {
            val fixture = Fixture()
            val receipt = CompletableDeferred<String>()
            val creation = fixture.creation { receipt.await() }
            val first = async(start = CoroutineStart.UNDISPATCHED) { creation.recover("old", "p") }
            assertTrue(fixture.state.creatingSession)
            val creatingRows = fixture.state.threadUi().messages
            assertTrue(creatingRows.none { it.action == "new-chat:p" })
            creation.recover("old", "p")
            assertEquals(1, fixture.creates)
            first.cancelAndJoin()
            val source = fixture.state.conversations.getValue("old")
            val rows = fixture.state.threadUi().messages
            assertFalse(fixture.state.creatingSession)
            assertEquals(fixture.text, source.prompts.single().text)
            assertTrue(rows.any { it.action == "new-chat:p" })
            assertTrue(fixture.sent.isEmpty())
        }

    @Test
    fun `rejection after successful creation keeps the original text in the new composer and retry row`() =
        runBlocking {
            val fixture = Fixture()
            fixture.creation().recover("old", "p")
            val conversation = fixture.state.conversations.getValue("new/session")
            val rejected = conversation.submitResult("recovered", null, "Not allowed", rejectionCode = 4030)
            fixture.state = fixture.state.copy(conversations = fixture.state.conversations + ("new/session" to rejected))
            assertEquals(fixture.text, fixture.state.threadUi().composer)
            assertEquals(fixture.text, rejected.prompts.single().text)
            val rows = fixture.state.threadUi().messages
            assertTrue(rows.any { it.action == "retry:recovered" })
            assertEquals("new/session", fixture.state.selectedSession)
        }

    @Test
    fun `non4090 rejection stays on ordinary retry and cannot trigger new chat recovery`() =
        runBlocking {
            val fixture = Fixture(code = 4030)
            fixture.creation().recover("old", "p")
            assertEquals(0, fixture.creates)
            assertEquals("old", fixture.state.selectedSession)
            assertEquals(fixture.text, fixture.state.threadUi().composer)
            val rows = fixture.state.threadUi().messages
            assertTrue(rows.any { it.action == "retry:p" })
            assertTrue(rows.none { it.action?.startsWith("new-chat:") == true })
            assertTrue(fixture.opened.isEmpty())
        }

    private class Fixture(
        code: Int = 4090,
    ) {
        val text = "  keep this exact text\nincluding a second line  "
        var state =
            AppState(
                selectedSession = "old",
                conversations = mapOf("old" to Conversation().submit("p", text).submitResult("p", null, "Refused", rejectionCode = code)),
            )
        var creates = 0
        val opened = mutableListOf<String>()
        val sent = mutableListOf<JsonObject>()
        val runtime =
            SessionRuntime(
                resolve = { error("A new session must not resolve a stored tip") },
                resumeSession = { error("A new session must not resume") },
                request = {
                    sent.add(it)
                    buildJsonObject { put("status", "accepted") }
                },
            )

        fun creation(create: suspend () -> String = { "new/session" }) =
            SessionCreation(
                state = { state },
                update = { state = it(state) },
                runtime = runtime,
                create = {
                    creates++
                    create()
                },
                opened = { opened.add(it) },
                submit = { session, text ->
                    val conversation = state.conversations.getValue(session).submit("recovered", text)
                    state = state.copy(conversations = state.conversations + (session to conversation))
                },
            )
    }
}
