/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Tests for the sessions REST family.
 */
package dev.loosewire.hermeslight.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTest {
    @Test
    fun `audio transcription posts WAV data with proxy prefix and bearer auth`() =
        runBlocking {
            val dataUrl = "data:audio/wav;base64,UklGRg=="
            TestHttpServer { exchange ->
                assertEquals("/dash/api/audio/transcribe", exchange.requestURI.path)
                assertEquals("Bearer voice-token", exchange.requestHeaders.getFirst("Authorization"))
                val contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
                assertTrue(contentType.startsWith("application/json"))
                val payload = hermesJson.parseToJsonElement(exchange.bodyText()).jsonObject
                assertEquals(dataUrl, payload["data_url"]?.jsonPrimitive?.content)
                assertEquals("audio/wav", payload["mime_type"]?.jsonPrimitive?.content)
                exchange.respondJson(200, """{"transcript":" hello world ","extra":true}""")
            }.use { server ->
                val api = Api(newHttpClient(), tokenProvider = { "voice-token" })
                assertEquals("hello world", api.transcribe(Endpoint.parse("${server.baseUrl}dash/"), dataUrl))
            }
        }

    @Test
    fun `audio transcription shares password session cookie and accepts empty speech`() =
        runBlocking {
            TestHttpServer { exchange ->
                if (exchange.requestURI.path == "/api/cron/jobs") {
                    exchange.respondJson(200, "[]", mapOf("Set-Cookie" to "hermes_session=voice-session; Path=/; HttpOnly"))
                } else {
                    assertEquals("hermes_session=voice-session", exchange.requestHeaders.getFirst("Cookie"))
                    assertNull(exchange.requestHeaders.getFirst("Authorization"))
                    exchange.respondJson(200, """{"transcript":""}""")
                }
            }.use { server ->
                val api = Api(newHttpClient())
                val endpoint = Endpoint.parse(server.baseUrl)
                api.cronJobs(endpoint)
                assertEquals("", api.transcribe(endpoint, "data:audio/wav;base64,UklGRg=="))
            }
        }

    @Test
    fun `audio transcription rejects failed and malformed responses instead of treating them as silence`() =
        runBlocking {
            val cases =
                listOf(
                    Triple(503, "{}", ApiErrorKind.HTTP),
                    Triple(401, "{}", ApiErrorKind.UNAUTHORIZED),
                    Triple(200, "{}", ApiErrorKind.INVALID_RESPONSE),
                    Triple(200, """{"transcript":null}""", ApiErrorKind.INVALID_RESPONSE),
                )
            for ((status, body, kind) in cases) {
                TestHttpServer { it.respondJson(status, body) }.use { server ->
                    val api = Api(newHttpClient())
                    val failure =
                        assertFailsWith<ApiException> {
                            api.transcribe(Endpoint.parse(server.baseUrl), "data:audio/wav;base64,UklGRg==")
                        }
                    assertEquals(kind, failure.kind)
                }
            }
            val server = TestHttpServer { it.respondEmpty(503) }
            val endpoint = Endpoint.parse(server.baseUrl)
            server.close()
            val failure =
                assertFailsWith<ApiException> {
                    Api(newHttpClient()).transcribe(endpoint, "data:audio/wav;base64,UklGRg==")
                }
            assertEquals(ApiErrorKind.NETWORK, failure.kind)
        }

    @Test
    fun `cron menu snapshot preserves proxy prefix profile and authentication`() =
        runBlocking {
            TestHttpServer { exchange ->
                assertEquals("/dash/api/cron/jobs", exchange.requestURI.path)
                assertEquals("Bearer menu-token", exchange.requestHeaders.getFirst("Authorization"))
                assertEquals("june", queryParameters(exchange.requestURI)["profile"])
                exchange.respondJson(200, """[{"id":"job-1","name":"Morning","state":"paused","extra":true}]""")
            }.use { server ->
                val api = Api(newHttpClient(), tokenProvider = { "menu-token" })
                assertEquals(
                    listOf(CronJobSummary("job-1", "Morning", "paused")),
                    api.cronJobs(Endpoint.parse("${server.baseUrl}dash/"), "june"),
                )
            }
        }

    @Test
    fun `cron menu snapshot distinguishes no jobs from unavailable jobs`() =
        runBlocking {
            var status = 200
            TestHttpServer { exchange ->
                exchange.respondJson(status, "[]")
            }.use { server ->
                val api = Api(newHttpClient())
                val endpoint = Endpoint.parse(server.baseUrl)
                assertEquals(emptyList(), api.cronJobs(endpoint))
                status = 503
                assertEquals(503, assertFailsWith<ApiException> { api.cronJobs(endpoint) }.httpCode)
            }
        }

    @Test
    fun `session ids are single encoded path segments for every REST operation`() =
        runBlocking {
            val paths = Collections.synchronizedList(mutableListOf<String>())
            TestHttpServer { exchange ->
                paths.add(exchange.requestURI.rawPath)
                val body =
                    when {
                        exchange.requestURI.path.endsWith("/latest-descendant") -> """{"session_id":"current/leaf"}"""
                        exchange.requestURI.path.endsWith("/messages/around") -> """{"messages":[]}"""
                        exchange.requestURI.path.endsWith("/messages") -> """{"messages":[]}"""
                        else -> """{"session_id":"stored/branch %?#"}"""
                    }
                exchange.respondJson(200, body)
            }.use { server ->
                val api = Api(newHttpClient())
                val endpoint = Endpoint.parse("${server.baseUrl}dashboard/")
                val id = "stored/branch %?#"
                assertEquals(id, api.session(endpoint, id).id)
                api.messages(endpoint, id)
                api.messagesAround(endpoint, id, rowId = 1)
                assertEquals("current/leaf", api.latestDescendant(endpoint, id))
                val prefix = "/dashboard/api/sessions/stored%2Fbranch%20%25%3F%23"
                assertEquals(
                    listOf(prefix, "$prefix/messages", "$prefix/messages/around", "$prefix/latest-descendant"),
                    paths.toList(),
                )
            }
        }

    @Test
    fun `rename session reads the mutation snapshot and patches its encoded id with mixed counters`() =
        runBlocking<Unit> {
            val requests = Collections.synchronizedList(mutableListOf<String>())
            var patched: JsonObject? = null
            var snapshotAuthorization: String? = null
            var patchAuthorization: String? = null
            TestHttpServer { exchange ->
                requests.add("${exchange.method} ${exchange.requestURI.rawPath}")
                when {
                    exchange.requestURI.path.endsWith("/mutation-snapshot") -> {
                        snapshotAuthorization = exchange.requestHeaders.getFirst("Authorization")
                        exchange.respondJson(
                            200,
                            """{"session_id":"stored/branch %?#","exists":true,"runtime_revision":"41","runtime_generation":7}""",
                        )
                    }

                    exchange.method == "PATCH" -> {
                        patchAuthorization = exchange.requestHeaders.getFirst("Authorization")
                        val contentType = exchange.requestHeaders.getFirst("Content-Type").orEmpty()
                        assertTrue(contentType.startsWith("application/json"))
                        assertNull(exchange.requestURI.rawQuery)
                        patched = hermesJson.parseToJsonElement(exchange.bodyText()).jsonObject
                        exchange.respondEmpty(204)
                    }

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                Api(newHttpClient(), tokenProvider = { "session-token" }).renameSession(
                    endpoint = Endpoint.parse("${server.baseUrl}dash/"),
                    id = "stored/branch %?#",
                    title = "Renamed / title",
                )
                assertEquals(
                    listOf(
                        "GET /dash/api/sessions/stored%2Fbranch%20%25%3F%23/mutation-snapshot",
                        "PATCH /dash/api/sessions/stored%2Fbranch%20%25%3F%23",
                    ),
                    requests.toList(),
                )
            }
            assertEquals("Bearer session-token", snapshotAuthorization)
            assertEquals("Bearer session-token", patchAuthorization)
            val payload = checkNotNull(patched)
            assertEquals(setOf("title", "request_id", "expected_revision", "expected_generation"), payload.keys)
            assertEquals("Renamed / title", payload.getValue("title").jsonPrimitive.content)
            assertTrue(payload.getValue("expected_revision").jsonPrimitive.isString)
            assertEquals("41", payload.getValue("expected_revision").jsonPrimitive.content)
            assertFalse(payload.getValue("expected_generation").jsonPrimitive.isString)
            assertEquals("7", payload.getValue("expected_generation").jsonPrimitive.content)
            UUID.fromString(payload.getValue("request_id").jsonPrimitive.content)
        }

    @Test
    fun `delete session reads the mutation snapshot and sends fenced query counters`() =
        runBlocking<Unit> {
            val requests = Collections.synchronizedList(mutableListOf<String>())
            var observedQuery: Map<String, String> = emptyMap()
            TestHttpServer { exchange ->
                requests.add("${exchange.method} ${exchange.requestURI.rawPath}")
                when {
                    exchange.requestURI.path.endsWith("/mutation-snapshot") ->
                        exchange.respondJson(
                            200,
                            """{"session_id":"stored/branch %?#","exists":true,"runtime_revision":42,"runtime_generation":"9"}""",
                        )

                    exchange.method == "DELETE" -> {
                        observedQuery = queryParameters(exchange.requestURI)
                        assertEquals("", exchange.bodyText())
                        assertNull(exchange.requestHeaders.getFirst("Authorization"))
                        exchange.respondEmpty(204)
                    }

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                Api(newHttpClient()).deleteSession(
                    endpoint = Endpoint.parse("${server.baseUrl}dash/"),
                    id = "stored/branch %?#",
                )
                assertEquals(
                    listOf(
                        "GET /dash/api/sessions/stored%2Fbranch%20%25%3F%23/mutation-snapshot",
                        "DELETE /dash/api/sessions/stored%2Fbranch%20%25%3F%23",
                    ),
                    requests.toList(),
                )
            }
            assertEquals(setOf("request_id", "expected_revision", "expected_generation"), observedQuery.keys)
            assertEquals("42", observedQuery["expected_revision"])
            assertEquals("9", observedQuery["expected_generation"])
            UUID.fromString(observedQuery.getValue("request_id"))
        }

    @Test
    fun `session mutations propagate dashboard failures and never report success early`() =
        runBlocking {
            var mutationStatus = 500
            val requests = Collections.synchronizedList(mutableListOf<String>())
            TestHttpServer { exchange ->
                requests.add("${exchange.method} ${exchange.requestURI.rawPath}")
                when {
                    exchange.requestURI.path.endsWith("/mutation-snapshot") ->
                        exchange.respondJson(
                            200,
                            """{"exists":true,"runtime_revision":3,"runtime_generation":1}""",
                        )

                    exchange.method == "PATCH" || exchange.method == "DELETE" ->
                        exchange.respondEmpty(mutationStatus)

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val endpoint = Endpoint.parse(server.baseUrl)
                val api = Api(newHttpClient())

                val renameFailure =
                    assertFailsWith<ApiException> {
                        api.renameSession(endpoint, "s1", "New title")
                    }
                assertEquals(ApiErrorKind.HTTP, renameFailure.kind)
                assertEquals(500, renameFailure.httpCode)

                mutationStatus = 403
                val deleteFailure =
                    assertFailsWith<ApiException> {
                        api.deleteSession(endpoint, "s1")
                    }
                assertEquals(ApiErrorKind.UNAUTHORIZED, deleteFailure.kind)
                assertEquals(403, deleteFailure.httpCode)
                assertEquals(2, requests.toList().count { !it.startsWith("GET") })
            }
        }

    @Test
    fun `rename retries once with a fresh snapshot and request id after client_upgrade_required`() =
        runBlocking {
            val snapshots = Collections.synchronizedList(mutableListOf<Int>())
            val revisions = Collections.synchronizedList(mutableListOf<String>())
            val requestIds = Collections.synchronizedList(mutableListOf<String>())
            val patchCount = AtomicInteger(0)
            TestHttpServer { exchange ->
                when {
                    exchange.requestURI.path.endsWith("/mutation-snapshot") -> {
                        val revision = snapshots.size + 11
                        snapshots.add(revision)
                        exchange.respondJson(
                            200,
                            """{"exists":true,"runtime_revision":$revision,"runtime_generation":2}""",
                        )
                    }

                    exchange.method == "PATCH" -> {
                        val attempt = patchCount.incrementAndGet()
                        val payload = hermesJson.parseToJsonElement(exchange.bodyText()).jsonObject
                        requestIds.add(payload.getValue("request_id").jsonPrimitive.content)
                        revisions.add(payload.getValue("expected_revision").jsonPrimitive.content)
                        if (attempt == 1) {
                            exchange.respondJson(409, """{"detail":"client_upgrade_required"}""")
                        } else {
                            exchange.respondEmpty(204)
                        }
                    }

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                Api(newHttpClient()).renameSession(Endpoint.parse(server.baseUrl), "s1", "New title")
            }
            assertEquals(listOf(11, 12), snapshots.toList())
            assertEquals(2, patchCount.get())
            assertEquals(listOf("11", "12"), revisions.toList())
            assertNotEquals(requestIds[0], requestIds[1])
            requestIds.forEach { UUID.fromString(it) }
        }

    @Test
    fun `delete retries once after client_upgrade_required and keeps counters in the query`() =
        runBlocking {
            val snapshotCount = AtomicInteger(0)
            val deleteCount = AtomicInteger(0)
            val requestIds = Collections.synchronizedList(mutableListOf<String>())
            TestHttpServer { exchange ->
                when {
                    exchange.requestURI.path.endsWith("/mutation-snapshot") -> {
                        val revision = snapshotCount.incrementAndGet() + 20
                        exchange.respondJson(
                            200,
                            """{"exists":true,"runtime_revision":"$revision","runtime_generation":4}""",
                        )
                    }

                    exchange.method == "DELETE" -> {
                        val attempt = deleteCount.incrementAndGet()
                        requestIds.add(queryParameters(exchange.requestURI).getValue("request_id"))
                        if (attempt == 1) {
                            exchange.respondJson(409, """{"detail":{"code":"client_upgrade_required"}}""")
                        } else {
                            exchange.respondEmpty(204)
                        }
                    }

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                Api(newHttpClient()).deleteSession(Endpoint.parse(server.baseUrl), "s1")
            }
            assertEquals(2, snapshotCount.get())
            assertEquals(2, deleteCount.get())
            assertNotEquals(requestIds[0], requestIds[1])
            requestIds.forEach { UUID.fromString(it) }
        }

    @Test
    fun `two relevant conflicts report changed elsewhere without a third attempt`() =
        runBlocking {
            for (operation in listOf("rename", "delete")) {
                val snapshots = AtomicInteger(0)
                val mutations = AtomicInteger(0)
                TestHttpServer { exchange ->
                    when {
                        exchange.requestURI.path.endsWith("/mutation-snapshot") -> {
                            snapshots.incrementAndGet()
                            exchange.respondJson(200, """{"exists":true,"runtime_revision":1}""")
                        }

                        operation == "rename" && exchange.method == "PATCH" ||
                            operation == "delete" && exchange.method == "DELETE" -> {
                            mutations.incrementAndGet()
                            exchange.respondJson(409, """{"detail":"client_upgrade_required"}""")
                        }

                        else -> exchange.respondEmpty(404)
                    }
                }.use { server ->
                    val endpoint = Endpoint.parse(server.baseUrl)
                    val api = Api(newHttpClient())
                    val failure =
                        assertFailsWith<ApiException> {
                            if (operation == "rename") {
                                api.renameSession(endpoint, "s1", "New title")
                            } else {
                                api.deleteSession(endpoint, "s1")
                            }
                        }
                    assertEquals("changed elsewhere — retry", failure.message)
                    assertEquals(ApiErrorKind.HTTP, failure.kind)
                    assertEquals(409, failure.httpCode)
                }
                assertEquals(2, snapshots.get(), operation)
                assertEquals(2, mutations.get(), operation)
            }
        }

    @Test
    fun `missing, malformed, or stale mutation snapshots never send an unfenced mutation`() =
        runBlocking {
            val cases =
                listOf(
                    """{"exists":false,"runtime_revision":1}""" to ApiErrorKind.HTTP,
                    "{}" to ApiErrorKind.INVALID_RESPONSE,
                    """{"exists":true}""" to ApiErrorKind.INVALID_RESPONSE,
                    """{"exists":true,"runtime_revision":{"value":1}}""" to ApiErrorKind.INVALID_RESPONSE,
                    """{"exists":true,"runtime_revision":"soon"}""" to ApiErrorKind.INVALID_RESPONSE,
                    """{"exists":true,"runtime_revision":1,"runtime_generation":"later"}""" to
                        ApiErrorKind.INVALID_RESPONSE,
                )
            for ((snapshotBody, expectedKind) in cases) {
                val mutations = AtomicInteger(0)
                TestHttpServer { exchange ->
                    when {
                        exchange.requestURI.path.endsWith("/mutation-snapshot") ->
                            exchange.respondJson(200, snapshotBody)

                        exchange.method == "PATCH" || exchange.method == "DELETE" -> {
                            mutations.incrementAndGet()
                            exchange.respondEmpty(204)
                        }

                        else -> exchange.respondEmpty(404)
                    }
                }.use { server ->
                    val failure =
                        assertFailsWith<ApiException> {
                            Api(newHttpClient()).renameSession(
                                Endpoint.parse(server.baseUrl),
                                "s1",
                                "New title",
                            )
                        }
                    assertEquals(expectedKind, failure.kind, snapshotBody)
                    if (expectedKind == ApiErrorKind.HTTP) assertEquals(404, failure.httpCode)
                    assertEquals(0, mutations.get(), snapshotBody)
                }
            }

            val hugeRevision = "184467440737095516160"
            for (generation in listOf(null, hugeRevision)) {
                val snapshot =
                    if (generation == null) {
                        """{"exists":true,"runtime_revision":"$hugeRevision"}"""
                    } else {
                        """{"exists":true,"runtime_revision":"$hugeRevision","runtime_generation":"$generation"}"""
                    }
                var patched: JsonObject? = null
                TestHttpServer { exchange ->
                    when {
                        exchange.requestURI.path.endsWith("/mutation-snapshot") ->
                            exchange.respondJson(200, snapshot)

                        exchange.method == "PATCH" -> {
                            patched = hermesJson.parseToJsonElement(exchange.bodyText()).jsonObject
                            exchange.respondEmpty(204)
                        }

                        else -> exchange.respondEmpty(404)
                    }
                }.use { server ->
                    Api(newHttpClient()).renameSession(
                        Endpoint.parse(server.baseUrl),
                        "s1",
                        "New title",
                    )
                }
                val payload = checkNotNull(patched)
                assertTrue(payload.getValue("expected_revision").jsonPrimitive.isString)
                assertEquals(hugeRevision, payload.getValue("expected_revision").jsonPrimitive.content)
                assertEquals(generation, payload["expected_generation"]?.jsonPrimitive?.content, snapshot)
            }

            val mutations = AtomicInteger(0)
            TestHttpServer { exchange ->
                when {
                    exchange.requestURI.path.endsWith("/mutation-snapshot") -> exchange.respondEmpty(404)
                    else -> {
                        mutations.incrementAndGet()
                        exchange.respondEmpty(204)
                    }
                }
            }.use { server ->
                val failure =
                    assertFailsWith<ApiException> {
                        Api(newHttpClient()).deleteSession(Endpoint.parse(server.baseUrl), "s1")
                    }
                assertEquals(ApiErrorKind.HTTP, failure.kind)
                assertEquals(404, failure.httpCode)
                assertEquals(0, mutations.get())
            }
        }

    @Test
    fun `unrelated conflicts are not retried`() =
        runBlocking {
            val snapshots = AtomicInteger(0)
            val mutations = AtomicInteger(0)
            TestHttpServer { exchange ->
                when {
                    exchange.requestURI.path.endsWith("/mutation-snapshot") -> {
                        snapshots.incrementAndGet()
                        exchange.respondJson(200, """{"exists":true,"runtime_revision":1}""")
                    }

                    exchange.method == "PATCH" -> {
                        mutations.incrementAndGet()
                        exchange.respondJson(409, """{"detail":"title_conflict"}""")
                    }

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val failure =
                    assertFailsWith<ApiException> {
                        Api(newHttpClient()).renameSession(
                            Endpoint.parse(server.baseUrl),
                            "s1",
                            "New title",
                        )
                    }
                assertEquals(ApiErrorKind.HTTP, failure.kind)
                assertEquals(409, failure.httpCode)
            }
            assertEquals(1, snapshots.get())
            assertEquals(1, mutations.get())
        }

    @Test
    fun `latest descendant requires a nonblank current id`() =
        runBlocking {
            for (body in listOf("{}", """{"session_id":""}""", """{"session_id":" "}""")) {
                TestHttpServer { it.respondJson(200, body) }.use { server ->
                    val failure =
                        assertFailsWith<ApiException> {
                            Api(newHttpClient()).latestDescendant(Endpoint.parse(server.baseUrl), "stored")
                        }
                    assertEquals(ApiErrorKind.INVALID_RESPONSE, failure.kind)
                }
            }
        }

    @Test
    fun `sessions page maps rows, paging, and token auth`() =
        runBlocking {
            val requests = Collections.synchronizedList(mutableListOf<String>())
            var authorization: String? = null
            var observedQuery: Map<String, String> = emptyMap()
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/dash/api/sessions" -> {
                        requests.add(exchange.requestURI.toString())
                        authorization = exchange.requestHeaders.getFirst("Authorization")
                        observedQuery = queryParameters(exchange.requestURI)
                        exchange.respondJson(
                            200,
                            """
                            {
                              "sessions": [
                                {
                                  "id": "s1",
                                  "title": "One",
                                  "source": "june",
                                  "profile": "june",
                                  "preview": "hello",
                                  "display_name": "June",
                                  "model": "m1",
                                  "message_count": 4,
                                  "status": "idle",
                                  "last_active": "1700000000",
                                  "unread": 0,
                                  "pinned": 1,
                                  "archived": false
                                },
                                {
                                  "id": "",
                                  "title": "ignored"
                                },
                                {
                                  "id": "s2",
                                  "display_name": "Forge",
                                  "last_activity_at": 1700000123
                                }
                              ],
                              "total": 3,
                              "limit": 2,
                              "offset": 0
                            }
                            """.trimIndent(),
                        )
                    }

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val endpoint = Endpoint.parse("${server.baseUrl}dash/")
                val page =
                    Api(newHttpClient(), tokenProvider = { "tok-123" }).sessions(
                        endpoint = endpoint,
                        limit = 2,
                        offset = 0,
                        source = "june",
                        excludeSources = "cron",
                        profile = "june",
                    )
                assertEquals(2, page.sessions.size)
                assertEquals("s1", page.sessions[0].id)
                assertEquals("One", page.sessions[0].title)
                assertEquals("june", page.sessions[0].source)
                assertEquals("june", page.sessions[0].profile)
                assertEquals(4, page.sessions[0].messageCount)
                assertEquals(1_700_000_000_000L, page.sessions[0].updatedAtEpochMs)
                assertEquals(false, page.sessions[0].unread)
                assertTrue(page.sessions[0].pinned)
                assertEquals("Forge", page.sessions[1].title)
                assertEquals(1_700_000_123_000L, page.sessions[1].updatedAtEpochMs)
                assertEquals(3, page.total)
                assertEquals(2, page.limit)
                assertEquals(0, page.offset)
                assertTrue(page.hasMore)

                assertEquals("Bearer tok-123", authorization)
                assertEquals("2", observedQuery["limit"])
                assertEquals("0", observedQuery["offset"])
                assertEquals("recent", observedQuery["order"])
                assertEquals("june", observedQuery["source"])
                assertEquals("cron", observedQuery["exclude_sources"])
                assertEquals("june", observedQuery["profile"])
                assertEquals(1, requests.size)
            }
        }

    @Test
    fun `gated mode sends no bearer header`() =
        runBlocking {
            var authorization: String? = "unset"
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/api/sessions" -> {
                        authorization = exchange.requestHeaders.getFirst("Authorization")
                        exchange.respondJson(200, """{"sessions":[],"total":0}""")
                    }

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val page =
                    Api(newHttpClient()).sessions(
                        endpoint = Endpoint.parse(server.baseUrl),
                        excludeSources = null,
                    )
                assertTrue(page.sessions.isEmpty())
                assertNull(authorization)
            }
        }

    @Test
    fun `latest messages flatten content and keep row keys unique`() =
        runBlocking {
            var observedQuery: Map<String, String> = emptyMap()
            TestHttpServer { exchange ->
                if (exchange.requestURI.path.startsWith("/dash/api/sessions/a/b/messages")) {
                    observedQuery = queryParameters(exchange.requestURI)
                    exchange.respondJson(
                        200,
                        """
                        {
                          "messages": [
                            {
                              "id": 10,
                              "role": "user",
                              "content": "hello",
                              "timestamp": 1700000000
                            },
                            {
                              "id": null,
                              "role": "assistant",
                              "content": [
                                {"type": "text", "text": "first"},
                                {"type": "image", "text": "ignored"},
                                {"type": "text", "text": "second"}
                              ],
                              "display_reasoning": "why",
                              "type": "message"
                            },
                            {
                              "id": 0,
                              "role": "assistant",
                              "content": "raw",
                              "display_content": {"text": "projected"},
                              "type": "tool",
                              "tool_call_id": "call-1",
                              "timestamp": "2026-11-26T00:00:00Z",
                              "display_kind": "model_switch"
                            }
                          ],
                          "offset": 0,
                          "total": 8,
                          "pagination": {
                            "limit": 3,
                            "offset": 0,
                            "order": "latest",
                            "returned": 3,
                            "has_older": true
                          }
                        }
                        """.trimIndent(),
                    )
                } else {
                    exchange.respondEmpty(404)
                }
            }.use { server ->
                val endpoint = Endpoint.parse("${server.baseUrl}dash/")
                val page =
                    Api(newHttpClient()).messages(
                        endpoint = endpoint,
                        id = "a/b",
                        limit = 3,
                        offset = 0,
                    )
                assertEquals(3, page.messages.size)
                assertEquals(8, page.total)
                assertTrue(page.hasOlder)
                assertEquals("hello", page.messages[0].text)
                assertEquals(1_700_000_000_000L, page.messages[0].timestampEpochMs)
                assertEquals("first\nsecond", page.messages[1].text)
                assertEquals("why", page.messages[1].reasoning)
                assertEquals("projected", page.messages[2].text)
                assertEquals("tool", page.messages[2].type)
                assertEquals("call-1", page.messages[2].toolCallId)
                assertEquals("model_switch", page.messages[2].displayKind)
                assertEquals(1_795_651_200_000L, page.messages[2].timestampEpochMs)

                assertTrue(page.messages[0].rowId > 0)
                assertTrue(page.messages[1].rowId < 0)
                assertTrue(page.messages[2].rowId < 0)
                assertEquals(
                    3,
                    page.messages
                        .map { it.rowId }
                        .toSet()
                        .size,
                )

                assertEquals("3", observedQuery["limit"])
                assertEquals("latest", observedQuery["order"])
                assertEquals("true", observedQuery["include_compacted"])
            }
        }

    @Test
    fun `around window reports edges and refuses synthetic anchors`() =
        runBlocking {
            val requests = AtomicInteger(0)
            var observedQuery: Map<String, String> = emptyMap()
            TestHttpServer { exchange ->
                requests.incrementAndGet()
                if (exchange.requestURI.path.endsWith("/messages/around")) {
                    observedQuery = queryParameters(exchange.requestURI)
                    exchange.respondJson(
                        200,
                        """
                        {
                          "session_id": "s1",
                          "messages": [
                            {"role": "user", "content": "older"},
                            {"role": "assistant", "content": "newer"}
                          ],
                          "pagination": {
                            "row_id": 10,
                            "limit": 2,
                            "returned": 2,
                            "offset": 50,
                            "total": 120,
                            "has_older": true,
                            "has_newer": false
                          }
                        }
                        """.trimIndent(),
                    )
                } else {
                    exchange.respondEmpty(404)
                }
            }.use { server ->
                val endpoint = Endpoint.parse(server.baseUrl)
                val api = Api(newHttpClient())
                val window = api.messagesAround(endpoint, "s1", rowId = 10, limit = 2)
                assertEquals(2, window.messages.size)
                assertTrue(window.hasOlder)
                assertTrue(!window.hasNewer)
                assertEquals(120, window.total)
                assertTrue(window.messages.all { it.rowId < 0 })
                assertEquals(
                    2,
                    window.messages
                        .map { it.rowId }
                        .toSet()
                        .size,
                )
                assertEquals("10", observedQuery["row_id"])
                assertEquals("2", observedQuery["limit"])
                assertEquals(1, requests.get())

                val rejection =
                    assertFailsWith<ApiException> {
                        api.messagesAround(endpoint, "s1", rowId = -3)
                    }
                assertEquals(ApiErrorKind.UNSUPPORTED, rejection.kind)
                assertEquals(1, requests.get())
            }
        }

    @Test
    fun `session detail carries token counts`() =
        runBlocking {
            TestHttpServer { exchange ->
                if (exchange.requestURI.path == "/api/sessions/s1") {
                    exchange.respondJson(
                        200,
                        """
                        {
                          "session_id": "s1",
                          "session_key": "key-1",
                          "title": "June",
                          "status": "idle",
                          "message_count": 12,
                          "input_tokens": "100",
                          "output_tokens": 50,
                          "last_active": 1700000000
                        }
                        """.trimIndent(),
                    )
                } else {
                    exchange.respondEmpty(404)
                }
            }.use { server ->
                val detail = Api(newHttpClient()).session(Endpoint.parse(server.baseUrl), "s1")
                assertEquals("s1", detail.id)
                assertEquals("key-1", detail.sessionKey)
                assertEquals("June", detail.title)
                assertEquals(12, detail.messageCount)
                assertEquals(100L, detail.inputTokens)
                assertEquals(50L, detail.outputTokens)
                assertEquals(1_700_000_000_000L, detail.updatedAtEpochMs)
            }
        }

    @Test
    fun `unauthorized, server, and malformed replies map to typed errors`() =
        runBlocking {
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/api/sessions" -> exchange.respondEmpty(401)
                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val failure =
                    assertFailsWith<ApiException> {
                        Api(newHttpClient()).sessions(Endpoint.parse(server.baseUrl))
                    }
                assertEquals(ApiErrorKind.UNAUTHORIZED, failure.kind)
                assertEquals(401, failure.httpCode)
            }

            TestHttpServer { exchange -> exchange.respondEmpty(500) }.use { server ->
                val failure =
                    assertFailsWith<ApiException> {
                        Api(newHttpClient()).sessions(Endpoint.parse(server.baseUrl))
                    }
                assertEquals(ApiErrorKind.HTTP, failure.kind)
                assertEquals(500, failure.httpCode)
            }

            TestHttpServer { exchange -> exchange.respondJson(200, "not-json") }.use { server ->
                val failure =
                    assertFailsWith<ApiException> {
                        Api(newHttpClient()).sessions(Endpoint.parse(server.baseUrl))
                    }
                assertEquals(ApiErrorKind.INVALID_RESPONSE, failure.kind)
            }
        }

    @Test
    fun `session search maps the sessions envelope with the true total and scoped query`() =
        runBlocking {
            var observedQuery: Map<String, String> = emptyMap()
            var rawQuery: String? = null
            var authorization: String? = null
            TestHttpServer { exchange ->
                if (exchange.requestURI.path == "/dash/api/sessions/search") {
                    observedQuery = queryParameters(exchange.requestURI)
                    rawQuery = exchange.requestURI.rawQuery
                    authorization = exchange.requestHeaders.getFirst("Authorization")
                    exchange.respondJson(
                        200,
                        """
                        {
                          "sessions": [
                            {
                              "id": "s1",
                              "title": "One",
                              "source": "june",
                              "profile": "june",
                              "message_count": "2",
                              "last_active": 1700000000,
                              "pinned": 1
                            },
                            {"id": "", "title": "ignored"},
                            {"id": "s2", "display_name": "Forge", "last_activity_at": "1700000123000"}
                          ],
                          "total": 801,
                          "limit": 2,
                          "offset": 4
                        }
                        """.trimIndent(),
                    )
                } else {
                    exchange.respondEmpty(404)
                }
            }.use { server ->
                val page =
                    Api(newHttpClient(), tokenProvider = { "search-token" }).searchSessions(
                        endpoint = Endpoint.parse("${server.baseUrl}dash/"),
                        query = "needle & thread / 2",
                        limit = 2,
                        offset = 4,
                        profile = "june",
                        source = "june",
                    )
                assertEquals(2, page.sessions.size)
                assertEquals("s1", page.sessions[0].id)
                assertEquals("One", page.sessions[0].title)
                assertEquals("june", page.sessions[0].profile)
                assertEquals(2, page.sessions[0].messageCount)
                assertTrue(page.sessions[0].pinned)
                assertEquals("Forge", page.sessions[1].title)
                assertEquals(1_700_000_123_000L, page.sessions[1].updatedAtEpochMs)
                assertEquals(801, page.total)
                assertEquals(2, page.limit)
                assertEquals(4, page.offset)
                assertTrue(page.hasMore)
            }
            assertEquals("Bearer search-token", authorization)
            assertEquals("needle & thread / 2", observedQuery["q"])
            assertEquals("june", observedQuery["profile"])
            assertEquals("june", observedQuery["source"])
            assertEquals("2", observedQuery["limit"])
            assertEquals("4", observedQuery["offset"])
            assertNull(observedQuery["exclude_sources"])
            assertNull(observedQuery["order"])
            assertTrue(rawQuery.orEmpty().contains("q=needle%20%26%20thread"))
        }

    @Test
    fun `session search sessions envelope without a total counts the returned rows`() =
        runBlocking {
            var observedQuery: Map<String, String> = emptyMap()
            TestHttpServer { exchange ->
                if (exchange.requestURI.path == "/api/sessions/search") {
                    observedQuery = queryParameters(exchange.requestURI)
                    exchange.respondJson(200, """{"sessions":[{"id":"s1"},{"id":"s2"}]}""")
                } else {
                    exchange.respondEmpty(404)
                }
            }.use { server ->
                val page =
                    Api(newHttpClient()).searchSessions(
                        endpoint = Endpoint.parse(server.baseUrl),
                        query = "50% off?",
                        limit = 2,
                    )
                assertEquals(2, page.total)
                assertEquals(2, page.sessions.size)
                assertEquals(2, page.limit)
                assertEquals(0, page.offset)
                assertFalse(page.hasMore)
            }
            assertEquals("50% off?", observedQuery["q"])
            assertEquals(1, observedQuery.keys.count { it == "q" })
        }

    @Test
    fun `session search maps the reference results envelope, deduplicates hits, and uses its total`() =
        runBlocking {
            var observedQuery: Map<String, String> = emptyMap()
            TestHttpServer { exchange ->
                if (exchange.requestURI.path == "/dash/api/sessions/search") {
                    observedQuery = queryParameters(exchange.requestURI)
                    exchange.respondJson(
                        200,
                        """
                        {
                          "results": [
                            {
                              "session_id": "s1",
                              "snippet": "needle in a message",
                              "title": "One",
                              "source": "june",
                              "model": "m1",
                              "session_started": 1700000000,
                              "last_active": 1700000300,
                              "message_count": 4,
                              "archived": false
                            },
                            {
                              "session_id": "s1",
                              "snippet": "a second matching message",
                              "title": "One",
                              "session_started": 1700000000
                            },
                            {
                              "session_id": "s2",
                              "snippet": "other snippet",
                              "title": "Two",
                              "session_started": "1700000400"
                            }
                          ],
                          "has_more": true,
                          "next_offset": 3,
                          "total": 9
                        }
                        """.trimIndent(),
                    )
                } else {
                    exchange.respondEmpty(404)
                }
            }.use { server ->
                val page =
                    Api(newHttpClient()).searchSessions(
                        endpoint = Endpoint.parse("${server.baseUrl}dash/"),
                        query = "needle",
                        limit = 3,
                        profile = "june",
                    )
                assertEquals(listOf("s1", "s2"), page.sessions.map { it.id })
                assertEquals(2, page.sessions.size)
                assertEquals("One", page.sessions[0].title)
                assertEquals("june", page.sessions[0].source)
                assertEquals("m1", page.sessions[0].model)
                assertEquals("needle in a message", page.sessions[0].preview)
                assertEquals(4, page.sessions[0].messageCount)
                assertEquals(1_700_000_300_000L, page.sessions[0].updatedAtEpochMs)
                assertEquals("other snippet", page.sessions[1].preview)
                assertEquals(1_700_000_400_000L, page.sessions[1].updatedAtEpochMs)
                assertEquals(9, page.total)
                assertEquals(3, page.limit)
                assertEquals(0, page.offset)
                assertTrue(page.hasMore)
            }
            assertEquals("needle", observedQuery["q"])
            assertEquals("june", observedQuery["profile"])
        }

    @Test
    fun `session search reference results without a total count unique rows and honor has_more`() =
        runBlocking {
            TestHttpServer { exchange ->
                if (exchange.requestURI.path == "/api/sessions/search") {
                    exchange.respondJson(
                        200,
                        """
                        {
                          "results": [
                            {"session_id": "s1", "snippet": "first hit", "session_started": 1700000000},
                            {"session_id": "s1", "snippet": "second hit"},
                            {"session_id": "s2", "session_started": 1700000500}
                          ],
                          "has_more": true,
                          "next_offset": 2
                        }
                        """.trimIndent(),
                    )
                } else {
                    exchange.respondEmpty(404)
                }
            }.use { server ->
                val page =
                    Api(newHttpClient()).searchSessions(
                        endpoint = Endpoint.parse(server.baseUrl),
                        query = "hit",
                        limit = 2,
                    )
                assertEquals(listOf("s1", "s2"), page.sessions.map { it.id })
                assertEquals(2, page.total)
                assertEquals(2, page.limit)
                assertEquals(0, page.offset)
                assertTrue(page.hasMore)
                assertEquals("first hit", page.sessions[0].preview)
                assertEquals(1_700_000_000_000L, page.sessions[0].updatedAtEpochMs)
                assertEquals(1_700_000_500_000L, page.sessions[1].updatedAtEpochMs)
            }
        }

    @Test
    fun `session search rejects a response with neither sessions nor results`() =
        runBlocking {
            TestHttpServer { exchange ->
                exchange.respondJson(200, """{"total":5,"has_more":false}""")
            }.use { server ->
                val failure =
                    assertFailsWith<ApiException> {
                        Api(newHttpClient()).searchSessions(
                            Endpoint.parse(server.baseUrl),
                            "needle",
                        )
                    }
                assertEquals(ApiErrorKind.INVALID_RESPONSE, failure.kind)
            }
        }

    @Test
    fun `unreachable dashboard maps to a network error`() =
        runBlocking {
            val server = TestHttpServer { exchange -> exchange.respondEmpty(404) }
            val baseUrl = server.baseUrl
            server.close()

            val failure =
                assertFailsWith<ApiException> {
                    Api(newHttpClient()).sessions(Endpoint.parse(baseUrl))
                }
            assertEquals(ApiErrorKind.NETWORK, failure.kind)
            assertTrue(failure.cause != null)
        }
}
