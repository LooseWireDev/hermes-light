package dev.loosewire.hermeslight.net

import dev.loosewire.hermeslight.Conversation
import dev.loosewire.hermeslight.PromptStatus
import dev.loosewire.hermeslight.submit
import dev.loosewire.hermeslight.submitResult
import dev.loosewire.hermeslight.submitUnknown
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
    fun `resolve then submit uses unencoded descendant and caches separately per stored id`() =
        runBlocking {
            val resolved = Collections.synchronizedList(mutableListOf<String>())
            val sent = mutableListOf<JsonObject>()
            val receipt = buildJsonObject { put("status", "accepted") }
            TestHttpServer { exchange ->
                resolved.add(exchange.requestURI.rawPath)
                val id = if (exchange.requestURI.path.contains("other")) "other/current" else "live/current"
                exchange.respondJson(200, """{"session_id":"$id"}""")
            }.use { server ->
                val api = Api(newHttpClient())
                val runtime =
                    SessionRuntime(
                        resolve = { api.latestDescendant(Endpoint.parse(server.baseUrl), it) },
                        request = {
                            sent.add(it)
                            receipt
                        },
                    )
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
                assertEquals(listOf("live/current", "live/current", "other/current"), sent.map { it["session_id"]?.jsonPrimitive?.content })
                assertEquals("hello", sent[0]["text"]?.jsonPrimitive?.content)
                assertFalse("queued" in sent[0])
                assertEquals("true", sent[1]["queued"]?.jsonPrimitive?.content)
                assertEquals("stored/root", runtime.storedId("live/current"))
                assertEquals("live/current", runtime.runtimeId("stored/root"))
            }
        }

    @Test
    fun `4001 refreshes original stored id once and retries queued text unchanged`() =
        runBlocking {
            val resolved = mutableListOf<String>()
            val sent = mutableListOf<JsonObject>()
            val receipt = buildJsonObject { put("status", "queued") }
            val runtime =
                SessionRuntime(
                    resolve = {
                        resolved.add(it)
                        if (resolved.size == 1) "old/current" else "new/current"
                    },
                    request = {
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
            assertEquals(listOf("old/current", "new/current"), sent.map { it["session_id"]?.jsonPrimitive?.content })
            assertTrue(sent.all { it["text"]?.jsonPrimitive?.content == "next" && it["queued"]?.jsonPrimitive?.content == "true" })
            assertEquals("new/current", runtime.runtimeId("stored/root"))
            assertEquals("stored/root", runtime.storedId("old/current"))
            assertEquals("stored/root", runtime.storedId("new/current"))
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
            assertTrue(failure.message.orEmpty().contains("Session not found after refreshing"))
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
                    request = {
                        submits++
                        null
                    },
                )
            assertSame(failure, assertFailsWith<ApiException> { runtime.submit("stored/root", "hello", queued = false) })
            assertEquals(0, submits)
        }
}

private fun notFound() = GatewayRpcException("prompt.submit rejected (code 4001): session not found", RpcFailureKind.REJECTED, 4001)
