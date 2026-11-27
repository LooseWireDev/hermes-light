/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Tests for dashboard endpoint derivation.
 */
package dev.loosewire.hermeslight.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndpointTest {
    @Test
    fun `parse normalizes a trailing slash and preserves a proxy prefix`() {
        val endpoint = Endpoint.parse("https://hermes.example:8443/dash")
        assertEquals("https://hermes.example:8443/dash/", endpoint.baseUrl)
        assertTrue(endpoint.secure)
        assertNull(endpoint.cleartextWarning)
        assertEquals(
            "https://hermes.example:8443/dash/api/status",
            endpoint.resolve("api/status"),
        )
        assertEquals(
            "https://hermes.example:8443/dash/api/sessions/a/b",
            endpoint.resolve("api/sessions/a/b"),
        )
    }

    @Test
    fun `http endpoint warns about cleartext`() {
        val endpoint = Endpoint.parse("http://127.0.0.1:9121")
        assertEquals("http://127.0.0.1:9121/", endpoint.baseUrl)
        assertEquals(Endpoint.CLEARTEXT_WARNING, endpoint.cleartextWarning)
    }

    @Test
    fun `websocket url converts scheme and encodes the credential`() {
        val secure = Endpoint.parse("https://example.com/hermes")
        assertEquals(
            "wss://example.com/hermes/api/ws?token=a%20b%2Fc%3D",
            secure.webSocketUrl(WsAuthParam.TOKEN, "a b/c="),
        )
        assertEquals(
            "wss://example.com/hermes/api/ws?ticket=t%2Bt%2F%3D",
            secure.webSocketUrl(WsAuthParam.TICKET, "t+t/="),
        )

        val cleartext = Endpoint.parse("http://127.0.0.1:9119")
        assertEquals(
            "ws://127.0.0.1:9119/api/ws?ticket=xyz",
            cleartext.webSocketUrl(WsAuthParam.TICKET, "xyz"),
        )
    }

    @Test
    fun `bad addresses are rejected honestly`() {
        assertFailsWith<InvalidEndpointException> { Endpoint.parse("") }
        assertFailsWith<InvalidEndpointException> { Endpoint.parse("example.com") }
        assertFailsWith<InvalidEndpointException> { Endpoint.parse("ftp://example.com") }
        assertFailsWith<InvalidEndpointException> { Endpoint.parse("https://user:pass@example.com") }
        assertFailsWith<InvalidEndpointException> { Endpoint.parse("https://example.com/?x=1") }
        assertFailsWith<InvalidEndpointException> { Endpoint.parse("https://example.com/#frag") }
        assertNull(Endpoint.parseOrNull("nonsense"))
    }

    @Test
    fun `resolution refuses parent traversal`() {
        val endpoint = Endpoint.parse("https://example.com/dash/")
        assertFailsWith<IllegalArgumentException> { endpoint.resolve("../admin") }
        assertFailsWith<IllegalArgumentException> { endpoint.resolve("/") }
    }

    @Test
    fun `redaction strips token and ticket values`() {
        assertEquals(
            "wss://example.com/api/ws?token=REDACTED&x=1",
            redactEndpointUrl("wss://example.com/api/ws?token=secret&x=1"),
        )
        assertEquals(
            "https://example.com/api/ws?ticket=REDACTED",
            redactEndpointUrl("https://example.com/api/ws?ticket=abc"),
        )
        assertEquals("<invalid-url>", redactEndpointUrl("not a url"))
    }

    @Test
    fun `equality is based on the normalized base url`() {
        assertEquals(
            Endpoint.parse("https://example.com/dash"),
            Endpoint.parse("https://example.com/dash/"),
        )
        assertTrue(
            Endpoint.parse("https://example.com/dash") !=
                Endpoint.parse("https://example.com/other"),
        )
    }
}
