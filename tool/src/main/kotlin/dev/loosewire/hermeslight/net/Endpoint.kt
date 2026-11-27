/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Endpoint derivation is derived from Hermes Mobile
 * (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Which query parameter carries the WebSocket credential. */
enum class WsAuthParam(
    val queryName: String,
) {
    /** Loopback token mode (`?token=`). */
    TOKEN("token"),

    /** Gated password mode (`?ticket=`, single use). */
    TICKET("ticket"),
}

/** The entered endpoint cannot be used as a dashboard base URL. */
class InvalidEndpointException(
    message: String,
) : IllegalArgumentException(message)

/**
 * Canonical Hermes dashboard endpoint.
 *
 * [baseUrl] always carries an http(s) scheme and a trailing slash. REST paths and WebSocket URLs are
 * derived from it, so a reverse-proxy path prefix is never dropped by an individual caller.
 */
class Endpoint private constructor(
    val baseUrl: String,
    private val url: HttpUrl,
) {
    val secure: Boolean get() = url.isHttps

    /** Warning for cleartext HTTP; null for HTTPS. */
    val cleartextWarning: String? get() = if (url.isHttps) null else CLEARTEXT_WARNING

    /** Absolute HTTP URL for a path relative to this endpoint, preserving the proxy prefix. */
    fun resolve(relativePath: String): String = buildUrl(relativePath, emptyMap()).toString()

    /** The parsed WebSocket URL for `api/ws` plus the encoded token/ticket credential. */
    fun webSocketUrl(
        authParam: WsAuthParam,
        credential: String,
    ): String {
        val httpUrl =
            buildUrl("api/ws", emptyMap())
                .newBuilder()
                .addQueryParameter(authParam.queryName, credential)
                .build()
        val text = httpUrl.toString()
        val separator = text.indexOf("://")
        return if (url.isHttps) {
            "wss${text.substring(separator)}"
        } else {
            "ws${text.substring(separator)}"
        }
    }

    /** URL builder used by the REST client; null query values are omitted. */
    internal fun buildUrl(
        relativePath: String,
        query: Map<String, String?>,
    ): HttpUrl {
        val path = relativePath.trim().trimStart('/')
        require(path.isNotEmpty()) { "Endpoint path must not be blank" }
        require(path.split('/').none { it == ".." }) {
            "Endpoint path must not escape the configured base path"
        }
        val builder = url.newBuilder().addPathSegments(path)
        query.forEach { (key, value) ->
            if (value != null) builder.addQueryParameter(key, value)
        }
        return builder.build()
    }

    override fun equals(other: Any?): Boolean = other is Endpoint && other.baseUrl == baseUrl

    override fun hashCode(): Int = baseUrl.hashCode()

    override fun toString(): String = baseUrl

    companion object {
        const val DEFAULT_BASE_URL = "https://127.0.0.1:9119/"

        const val CLEARTEXT_WARNING =
            "Cleartext HTTP exposes credentials and messages. " +
                "Use HTTPS unless this is a trusted development network."

        /**
         * Parse a user-entered base URL. Accepts http/https, an optional port, and a reverse-proxy
         * path prefix. Rejects credentials, query strings, fragments, and missing schemes.
         */
        fun parse(rawValue: String): Endpoint {
            val raw = rawValue.trim()
            if (raw.isEmpty()) throw InvalidEndpointException("Dashboard address is required")
            if (!raw.startsWith("https://", ignoreCase = true) &&
                !raw.startsWith("http://", ignoreCase = true)
            ) {
                throw InvalidEndpointException("Dashboard address must start with http:// or https://")
            }
            val parsed =
                raw.toHttpUrlOrNull()
                    ?: throw InvalidEndpointException("Dashboard address is malformed")
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
                throw InvalidEndpointException("Dashboard address must not contain credentials")
            }
            if (parsed.encodedQuery != null) {
                throw InvalidEndpointException("Dashboard address must not contain a query")
            }
            if (parsed.encodedFragment != null) {
                throw InvalidEndpointException("Dashboard address must not contain a fragment")
            }
            val normalized =
                if (parsed.encodedPath.endsWith('/')) {
                    parsed
                } else {
                    parsed.newBuilder().addPathSegment("").build()
                }
            return Endpoint(normalized.toString(), normalized)
        }

        fun parseOrNull(rawValue: String): Endpoint? =
            try {
                parse(rawValue)
            } catch (_: InvalidEndpointException) {
                null
            }
    }
}

/**
 * Remove token/ticket values from a URL before it reaches a status line or log. Invalid URLs lose
 * their query entirely rather than leaking an unknown credential shape.
 */
fun redactEndpointUrl(rawUrl: String): String {
    val socketScheme =
        when {
            rawUrl.startsWith("wss://", ignoreCase = true) -> "wss"
            rawUrl.startsWith("ws://", ignoreCase = true) -> "ws"
            else -> null
        }
    val httpUrl =
        when (socketScheme) {
            "wss" -> "https" + rawUrl.substring("wss".length)
            "ws" -> "http" + rawUrl.substring("ws".length)
            else -> rawUrl
        }
    val parsed = httpUrl.toHttpUrlOrNull() ?: return "<invalid-url>"
    val builder = parsed.newBuilder().query(null)
    parsed.queryParameterNames.forEach { name ->
        if (name == "token" || name == "ticket" || name == "password") {
            builder.addQueryParameter(name, "REDACTED")
        } else {
            parsed.queryParameterValues(name).forEach { value ->
                builder.addQueryParameter(name, value)
            }
        }
    }
    val redacted = builder.build().toString()
    return when (socketScheme) {
        "wss" -> "wss" + redacted.substring("https".length)
        "ws" -> "ws" + redacted.substring("http".length)
        else -> redacted
    }
}
