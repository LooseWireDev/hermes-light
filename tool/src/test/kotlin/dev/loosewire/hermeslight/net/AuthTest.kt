/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Tests for probe, sign-in, and ticket handling.
 */
package dev.loosewire.hermeslight.net

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthTest {
    @Test
    fun `open dashboard extracts the loopback token`() =
        runBlocking {
            val statusCalls = mutableListOf<String>()
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/api/status" -> {
                        statusCalls.add(exchange.requestURI.path)
                        exchange.respondJson(200, """{"auth_required":false,"version":"1.2.3"}""")
                    }

                    "/" ->
                        exchange.respondHtml(
                            200,
                            """<script>window.__HERMES_SESSION_TOKEN__ = "tok-123";</script>""",
                        )

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val result = Auth(newHttpClient()).probe(Endpoint.parse(server.baseUrl))
                assertEquals(ProbeResult.Open(token = "tok-123", version = "1.2.3"), result)
                assertEquals(listOf("/api/status"), statusCalls)
            }
        }

    @Test
    fun `open dashboard without an embedded token still connects`() =
        runBlocking {
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/api/status" -> exchange.respondJson(200, """{"auth_required":false}""")
                    "/" -> exchange.respondHtml(200, "<html>no token</html>")
                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val result = Auth(newHttpClient()).probe(Endpoint.parse(server.baseUrl))
                assertEquals(ProbeResult.Open(token = null, version = null), result)
            }
        }

    @Test
    fun `gated dashboard discovers password providers and flows`() =
        runBlocking {
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/api/status" ->
                        exchange.respondJson(
                            200,
                            """
                            {
                              "auth_required": true,
                              "auth_providers": ["basic"],
                              "auth_flows": ["native_pkce"]
                            }
                            """.trimIndent(),
                        )

                    "/api/auth/providers" ->
                        exchange.respondJson(
                            200,
                            """
                            {
                              "providers": [
                                {"name": "basic", "display_name": "Password", "supports_password": true},
                                {"name": "oidc", "display_name": "SSO", "supports_password": false},
                                {"name": "drain-secret", "display_name": "Drain", "supports_password": true}
                              ]
                            }
                            """.trimIndent(),
                        )

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val result = Auth(newHttpClient()).probe(Endpoint.parse(server.baseUrl))
                val gated = result as? ProbeResult.Gated ?: error("expected gated, got $result")
                assertEquals(listOf("basic", "oidc"), gated.providers.map { it.name })
                assertEquals("Password", gated.providers[0].displayName)
                assertTrue(gated.providers[0].supportsPassword)
                assertTrue(!gated.providers[1].supportsPassword)
                assertTrue(gated.browserSupported)
            }
        }

    @Test
    fun `gated dashboard falls back to status provider names`() =
        runBlocking {
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/api/status" ->
                        exchange.respondJson(
                            200,
                            """{"auth_required":true,"auth_providers":["basic","oidc"]}""",
                        )

                    else -> exchange.respondEmpty(500)
                }
            }.use { server ->
                val result = Auth(newHttpClient()).probe(Endpoint.parse(server.baseUrl))
                val gated = result as? ProbeResult.Gated ?: error("expected gated, got $result")
                assertEquals(listOf("basic", "oidc"), gated.providers.map { it.name })
                assertTrue(gated.providers[0].supportsPassword)
                assertTrue(!gated.providers[1].supportsPassword)
                assertTrue(!gated.browserSupported)
            }
        }

    @Test
    fun `401 status with published providers is a gated dashboard`() =
        runBlocking {
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/api/status" -> exchange.respondEmpty(401)
                    "/api/auth/providers" ->
                        exchange.respondJson(
                            200,
                            """{"providers":[{"name":"basic","supports_password":true}]}""",
                        )

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val result = Auth(newHttpClient()).probe(Endpoint.parse(server.baseUrl))
                val gated = result as? ProbeResult.Gated ?: error("expected gated, got $result")
                assertEquals("basic", gated.providers.single().name)
                assertTrue(!gated.browserSupported)
            }
        }

    @Test
    fun `unreachable dashboard reports a network error`() =
        runBlocking {
            val server = TestHttpServer { exchange -> exchange.respondEmpty(500) }
            val baseUrl = server.baseUrl
            server.close()

            val result = Auth(newHttpClient(), deadlineMillis = 2_000L).probe(Endpoint.parse(baseUrl))
            val failure = result as? ProbeResult.Failed ?: error("expected failure, got $result")
            assertTrue(failure.error is AuthError.Unreachable)
        }

    @Test
    fun `a stalled dashboard hits the 25 second watchdog`() =
        runBlocking {
            TestHttpServer { exchange ->
                Thread.sleep(1_000)
                exchange.respondJson(200, """{"auth_required":false}""")
            }.use { server ->
                val result = Auth(newHttpClient(), deadlineMillis = 150L).probe(Endpoint.parse(server.baseUrl))
                val failure = result as? ProbeResult.Failed ?: error("expected failure, got $result")
                assertTrue(failure.error is AuthError.Timeout)
            }
        }

    @Test
    fun `unexpected status body is reported as invalid`() =
        runBlocking {
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/api/status" -> exchange.respondHtml(200, "<html>not a dashboard</html>")
                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val result = Auth(newHttpClient()).probe(Endpoint.parse(server.baseUrl))
                val failure = result as? ProbeResult.Failed ?: error("expected failure, got $result")
                assertTrue(failure.error is AuthError.InvalidResponse)
            }
        }

    @Test
    fun `wrong password is rejected without minting a ticket`() =
        runBlocking {
            var ticketCalls = 0
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/auth/password-login" -> exchange.respondJson(401, """{"detail":"bad password"}""")
                    "/api/auth/ws-ticket" -> {
                        ticketCalls += 1
                        exchange.respondJson(200, """{"ticket":"unused"}""")
                    }

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val result = Auth(newHttpClient()).signIn(Endpoint.parse(server.baseUrl), "u", "wrong")
                val failure = result as? SignInResult.Failed ?: error("expected failure, got $result")
                assertTrue(failure.error is AuthError.Rejected)
                assertEquals(0, ticketCalls)
            }
        }

    @Test
    fun `password login keeps its cookie and mints a websocket ticket`() =
        runBlocking {
            var loginBody = ""
            var ticketCookie: String? = null
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/auth/password-login" -> {
                        loginBody = exchange.bodyText()
                        exchange.respondJson(
                            200,
                            """{"ok":true}""",
                            mapOf("Set-Cookie" to "hermes_session=abc; Path=/; HttpOnly"),
                        )
                    }

                    "/api/auth/ws-ticket" -> {
                        ticketCookie = exchange.requestHeaders.getFirst("Cookie")
                        exchange.respondJson(200, """{"ticket":"ticket+/=","ttl_seconds":60}""")
                    }

                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val result = Auth(newHttpClient()).signIn(Endpoint.parse(server.baseUrl), "june", "secret")
                assertEquals(SignInResult.Success("ticket+/="), result)
                assertTrue(loginBody.contains("\"provider\":\"basic\""))
                assertTrue(loginBody.contains("\"username\":\"june\""))
                assertTrue(loginBody.contains("\"password\":\"secret\""))
                val cookie = assertNotNull(ticketCookie)
                assertTrue(cookie.contains("hermes_session=abc"))
            }
        }

    @Test
    fun `ticket minting maps HTTP failures honestly`() =
        runBlocking {
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/auth/password-login" -> exchange.respondJson(200, """{"ok":true}""")
                    "/api/auth/ws-ticket" -> exchange.respondEmpty(503)
                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val endpoint = Endpoint.parse(server.baseUrl)
                val failure =
                    Auth(newHttpClient()).signIn(endpoint, "june", "secret") as? SignInResult.Failed
                        ?: error("expected failure")
                val error = failure.error as? AuthError.Http ?: error("expected HTTP error")
                assertEquals(503, error.code)
                assertTrue(error.message.contains("503"))
            }
        }

    @Test
    fun `missing ticket body is invalid rather than connectable`() =
        runBlocking {
            TestHttpServer { exchange ->
                when (exchange.requestURI.path) {
                    "/api/auth/ws-ticket" -> exchange.respondJson(200, """{"ttl_seconds":60}""")
                    else -> exchange.respondEmpty(404)
                }
            }.use { server ->
                val result = Auth(newHttpClient()).mintWsTicket(Endpoint.parse(server.baseUrl))
                val failure = result as? TicketResult.Failed ?: error("expected failure, got $result")
                assertTrue(failure.error is AuthError.InvalidResponse)
            }
        }
}
