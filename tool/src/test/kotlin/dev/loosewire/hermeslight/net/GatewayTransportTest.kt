/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Tests for JSON-RPC command correlation and honest failures.
 */
package dev.loosewire.hermeslight.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayTransportTest {
    @Test
    fun `request correlates out-of-order replies by id and keeps server requests off`() =
        runBlocking {
            val frames = LinkedBlockingQueue<String>()
            val server =
                TestWebSocketServer { ws, _ ->
                    ws.sendText(readyFrame())
                    val capabilities = ws.awaitText() ?: error("missing capabilities")
                    frames.put(capabilities)
                    ws.sendText(resultFrame(idOf(capabilities), "{}"))
                    val first = ws.awaitText() ?: error("missing first command")
                    val second = ws.awaitText() ?: error("missing second command")
                    // Reverse order proves the reply is matched by id, not arrival order.
                    ws.sendText(resultFrame(idOf(second), """{"which":"second"}"""))
                    ws.sendText(resultFrame(idOf(first), """{"which":"first"}"""))
                    ws.awaitText(500)
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                server.use {
                    val listener = RecordingListener()
                    val gateway = Gateway(scope, listener, newHttpClient(), fastConfig())
                    gateway.connect(connection(server))
                    listener.awaitConnected()

                    val first =
                        async(Dispatchers.Default) {
                            gateway.request("prompt.submit", params("""{"session_id":"s1","text":"hello"}"""))
                        }
                    val second =
                        async(Dispatchers.Default) {
                            gateway.request("prompt.cancel", params("""{"session_id":"s1","admission_id":"a-1"}"""))
                        }
                    val results = withTimeout(TEST_TIMEOUT) { listOf(first.await(), second.await()) }
                    assertEquals(
                        "first",
                        results[0]
                            ?.jsonObject
                            ?.get("which")
                            ?.jsonPrimitive
                            ?.content,
                    )
                    assertEquals(
                        "second",
                        results[1]
                            ?.jsonObject
                            ?.get("which")
                            ?.jsonPrimitive
                            ?.content,
                    )

                    val capabilities = params(frames.poll(2, TimeUnit.SECONDS) ?: error("no capabilities frame"))
                    assertEquals("client.capabilities", capabilities.getValue("method").jsonPrimitive.content)
                    val capabilityParams = capabilities.getValue("params").jsonObject
                    assertEquals("false", capabilityParams.getValue("server_requests").jsonPrimitive.content)
                    assertEquals(
                        "hermes-light",
                        capabilityParams
                            .getValue("client")
                            .jsonObject
                            .getValue("name")
                            .jsonPrimitive
                            .content,
                    )
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a JSON-RPC rejection surfaces the code and never echoes params`() =
        runBlocking {
            val server =
                TestWebSocketServer { ws, _ ->
                    ws.sendText(readyFrame())
                    val capabilities = ws.awaitText() ?: error("missing capabilities")
                    ws.sendText(resultFrame(idOf(capabilities), "{}"))
                    val command = ws.awaitText() ?: error("missing command")
                    ws.sendText(errorFrame(idOf(command), -32602, "invalid params"))
                    ws.awaitText(500)
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                server.use {
                    val listener = RecordingListener()
                    val gateway = Gateway(scope, listener, newHttpClient(), fastConfig())
                    gateway.connect(connection(server))
                    listener.awaitConnected()

                    val failure =
                        assertFailsWith<GatewayRpcException> {
                            gateway.request(
                                "prompt.submit",
                                params("""{"session_id":"s1","text":"do not echo me"}"""),
                            )
                        }
                    assertEquals(RpcFailureKind.REJECTED, failure.kind)
                    assertEquals(-32602, failure.code)
                    assertTrue(failure.message.orEmpty().contains("prompt.submit"))
                    assertTrue(failure.message.orEmpty().contains("invalid params"))
                    assertFalse(failure.message.orEmpty().contains("do not echo me"))
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `resumeSession resumes on the desktop surface and returns the reply's runtime id`() =
        runBlocking {
            val commands = LinkedBlockingQueue<String>()
            val server =
                TestWebSocketServer { ws, _ ->
                    ws.sendText(readyFrame())
                    val capabilities = ws.awaitText() ?: error("missing capabilities")
                    ws.sendText(resultFrame(idOf(capabilities), "{}"))
                    val command = ws.awaitText() ?: error("missing resume command")
                    commands.put(command)
                    ws.sendText(resultFrame(idOf(command), """{"session_id":"runtime-9","messages":[]}"""))
                    ws.awaitText(500)
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                server.use {
                    val listener = RecordingListener()
                    val gateway = Gateway(scope, listener, newHttpClient(), fastConfig())
                    gateway.connect(connection(server))
                    listener.awaitConnected()

                    val runtimeId = withTimeout(TEST_TIMEOUT) { gateway.resumeSession("stored-tip") }
                    assertEquals("runtime-9", runtimeId)
                    assertNotEquals("stored-tip", runtimeId)

                    val frame = params(commands.poll(2, TimeUnit.SECONDS) ?: error("no resume command"))
                    assertEquals("session.resume", frame.getValue("method").jsonPrimitive.content)
                    val resume = frame.getValue("params").jsonObject
                    assertEquals("stored-tip", resume.getValue("session_id").jsonPrimitive.content)
                    assertEquals("desktop", resume.getValue("source").jsonPrimitive.content)
                    assertEquals("true", resume.getValue("omit_messages").jsonPrimitive.content)
                    assertFalse(resume.containsKey("profile"))
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `resumeSession forwards a nonblank profile and omits a blank one`() =
        runBlocking {
            val commands = LinkedBlockingQueue<String>()
            val server =
                TestWebSocketServer { ws, _ ->
                    ws.sendText(readyFrame())
                    val capabilities = ws.awaitText() ?: error("missing capabilities")
                    ws.sendText(resultFrame(idOf(capabilities), "{}"))
                    repeat(2) { index ->
                        val command = ws.awaitText() ?: error("missing resume command")
                        commands.put(command)
                        ws.sendText(resultFrame(idOf(command), """{"session_id":"runtime-${index + 1}"}"""))
                    }
                    ws.awaitText(500)
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                server.use {
                    val listener = RecordingListener()
                    val gateway = Gateway(scope, listener, newHttpClient(), fastConfig())
                    gateway.connect(connection(server))
                    listener.awaitConnected()

                    assertEquals(
                        "runtime-1",
                        withTimeout(TEST_TIMEOUT) { gateway.resumeSession("stored-tip", "work") },
                    )
                    assertEquals(
                        "runtime-2",
                        withTimeout(TEST_TIMEOUT) { gateway.resumeSession("stored-tip", "   ") },
                    )

                    val withProfile = commandParams(commands.poll(2, TimeUnit.SECONDS) ?: error("no first resume"))
                    val blankProfile = commandParams(commands.poll(2, TimeUnit.SECONDS) ?: error("no second resume"))
                    assertEquals("work", withProfile.getValue("profile").jsonPrimitive.content)
                    assertFalse(blankProfile.containsKey("profile"))
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `resumeSession fails without a nonblank runtime id and never falls back to the stored tip`() =
        runBlocking {
            val server =
                TestWebSocketServer { ws, _ ->
                    ws.sendText(readyFrame())
                    val capabilities = ws.awaitText() ?: error("missing capabilities")
                    ws.sendText(resultFrame(idOf(capabilities), "{}"))
                    val replies =
                        listOf(
                            """{"stored_session_id":"stored-tip"}""",
                            """{"session_id":17}""",
                            """{"session_id":"   "}""",
                        )
                    for (reply in replies) {
                        val command = ws.awaitText() ?: error("missing resume command")
                        ws.sendText(resultFrame(idOf(command), reply))
                    }
                    ws.awaitText(500)
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                server.use {
                    val listener = RecordingListener()
                    val gateway = Gateway(scope, listener, newHttpClient(), fastConfig())
                    gateway.connect(connection(server))
                    listener.awaitConnected()

                    repeat(3) {
                        val failure =
                            assertFailsWith<GatewayRpcException> {
                                withTimeout(TEST_TIMEOUT) { gateway.resumeSession("stored-tip") }
                            }
                        assertEquals(RpcFailureKind.REJECTED, failure.kind)
                        assertNull(failure.code)
                        assertTrue(failure.message.orEmpty().contains("session.resume"))
                        assertTrue(failure.message.orEmpty().contains("session_id"))
                        assertFalse(failure.message.orEmpty().contains("stored-tip"))
                    }
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `resumeSession passes a dashboard rejection through unchanged`() =
        runBlocking {
            val server =
                TestWebSocketServer { ws, _ ->
                    ws.sendText(readyFrame())
                    val capabilities = ws.awaitText() ?: error("missing capabilities")
                    ws.sendText(resultFrame(idOf(capabilities), "{}"))
                    val command = ws.awaitText() ?: error("missing resume command")
                    ws.sendText(errorFrame(idOf(command), 4001, "session not found"))
                    ws.awaitText(500)
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                server.use {
                    val listener = RecordingListener()
                    val gateway = Gateway(scope, listener, newHttpClient(), fastConfig())
                    gateway.connect(connection(server))
                    listener.awaitConnected()

                    val failure =
                        assertFailsWith<GatewayRpcException> {
                            withTimeout(TEST_TIMEOUT) { gateway.resumeSession("stored-tip") }
                        }
                    assertEquals(RpcFailureKind.REJECTED, failure.kind)
                    assertEquals(4001, failure.code)
                    assertTrue(failure.message.orEmpty().contains("session.resume"))
                    assertTrue(failure.message.orEmpty().contains("session not found"))
                    assertFalse(failure.message.orEmpty().contains("stored-tip"))
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `rpc failure logging carries method code and message but redacts credentials`() {
        val failure =
            GatewayRpcException(
                "Dashboard rejected session.resume (code 4001): token=super-secret&ticket=t-9 session not found",
                RpcFailureKind.REJECTED,
                code = 4001,
            )

        val line = rpcFailureLogLine("session.resume", failure)

        assertTrue(line.contains("session.resume"))
        assertTrue(line.contains("4001"))
        assertTrue(line.contains("session not found"))
        assertFalse(line.contains("super-secret"))
        assertFalse(line.contains("t-9"))
        assertFalse(line.contains("session_id"))
    }

    @Test
    fun `rpc failure logs redact raw and JSON escaped bodies plus credential formats`() {
        val text = "private \"prompt\"\nbody token=inner-secret tail"
        val answer = "private clarification"
        val params =
            buildJsonObject {
                put("text", text)
                put("answers", JsonArray(listOf(JsonPrimitive(answer), JsonPrimitive("TOKEN"))))
            }
        val failure =
            GatewayRpcException(
                "invalid params: $text encoded=${JsonPrimitive(text)} answer=$answer " +
                    """{"TOKEN":"json-secret","ticket":"json-ticket"} Authorization: bEaReR bearer-secret bare-live-token""",
                RpcFailureKind.REJECTED,
                code = 4001,
            )

        val line = rpcFailureLogLine("prompt.submit", failure, params, credential = "bare-live-token")

        assertTrue(line.contains("prompt.submit"))
        assertTrue(line.contains("code=4001"))
        assertTrue(line.contains("invalid params"))
        for (sensitive in listOf(text, "private", "inner-secret", "json-secret", "json-ticket", "bearer-secret", "bare-live-token")) {
            assertFalse(line.contains(sensitive), "Sensitive request or credential value reached the log")
        }
    }

    @Test
    fun `a closed socket fails the command as outcome unknown and never replays it`() =
        runBlocking {
            val commands = LinkedBlockingQueue<String>()
            val reconnects = LinkedBlockingQueue<String>()
            val server =
                TestWebSocketServer(connections = 2) { ws, index ->
                    ws.sendText(readyFrame())
                    val capabilities = ws.awaitText() ?: error("missing capabilities")
                    ws.sendText(resultFrame(idOf(capabilities), "{}"))
                    if (index == 1) {
                        commands.put(ws.awaitText() ?: error("missing command"))
                        ws.close()
                    } else {
                        reconnects.put(capabilities)
                        ws.awaitText(1_000)
                    }
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                server.use {
                    val listener = RecordingListener()
                    val gateway = Gateway(scope, listener, newHttpClient(), fastConfig())
                    gateway.connect(connection(server))
                    listener.awaitConnected()

                    val pending =
                        async(Dispatchers.Default) {
                            runCatching {
                                gateway.request("prompt.submit", params("""{"session_id":"s1","text":"hello"}"""))
                            }
                        }
                    assertNotNull(commands.poll(2, TimeUnit.SECONDS), "the command was never sent")

                    val failure =
                        withTimeout(TEST_TIMEOUT) { pending.await() }
                            .exceptionOrNull() as? GatewayRpcException
                            ?: error("expected a GatewayRpcException")
                    assertEquals(RpcFailureKind.CONNECTION_LOST, failure.kind)
                    assertTrue(failure.message.orEmpty().contains("outcome unknown"))

                    assertNotNull(reconnects.poll(2, TimeUnit.SECONDS), "the gateway did not reconnect")
                    assertNull(
                        commands.poll(400, TimeUnit.MILLISECONDS),
                        "a command must never be replayed after a reconnect",
                    )
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `backgrounding fails an outstanding command as outcome unknown`() =
        runBlocking {
            val commands = LinkedBlockingQueue<String>()
            val server =
                TestWebSocketServer { ws, _ ->
                    ws.sendText(readyFrame())
                    val capabilities = ws.awaitText() ?: error("missing capabilities")
                    ws.sendText(resultFrame(idOf(capabilities), "{}"))
                    commands.put(ws.awaitText() ?: error("missing command"))
                    ws.awaitText(2_000)
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                server.use {
                    val listener = RecordingListener()
                    val gateway = Gateway(scope, listener, newHttpClient(), fastConfig())
                    gateway.connect(connection(server))
                    listener.awaitConnected()

                    val pending =
                        async(Dispatchers.Default) {
                            runCatching {
                                gateway.request("session.interrupt", params("""{"session_id":"s1"}"""))
                            }
                        }
                    assertNotNull(commands.poll(2, TimeUnit.SECONDS), "the command was never sent")

                    gateway.onForeground(false)
                    val failure =
                        withTimeout(TEST_TIMEOUT) { pending.await() }
                            .exceptionOrNull() as? GatewayRpcException
                            ?: error("expected a GatewayRpcException")
                    assertEquals(RpcFailureKind.CONNECTION_LOST, failure.kind)
                    assertTrue(failure.message.orEmpty().contains("background"))
                    assertTrue(failure.message.orEmpty().contains("outcome unknown"))
                    assertNull(commands.poll(300, TimeUnit.MILLISECONDS), "no command may be resent")
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a request times out honestly and a late reply cannot answer the next command`() =
        runBlocking {
            val server =
                TestWebSocketServer { ws, _ ->
                    ws.sendText(readyFrame())
                    val capabilities = ws.awaitText() ?: error("missing capabilities")
                    ws.sendText(resultFrame(idOf(capabilities), "{}"))
                    val timedOut = ws.awaitText() ?: error("missing first command")
                    // Let the caller time out, then publish a late reply for the abandoned id.
                    ws.pause(250)
                    ws.sendText(resultFrame(idOf(timedOut), """{"late":true}"""))
                    val next = ws.awaitText() ?: error("missing second command")
                    ws.sendText(resultFrame(idOf(next), """{"which":"next"}"""))
                    ws.awaitText(500)
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                server.use {
                    val listener = RecordingListener()
                    val gateway = Gateway(scope, listener, newHttpClient(), fastConfig())
                    gateway.connect(connection(server))
                    listener.awaitConnected()

                    val failure =
                        assertFailsWith<GatewayRpcException> {
                            gateway.request(
                                "session.interrupt",
                                params("""{"session_id":"s1"}"""),
                                timeoutMillis = 150,
                            )
                        }
                    assertEquals(RpcFailureKind.TIMEOUT, failure.kind)
                    assertTrue(failure.message.orEmpty().contains("timed out"))
                    assertTrue(failure.message.orEmpty().contains("outcome unknown"))

                    val result =
                        gateway.request(
                            "session.interrupt",
                            params("""{"session_id":"s1"}"""),
                            timeoutMillis = TEST_TIMEOUT,
                        )
                    assertEquals(
                        "next",
                        result
                            ?.jsonObject
                            ?.get("which")
                            ?.jsonPrimitive
                            ?.content,
                    )
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a stale generation cannot answer a command on the new connection`() =
        runBlocking {
            val firstCommandId = LinkedBlockingQueue<String>()
            val secondCommandId = LinkedBlockingQueue<String>()
            val secondConnected = LinkedBlockingQueue<Unit>()
            val server =
                TestWebSocketServer(connections = 2) { ws, index ->
                    ws.sendText(readyFrame())
                    val capabilities = ws.awaitText() ?: error("missing capabilities")
                    ws.sendText(resultFrame(idOf(capabilities), "{}"))
                    if (index == 1) {
                        firstCommandId.put(idOf(ws.awaitText() ?: error("missing first command")))
                        val staleId = secondCommandId.poll(2, TimeUnit.SECONDS)
                        if (staleId != null) {
                            runCatching { ws.sendText(resultFrame(staleId, """{"stale":true}""")) }
                        }
                        ws.awaitText(300)
                    } else {
                        secondConnected.put(Unit)
                        val command = ws.awaitText() ?: error("missing second command")
                        secondCommandId.put(idOf(command))
                        // Give the replaced socket a chance to deliver its stale reply first.
                        ws.pause(200)
                        ws.sendText(resultFrame(idOf(command), """{"real":true}"""))
                        ws.awaitText(500)
                    }
                }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                server.use {
                    val listener = RecordingListener()
                    val gateway = Gateway(scope, listener, newHttpClient(), fastConfig())
                    gateway.connect(connection(server))
                    listener.awaitConnected()

                    val pending =
                        async(Dispatchers.Default) {
                            runCatching {
                                gateway.request("prompt.submit", params("""{"session_id":"s1","text":"hello"}"""))
                            }
                        }
                    assertNotNull(firstCommandId.poll(2, TimeUnit.SECONDS), "the first command was never sent")

                    // Replacing the connection fails the old call; its socket generation dies with it.
                    gateway.connect(connection(server))
                    val failure =
                        withTimeout(TEST_TIMEOUT) { pending.await() }
                            .exceptionOrNull() as? GatewayRpcException
                            ?: error("expected a GatewayRpcException")
                    assertEquals(RpcFailureKind.CONNECTION_LOST, failure.kind)
                    assertTrue(failure.message.orEmpty().contains("replaced"))
                    assertTrue(failure.message.orEmpty().contains("outcome unknown"))

                    assertNotNull(secondConnected.poll(2, TimeUnit.SECONDS), "the replacement never connected")
                    val result =
                        gateway.request(
                            "session.interrupt",
                            params("""{"session_id":"s1"}"""),
                            timeoutMillis = TEST_TIMEOUT,
                        )
                    assertEquals(
                        "true",
                        result
                            ?.jsonObject
                            ?.get("real")
                            ?.jsonPrimitive
                            ?.content,
                    )
                }
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun `a request without a live connection is never sent`() =
        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val gateway = Gateway(scope, RecordingListener(), newHttpClient(), fastConfig())
                val failure =
                    assertFailsWith<GatewayRpcException> {
                        gateway.request("prompt.submit", params("""{"session_id":"s1","text":"hello"}"""))
                    }
                assertEquals(RpcFailureKind.NOT_CONNECTED, failure.kind)
            } finally {
                scope.cancel()
            }
        }

    private class RecordingListener : Gateway.Listener {
        private val states = LinkedBlockingQueue<GatewayState>()

        fun awaitConnected() {
            while (true) {
                val state = states.poll(2, TimeUnit.SECONDS) ?: error("the gateway never reported Connected")
                if (state == GatewayState.Connected) return
                if (state is GatewayState.AuthFailed) error(state.message)
            }
        }

        override fun onState(state: GatewayState) {
            states.put(state)
        }

        override fun onEvent(event: GatewayEvent) = Unit
    }

    private companion object {
        const val TEST_TIMEOUT: Long = 4_000L

        fun fastConfig() =
            GatewayConfig(
                initialBackoffMillis = 50L,
                maxBackoffMillis = 200L,
                handshakeTimeoutMillis = 2_000L,
            )

        fun connection(server: TestWebSocketServer) =
            GatewayConnection(
                endpoint = Endpoint.parse(server.baseUrl),
                authParam = WsAuthParam.TOKEN,
                credential = "test-credential",
            )

        fun readyFrame(): String = """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}"""

        fun idOf(frame: String): String =
            hermesJson
                .parseToJsonElement(frame)
                .jsonObject
                .getValue("id")
                .jsonPrimitive
                .content

        fun resultFrame(
            id: String,
            result: String,
        ): String = """{"jsonrpc":"2.0","id":"$id","result":$result}"""

        fun errorFrame(
            id: String,
            code: Int,
            message: String,
        ): String = """{"jsonrpc":"2.0","id":"$id","error":{"code":$code,"message":"$message"}}"""

        fun params(text: String): JsonObject = hermesJson.parseToJsonElement(text).jsonObject

        fun commandParams(frame: String): JsonObject = params(frame).getValue("params").jsonObject
    }
}
