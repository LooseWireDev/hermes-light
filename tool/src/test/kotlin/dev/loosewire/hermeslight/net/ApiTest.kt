/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Tests for the sessions REST family.
 */
package dev.loosewire.hermeslight.net

import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTest {
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
