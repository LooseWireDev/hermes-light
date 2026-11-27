package dev.loosewire.hermeslight.net

import dev.loosewire.hermeslight.AppState
import dev.loosewire.hermeslight.Conversation
import dev.loosewire.hermeslight.PromptStatus
import dev.loosewire.hermeslight.reduceGatewayEvent
import dev.loosewire.hermeslight.submit
import dev.loosewire.hermeslight.submitResult
import dev.loosewire.hermeslight.submitUnknown
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SessionRuntimeTest {
    @Test
    fun `opening resumes the stored tip then submit uses cached runtime separately per stored id`() =
        runBlocking {
            val resolved = Collections.synchronizedList(mutableListOf<String>())
            val sent = mutableListOf<JsonObject>()
            val resumed = mutableListOf<String>()
            val receipt = buildJsonObject { put("status", "accepted") }
            TestHttpServer { exchange ->
                resolved.add(exchange.requestURI.rawPath)
                val id = if (exchange.requestURI.path.contains("other")) "other/tip" else "live/tip"
                exchange.respondJson(200, """{"session_id":"$id"}""")
            }.use { server ->
                val api = Api(newHttpClient())
                val runtime =
                    SessionRuntime(
                        resolve = { api.latestDescendant(Endpoint.parse(server.baseUrl), it) },
                        resumeSession = {
                            resumed.add(it)
                            when (it) {
                                "live/tip" -> "runtime/current"
                                "other/tip" -> "runtime/other"
                                else -> error("Unexpected stored tip")
                            }
                        },
                        request = {
                            sent.add(it)
                            receipt
                        },
                    )
                assertEquals("runtime/current", runtime.resume("stored/root"))
                val result = runtime.submit("stored/root", "hello", queued = false)
                val accepted = Conversation().submit("p1", "hello").submitResult("p1", result)
                assertEquals(PromptStatus.ACCEPTED, accepted.prompts.single().status)
                assertFalse(accepted.running)
                runtime.submit("stored/root", "next", queued = true)
                runtime.submit("other/root", "other", queued = false)
                assertEquals(
                    listOf("/api/sessions/stored%2Froot/latest-descendant", "/api/sessions/other%2Froot/latest-descendant"),
                    resolved.toList(),
                )
                assertEquals(listOf("live/tip", "other/tip"), resumed)
                assertEquals(
                    listOf("runtime/current", "runtime/current", "runtime/other"),
                    sent.map { it["session_id"]?.jsonPrimitive?.content },
                )
                assertEquals("hello", sent[0]["text"]?.jsonPrimitive?.content)
                assertFalse("queued" in sent[0])
                assertEquals("true", sent[1]["queued"]?.jsonPrimitive?.content)
                assertEquals("stored/root", runtime.storedId("runtime/current"))
                assertEquals("runtime/current", runtime.runtimeId("stored/root"))
                val event = GatewayEvent.MessageStart("turn", "runtime/current")
                val started = reduceGatewayEvent(AppState(), event, runtime.storedId(event.sessionId!!))
                val streamed =
                    reduceGatewayEvent(
                        started,
                        GatewayEvent.MessageDelta("answer", "runtime/current"),
                        runtime.storedId("runtime/current"),
                    )
                assertEquals(setOf("stored/root"), streamed.liveSessions)
                assertEquals(
                    "answer",
                    streamed.conversations
                        .getValue("stored/root")
                        .stream
                        ?.text,
                )
                assertFalse("runtime/current" in streamed.conversations)
            }
        }

    @Test
    fun `4001 resolves and resumes original stored id once then retries queued text unchanged`() =
        runBlocking {
            val resolved = mutableListOf<String>()
            val resumed = mutableListOf<String>()
            val order = mutableListOf<String>()
            val sent = mutableListOf<JsonObject>()
            val receipt = buildJsonObject { put("status", "queued") }
            val runtime =
                SessionRuntime(
                    resolve = {
                        order.add("descendant")
                        resolved.add(it)
                        "stored/tip"
                    },
                    resumeSession = {
                        order.add("resume")
                        resumed.add(it)
                        if (resumed.size == 1) "old/runtime" else "new/runtime"
                    },
                    request = {
                        order.add("submit")
                        sent.add(it)
                        if (sent.size == 1) throw notFound()
                        receipt
                    },
                )
            val result = runtime.submit("stored/root", "next", queued = true)
            val queued = Conversation(running = true).submit("p1", "next").submitResult("p1", result)
            assertEquals(PromptStatus.QUEUED, queued.prompts.single().status)
            assertEquals("", queued.composer)
            assertEquals(listOf("stored/root", "stored/root"), resolved)
            assertEquals(listOf("stored/tip", "stored/tip"), resumed)
            assertEquals(listOf("descendant", "resume", "submit", "descendant", "resume", "submit"), order)
            assertEquals(listOf("old/runtime", "new/runtime"), sent.map { it["session_id"]?.jsonPrimitive?.content })
            assertTrue(sent.all { it["text"]?.jsonPrimitive?.content == "next" && it["queued"]?.jsonPrimitive?.content == "true" })
            assertEquals("new/runtime", runtime.runtimeId("stored/root"))
            assertEquals("stored/root", runtime.storedId("old/runtime"))
            assertEquals("stored/root", runtime.storedId("new/runtime"))
        }

    @Test
    fun `second 4001 stops with specific rejection and restores the draft`() =
        runBlocking {
            var resolutions = 0
            var submits = 0
            val runtime =
                SessionRuntime(
                    resolve = {
                        resolutions++
                        "live/current"
                    },
                    resumeSession = { "runtime/current" },
                    request = {
                        submits++
                        throw notFound()
                    },
                )
            val failure = assertFailsWith<GatewayRpcException> { runtime.submit("stored/root", "hello", queued = false) }
            assertEquals(2, resolutions)
            assertEquals(2, submits)
            assertEquals(RpcFailureKind.REJECTED, failure.kind)
            assertEquals(4001, failure.code)
            assertTrue(failure.message.orEmpty().contains("Session not found after resuming again"))
            val rejected = Conversation().submit("p1", "hello").submitResult("p1", null, failure.message)
            assertEquals("hello", rejected.composer)
            assertEquals(PromptStatus.FAILED, rejected.prompts.single().status)
        }

    @Test
    fun `unknown outcomes and unrelated refusals never refresh or resend`() =
        runBlocking {
            val failures =
                listOf(
                    GatewayRpcException("outcome unknown", RpcFailureKind.TIMEOUT),
                    GatewayRpcException("outcome unknown", RpcFailureKind.CONNECTION_LOST),
                    GatewayRpcException("not connected", RpcFailureKind.NOT_CONNECTED),
                    GatewayRpcException("invalid fields", RpcFailureKind.REJECTED, 4001),
                    GatewayRpcException("denied", RpcFailureKind.REJECTED, 403),
                )
            for (failure in failures) {
                var resolutions = 0
                var submits = 0
                val runtime =
                    SessionRuntime(
                        resolve = {
                            resolutions++
                            "live/current"
                        },
                        resumeSession = { "runtime/current" },
                        request = {
                            submits++
                            throw failure
                        },
                    )
                assertSame(failure, assertFailsWith<GatewayRpcException> { runtime.submit("stored", "hello", queued = false) })
                assertEquals(1, resolutions)
                assertEquals(1, submits)
                if (failure.kind == RpcFailureKind.TIMEOUT || failure.kind == RpcFailureKind.CONNECTION_LOST) {
                    val unknown = Conversation().submit("p1", "hello").submitUnknown("p1", failure.message)
                    assertEquals(PromptStatus.UNKNOWN, unknown.prompts.single().status)
                    assertEquals("", unknown.composer)
                }
            }
        }

    @Test
    fun `failed resolution never submits the stored id`() =
        runBlocking {
            var submits = 0
            val failure = ApiException("resolve failed", ApiErrorKind.NETWORK)
            val runtime =
                SessionRuntime(
                    resolve = { throw failure },
                    resumeSession = { error("Resume must not run after failed resolution") },
                    request = {
                        submits++
                        null
                    },
                )
            assertSame(failure, assertFailsWith<ApiException> { runtime.submit("stored/root", "hello", queued = false) })
            assertEquals(0, submits)
        }

    @Test
    fun `resume failure keeps prompt unsent with specific error and restored draft`() =
        runBlocking {
            val failures =
                listOf(
                    GatewayRpcException("session.resume rejected (code 4007): session not found", RpcFailureKind.REJECTED, 4007),
                    GatewayRpcException("session.resume timed out; outcome unknown", RpcFailureKind.TIMEOUT),
                )
            for (failure in failures) {
                var submits = 0
                val runtime =
                    SessionRuntime(
                        resolve = { "stored/tip" },
                        resumeSession = { throw failure },
                        request = {
                            submits++
                            null
                        },
                    )
                val error = assertFailsWith<IllegalStateException> { runtime.submit("stored/root", "hello", queued = false) }
                assertEquals("Session resume failed; prompt was not sent: ${failure.message}", error.message)
                assertSame(failure, error.cause)
                assertEquals(0, submits)
                val rejected = Conversation().submit("p1", "hello").submitResult("p1", null, error.message)
                assertEquals("hello", rejected.composer)
                assertEquals(PromptStatus.FAILED, rejected.prompts.single().status)
                assertEquals(error.message, rejected.prompts.single().error)
            }
        }

    @Test
    fun `failed resume after 4001 does not resend the prompt`() =
        runBlocking {
            var resumes = 0
            var submits = 0
            val runtime =
                SessionRuntime(
                    resolve = { "stored/tip" },
                    resumeSession = {
                        resumes++
                        if (resumes == 2) throw GatewayRpcException("denied", RpcFailureKind.REJECTED, 403)
                        "runtime/first"
                    },
                    request = {
                        submits++
                        throw notFound()
                    },
                )
            val error = assertFailsWith<IllegalStateException> { runtime.submit("stored/root", "hello", queued = false) }
            assertEquals("Session resume failed; prompt was not sent: denied", error.message)
            assertEquals(2, resumes)
            assertEquals(1, submits)
        }

    @Test
    fun `opening and first submit share an in-flight resume`() =
        runBlocking {
            val resuming = CompletableDeferred<Unit>()
            val ready = CompletableDeferred<Unit>()
            var resumes = 0
            val sent = mutableListOf<JsonObject>()
            val runtime =
                SessionRuntime(
                    resolve = { "stored/tip" },
                    resumeSession = {
                        resumes++
                        resuming.complete(Unit)
                        ready.await()
                        "runtime/current"
                    },
                    request = {
                        sent.add(it)
                        buildJsonObject { put("status", "accepted") }
                    },
                )
            val opening = async { runtime.resume("stored/root") }
            resuming.await()
            val submitting = async(start = CoroutineStart.UNDISPATCHED) { runtime.submit("stored/root", "hello", queued = false) }
            ready.complete(Unit)
            assertEquals("runtime/current", opening.await())
            val accepted = Conversation().submit("p1", "hello").submitResult("p1", submitting.await())
            assertEquals(PromptStatus.ACCEPTED, accepted.prompts.single().status)
            assertEquals(1, resumes)
            assertEquals("runtime/current", sent.single()["session_id"]?.jsonPrimitive?.content)
        }
}

private fun notFound() = GatewayRpcException("prompt.submit rejected (code 4001): session not found", RpcFailureKind.REJECTED, 4001)
