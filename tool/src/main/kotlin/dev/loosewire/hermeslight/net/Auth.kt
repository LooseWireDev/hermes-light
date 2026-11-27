/*
 * Copyright 2026 LooseWireDev
 *
 * Dashboard authentication handling is derived from Hermes Mobile
 * (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.net

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** One interactive sign-in option advertised by the dashboard. */
data class AuthProvider(
    val name: String,
    val displayName: String,
    val supportsPassword: Boolean,
)

/** User-showable authentication failure. Never contains a credential. */
sealed interface AuthError {
    val message: String

    data class Unreachable(
        override val message: String = "Dashboard unreachable — check address",
    ) : AuthError

    data object Timeout : AuthError {
        override val message: String = "Dashboard did not answer within 25 seconds — check address"
    }

    data class Http(
        val code: Int,
        override val message: String,
    ) : AuthError

    data class InvalidResponse(
        override val message: String,
    ) : AuthError

    data class Rejected(
        override val message: String,
    ) : AuthError
}

/** Result of probing `GET api/status`. */
sealed interface ProbeResult {
    /** Ungated (loopback) dashboard; [token] is the SPA-embedded session token when found. */
    data class Open(
        val token: String?,
        val version: String?,
    ) : ProbeResult

    /** Gated dashboard: pick a provider and sign in with a password. */
    data class Gated(
        val providers: List<AuthProvider>,
        val browserSupported: Boolean,
    ) : ProbeResult

    data class Failed(
        val error: AuthError,
    ) : ProbeResult
}

sealed interface SignInResult {
    data class Success(
        val ticket: String,
    ) : SignInResult

    data class Failed(
        val error: AuthError,
    ) : SignInResult
}

sealed interface TicketResult {
    data class Success(
        val ticket: String,
    ) : TicketResult

    data class Failed(
        val error: AuthError,
    ) : TicketResult
}

/**
 * Dashboard sign-in: status probe, provider discovery, loopback SPA token extraction, password
 * login, and WebSocket ticket minting.
 *
 * Every public operation is bounded by [deadlineMillis] for the whole flow, and cancellation
 * cancels the in-flight OkHttp call. Pass the same [client] to [Api] and the gateway so the gated
 * session cookie set by password login is reused for REST and ticket minting.
 */
