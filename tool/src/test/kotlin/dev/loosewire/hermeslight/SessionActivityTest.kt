package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.GatewayEvent
import dev.loosewire.hermeslight.net.SessionRuntime
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionActivityTest {
    @Test
    fun `idle gateway state on open clears stale running and first submit goes directly`() =
        runBlocking {
            val sent = mutableListOf<JsonObject>()
            val runtime =
                SessionRuntime(
                    resolve = { "tip" },
                    resumeSession = { "runtime" },
                    request = {
                        sent.add(it)
                        buildJsonObject { put("status", "accepted") }
                    },
                )
            val stale =
                AppState(
                    selectedSession = "stored",
                    liveSessions = setOf("stored"),
                    conversations = mapOf("stored" to Conversation(running = true, stream = LiveReply("old answer"))),
                )
            runtime.resume("stored")
            val active = activeSessionIds(Json.parseToJsonElement("""{"sessions":[]}"""), runtime::storedId)
            val opened = stale.reconcileActivity(active, emptySet())
            assertFalse(opened.threadUi().running)
            assertNull(opened.statusStripUi())
            val conversation = opened.conversations.getValue("stored")
            assertFalse(conversation.stream!!.streaming)
            val prompt =
                conversation
                    .submit("p", "hello", 10)
                    .prompts
                    .single()
            runtime.submit("stored", prompt.text, prompt.queued)
            assertEquals(1, sent.size)
            assertEquals(
                buildJsonObject {
                    put("session_id", "runtime")
                    put("text", "hello")
                },
                sent.single(),
            )
        }

    @Test
    fun `live runtime aliases on resume override remembered idle state`() =
        runBlocking {
            val runtime =
                SessionRuntime(
                    resolve = { it },
                    resumeSession = { "runtime" },
                    request = { error("No prompt should be sent") },
                )
            runtime.resume("stored")
            val active =
                activeSessionIds(
                    Json.parseToJsonElement("""{"sessions":[{"session_key":"runtime","status":"working"}]}"""),
                    runtime::storedId,
                )
            val resumed =
                AppState(selectedSession = "stored", conversations = mapOf("stored" to Conversation()))
                    .reconcileActivity(active, emptySet())
            assertEquals(setOf("stored"), resumed.liveSessions)
            assertTrue(resumed.threadUi().running)
            val prompt =
                resumed.conversations
                    .getValue("stored")
                    .submit("p", "next")
                    .prompts
                    .single()
            assertTrue(prompt.queued)
        }

    @Test
    fun `start or completion received during the snapshot wins over older gateway data`() {
        val started = reduceGatewayEvent(AppState(selectedSession = "a"), GatewayEvent.MessageStart("t", "a"))
        val afterStart = started.reconcileActivity(emptySet(), setOf("a"))
        assertTrue(afterStart.threadUi().running)
        assertEquals(setOf("a"), afterStart.liveSessions)
        val done = reduceGatewayEvent(afterStart, GatewayEvent.Done("a"))
        val afterDone = done.reconcileActivity(setOf("a"), setOf("a"))
        assertFalse(afterDone.threadUi().running)
        assertTrue(afterDone.liveSessions.isEmpty())
    }

    @Test
    fun `malformed active lists cannot turn a remembered run into an idle send`() {
        for (json in listOf("{}", "null", """{"sessions":[{}]}""", """{"sessions":[{"session_key":"s","status":null}]}""")) {
            assertFailsWith<IllegalStateException> {
                activeSessionIds(Json.parseToJsonElement(json)) { it }
            }
        }
    }
}