class Auth(
    private val client: OkHttpClient = defaultHttpClient(),
    private val json: Json = hermesJson,
    private val deadlineMillis: Long = DEFAULT_DEADLINE_MILLIS,
) {
    init {
        require(deadlineMillis > 0) { "Auth deadline must be positive" }
    }

    /** Reachability plus auth mode in one probe. Token extraction is part of the same deadline. */
    suspend fun probe(endpoint: Endpoint): ProbeResult =
        withTimeoutOrNull(deadlineMillis) { probeWithin(endpoint) }
            ?: ProbeResult.Failed(AuthError.Timeout)

    /**
     * Sign in with a password and mint a single-use WebSocket ticket. Credentials are never stored
     * here; the parent persists them encrypted only after [SignInResult.Success].
     */
    suspend fun signIn(
        endpoint: Endpoint,
        username: String,
        password: String,
        provider: String = DEFAULT_PROVIDER,
    ): SignInResult {
        if (username.isBlank()) {
            return SignInResult.Failed(AuthError.Rejected("Enter a username"))
        }
        if (password.isEmpty()) {
            return SignInResult.Failed(AuthError.Rejected("Enter a password"))
        }
        return withTimeoutOrNull(deadlineMillis) {
            passwordLoginWithin(endpoint, username, password, provider)
        } ?: SignInResult.Failed(AuthError.Timeout)
    }

    /** Mint a fresh single-use ticket from the session cookie already in the shared jar. */
    suspend fun mintWsTicket(endpoint: Endpoint): TicketResult =
        withTimeoutOrNull(deadlineMillis) { mintWsTicketWithin(endpoint) }
            ?: TicketResult.Failed(AuthError.Timeout)

    /** Best-effort loopback token read from the served single-page app. */
    suspend fun extractLoopbackToken(endpoint: Endpoint): String? =
        withTimeoutOrNull(deadlineMillis) {
            try {
                fetchLoopbackToken(endpoint)
            } catch (_: IOException) {
                null
            }
        }

    private suspend fun probeWithin(endpoint: Endpoint): ProbeResult {
        val statusResponse =
            try {
                get(endpoint, "api/status")
            } catch (_: IOException) {
                return ProbeResult.Failed(AuthError.Unreachable())
            }
        statusResponse.use { response ->
            val body = response.readBody()
            if (response.code == 401) {
                val providers = fetchProviders(endpoint).orEmpty()
                return if (providers.isEmpty()) {
                    ProbeResult.Failed(
                        AuthError.Http(
                            code = 401,
                            message = "Dashboard requires sign-in but publishes no sign-in method",
                        ),
                    )
                } else {
                    ProbeResult.Gated(providers = providers, browserSupported = false)
                }
            }
            if (!response.isSuccessful) {
                return ProbeResult.Failed(httpError(response.code))
            }
            val root = parseObject(body) ?: return ProbeResult.Failed(invalidStatus())
            val authRequired = root.booleanOrNull("auth_required") ?: false
            val version = root.stringOrNull("version")
            val authFlows = stringArray(root["auth_flows"])
            if (!authRequired) {
                // Token extraction is a convenience: a slow SPA must not turn a reachable loopback
                // dashboard into a timeout. A manual token can still be pasted in Setup.
                val token =
                    try {
                        withTimeoutOrNull(TOKEN_EXTRACT_TIMEOUT_MILLIS) {
                            fetchLoopbackToken(endpoint)
                        }
                    } catch (_: IOException) {
                        null
                    }
                return ProbeResult.Open(token = token, version = version)
            }
            val providers =
                fetchProviders(endpoint)
                    ?: providersFromNames(stringArray(root["auth_providers"]))
            return ProbeResult.Gated(
                providers = providers,
                browserSupported = authFlows.contains(NATIVE_PKCE_FLOW),
            )
        }
    }

    private suspend fun passwordLoginWithin(
        endpoint: Endpoint,
        username: String,
        password: String,
        provider: String,
    ): SignInResult {
        val payload =
            buildJsonObject {
                put("provider", provider)
                put("username", username)
                put("password", password)
                put("next", "")
            }.toString()
        val request =
            Request
                .Builder()
                .url(endpoint.resolve("auth/password-login"))
                .header("Content-Type", JSON_CONTENT_TYPE)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        try {
            client.newCall(request).awaitResponse().use { response ->
                response.readBody()
                if (response.code == 401 || response.code == 403) {
                    return SignInResult.Failed(
                        AuthError.Rejected("Invalid username or password"),
                    )
                }
                if (!response.isSuccessful) {
                    return SignInResult.Failed(httpError(response.code))
                }
            }
        } catch (_: IOException) {
            return SignInResult.Failed(AuthError.Unreachable())
        }
        return when (val ticket = mintWsTicketWithin(endpoint)) {
            is TicketResult.Success -> SignInResult.Success(ticket.ticket)
            is TicketResult.Failed -> SignInResult.Failed(ticket.error)
        }
    }

    private suspend fun mintWsTicketWithin(endpoint: Endpoint): TicketResult {
        val request =
            Request
                .Builder()
                .url(endpoint.resolve("api/auth/ws-ticket"))
                .header("Content-Type", JSON_CONTENT_TYPE)
                .post("{}".toRequestBody(JSON_MEDIA_TYPE))
                .build()
        try {
            client.newCall(request).awaitResponse().use { response ->
                val body = response.readBody()
                if (!response.isSuccessful) {
                    val message =
                        when (response.code) {
                            401, 403 -> "Sign-in expired — sign in again"
                            else -> "Dashboard could not issue a live ticket (HTTP ${response.code})"
                        }
                    return TicketResult.Failed(AuthError.Http(response.code, message))
                }
                val ticket = parseObject(body)?.stringOrNull("ticket")
                return if (ticket.isNullOrBlank()) {
                    TicketResult.Failed(
                        AuthError.InvalidResponse("Dashboard did not return a live ticket"),
                    )
                } else {
                    TicketResult.Success(ticket)
                }
            }
        } catch (_: IOException) {
            return TicketResult.Failed(AuthError.Unreachable())
        }
    }

    private suspend fun fetchLoopbackToken(endpoint: Endpoint): String? {
        val request =
            Request
                .Builder()
                .url(endpoint.baseUrl)
                .get()
                .build()
        client.newCall(request).awaitResponse().use { response ->
            if (!response.isSuccessful) return null
            val body = response.readBody()
            return TOKEN_PATTERN
                .find(body)
                ?.groupValues
                ?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
        }
    }

    private suspend fun fetchProviders(endpoint: Endpoint): List<AuthProvider>? {
        val request =
            Request
                .Builder()
                .url(endpoint.resolve("api/auth/providers"))
                .header("Accept", JSON_CONTENT_TYPE)
                .get()
                .build()
        return try {
            client.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) return null
                parseProviders(response.readBody())
            }
        } catch (_: IOException) {
            null
        }
    }

    private suspend fun get(
        endpoint: Endpoint,
        path: String,
    ): okhttp3.Response {
        val request =
            Request
                .Builder()
                .url(endpoint.resolve(path))
                .header("Accept", JSON_CONTENT_TYPE)
                .get()
                .build()
        return client.newCall(request).awaitResponse()
    }

    private fun parseProviders(body: String): List<AuthProvider>? {
        val element = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val array =
            when (element) {
                is JsonArray -> element
                is JsonObject -> element["providers"] as? JsonArray
                else -> null
            } ?: return null
        return array
            .mapNotNull { item ->
                val objectItem = item as? JsonObject ?: return@mapNotNull null
                val name = objectItem.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (name == DRAIN_PROVIDER) return@mapNotNull null
                AuthProvider(
                    name = name,
                    displayName = objectItem.stringOrNull("display_name")?.takeIf { it.isNotBlank() } ?: name,
                    supportsPassword = objectItem.booleanOrNull("supports_password") ?: false,
                )
            }
    }

    private fun providersFromNames(names: List<String>): List<AuthProvider> =
        names
            .filter { it.isNotBlank() && it != DRAIN_PROVIDER }
            .map { name ->
                AuthProvider(
                    name = name,
                    displayName = name,
                    supportsPassword = name == DEFAULT_PROVIDER,
                )
            }

    private fun parseObject(body: String): JsonObject? = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun stringArray(element: kotlinx.serialization.json.JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { it.asStringOrNull() } ?: emptyList()

    private fun invalidStatus(): AuthError = AuthError.InvalidResponse("Dashboard returned an unexpected status reply")

    private fun httpError(code: Int): AuthError {
        val message =
            when (code) {
                401, 403 -> "Dashboard rejected the request — check sign-in"
                404 -> "Dashboard address is not a Hermes dashboard"
                in 500..599 -> "Dashboard error (HTTP $code)"
                else -> "Dashboard returned HTTP $code"
            }
        return AuthError.Http(code = code, message = message)
    }

    companion object {
        const val DEFAULT_DEADLINE_MILLIS: Long = 25_000L
        const val DEFAULT_PROVIDER: String = "basic"
        private const val TOKEN_EXTRACT_TIMEOUT_MILLIS: Long = 5_000L
        private const val DRAIN_PROVIDER = "drain-secret"
        private const val NATIVE_PKCE_FLOW = "native_pkce"
        private const val JSON_CONTENT_TYPE = "application/json"
        private val JSON_MEDIA_TYPE = JSON_CONTENT_TYPE.toMediaType()
        private val TOKEN_PATTERN = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""")
    }
}
