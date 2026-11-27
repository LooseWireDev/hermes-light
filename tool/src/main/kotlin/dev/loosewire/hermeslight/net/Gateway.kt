/*
 * Copyright 2026 LooseWireDev
 *
 * Gateway handshake, reconnect, and event vocabulary are derived from Hermes
 * Mobile (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.net

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.ln

/** Connection state rendered by the app's status line. */
sealed interface GatewayState {
    data object Disconnected : GatewayState

    data object Connecting : GatewayState

    data object Connected : GatewayState

    data class Reconnecting(
        val attempt: Int,
        val delayMillis: Long,
        val lastError: String? = null,
    ) : GatewayState

    /** The dashboard rejected the credential; automatic retries stop until the app reconnects. */
    data class AuthFailed(
        val message: String,
    ) : GatewayState
}

/** Everything needed to open one WebSocket connection. */
data class GatewayConnection(
    val endpoint: Endpoint,
    val authParam: WsAuthParam,
    val credential: String,
    /**
     * Gated mode mints a single-use ticket per connection. When present this is called before
     * every dial; returning null/blank is a connection failure and the gateway backs off.
     */
    val credentialProvider: (suspend () -> String?)? = null,
)

data class GatewayConfig(
    val initialBackoffMillis: Long = 1_000L,
    val maxBackoffMillis: Long = 30_000L,
    val factor: Double = 2.0,
    val handshakeTimeoutMillis: Long = 15_000L,
) {
    init {
        require(initialBackoffMillis > 0) { "initialBackoffMillis must be positive" }
        require(maxBackoffMillis >= initialBackoffMillis) {
            "maxBackoffMillis must be at least initialBackoffMillis"
        }
        require(factor >= 1.0) { "Backoff factor must be at least 1" }
        require(handshakeTimeoutMillis > 0) { "handshakeTimeoutMillis must be positive" }
    }
}

private const val MAX_BACKOFF_STEPS = 64

internal fun backoffDelayMillis(
    config: GatewayConfig,
    attempt: Int,
): Long {
    require(attempt >= 1) { "attempt must be at least 1" }
    if (config.factor == 1.0) return config.initialBackoffMillis
    // Cost is bounded by the number of doublings needed to reach the ceiling, not by the total
    // number of retries: a phone offline for weeks does not walk thousands of steps per attempt.
    val ratio = config.maxBackoffMillis.toDouble() / config.initialBackoffMillis
    val maxSteps =
        ceil(ln(ratio) / ln(config.factor))
            .toInt()
            .coerceIn(0, MAX_BACKOFF_STEPS)
    val steps = minOf(attempt - 1, maxSteps)
    var delay = config.initialBackoffMillis.toDouble()
    repeat(steps) {
        delay = (delay * config.factor).coerceAtMost(config.maxBackoffMillis.toDouble())
    }
    return delay.toLong().coerceIn(1L, config.maxBackoffMillis)
}

/** Rejection/no-connection are known failures; timeout/connection-loss leave the outcome unknown. */
enum class RpcFailureKind {
    /** No live socket, so the command was never sent. */
    NOT_CONNECTED,

    /** No reply arrived in time. The dashboard may still have applied the command. */
    TIMEOUT,

    /** The dashboard answered with a JSON-RPC error; the command was refused. */
    REJECTED,

    /** The socket was replaced, backgrounded, or lost after the command was sent. */
    CONNECTION_LOST,
}

/**
 * Honest, user-showable failure for one JSON-RPC command. The message names the method and the
 * outcome, never the params (which can carry prompt text).
 */
class GatewayRpcException(
    message: String,
    val kind: RpcFailureKind,
    val code: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * JSON-RPC gateway client.
 *
 * The dashboard's first frame is `gateway.ready`; it is the handshake. On that frame this client
 * sends `client.capabilities` with its identity and `server_requests: false`, then reports
 * [GatewayState.Connected]. The client never answers a same-id server request; approval and clarify
 * prompts surface as events and are answered with explicit `approval.respond` / `clarify.respond`
 * calls through [request].
 *
 * Reconnects back off exponentially while the app is foregrounded and the scope is alive. Listener
 * callbacks are dispatched on [scope], so a main-dispatcher scope keeps UI state on the main thread.
 *
 * [request] sends one command and waits for its correlated reply id. Commands are never replayed:
 * a replacement connection, backgrounding, disconnect, or transport failure fails outstanding
 * commands with [RpcFailureKind.CONNECTION_LOST] and an honest "outcome unknown" message, and a
 * timeout bounds how long a caller waits.
 */
class Gateway(
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val client: OkHttpClient = defaultHttpClient(),
    private val config: GatewayConfig = GatewayConfig(),
) {
    interface Listener {
        fun onState(state: GatewayState)

        fun onEvent(event: GatewayEvent)
    }

    private val lock = Any()

    @Volatile
    private var state: GatewayState = GatewayState.Disconnected

    private var target: GatewayConnection? = null
    private var socket: WebSocket? = null
    private var generation: Int = 0
    private var stateVersion: Int = 0
    private var attempt: Int = 0
    private var reconnectToken: Int = 0
    private var reconnectJob: Job? = null
    private var handshakeJob: Job? = null
    private var dialJob: Job? = null
    private var capabilitiesSentGeneration: Int = -1
    private var connected: Boolean = false
    private var foreground: Boolean = true
    private var manualDisconnect: Boolean = false
    private var requestSequence: Long = 0
    private val pendingCalls = mutableMapOf<String, PendingCall>()

    fun connect(connection: GatewayConnection) {
        val previous: WebSocket?
        val outstanding: List<PendingCall>
        synchronized(lock) {
            target = connection
            manualDisconnect = false
            attempt = 0
            connected = false
            generation += 1
            reconnectToken += 1
            reconnectJob?.cancel()
            reconnectJob = null
            handshakeJob?.cancel()
            handshakeJob = null
            dialJob?.cancel()
            dialJob = null
            capabilitiesSentGeneration = -1
            outstanding = takePendingCallsLocked()
            previous = socket
            socket = null
        }
        failPendingCalls(outstanding, "the connection being replaced")
        previous?.close(CLOSE_NORMAL, "Reconnecting")
        if (foreground) {
            setState(GatewayState.Connecting)
            openSocket()
        } else {
            setState(GatewayState.Disconnected)
        }
    }

    fun reconnect() {
        val connection =
            synchronized(lock) {
                if (target == null) return
                manualDisconnect = false
                target
            } ?: return
        if (!foreground) return
        connect(connection)
    }

    /**
     * Send one JSON-RPC command and wait for its correlated reply.
     *
     * Returns the reply's `result` (null when the gateway sent none). A same-id reply that arrives
     * after its call was failed or that belongs to another socket generation is ignored. The
     * command is never replayed: callers must decide whether to resend after a failure. Failures
     * throw [GatewayRpcException]; [timeoutMillis] bounds the wait.
     */
    suspend fun request(
        method: String,
        params: JsonObject = JsonObject(emptyMap()),
        timeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    ): JsonElement? {
        require(method.isNotBlank()) { "method must not be blank" }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        val id: String
        val socketGeneration: Int
        val webSocket: WebSocket
        synchronized(lock) {
            val active = socket
            if (!connected || !foreground || manualDisconnect || active == null) {
                throw GatewayRpcException(
                    "Not connected to the dashboard — $method was not sent",
                    RpcFailureKind.NOT_CONNECTED,
                )
            }
            id = "hl-${++requestSequence}"
            socketGeneration = generation
            webSocket = active
        }
        val frame =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString()
        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine<JsonElement?> { continuation ->
                    val call = PendingCall(method, socketGeneration, continuation)
                    val registered =
                        synchronized(lock) {
                            if (connected &&
                                foreground &&
                                !manualDisconnect &&
                                socketGeneration == generation &&
                                socket === webSocket
                            ) {
                                pendingCalls[id] = call
                                true
                            } else {
                                false
                            }
                        }
                    if (!registered) {
                        continuation.resumeWithException(
                            GatewayRpcException(
                                "Connection changed before $method was sent",
                                RpcFailureKind.NOT_CONNECTED,
                            ),
                        )
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation {
                        synchronized(lock) { pendingCalls.remove(id) }
                    }
                    if (!webSocket.send(frame)) {
                        val removed = synchronized(lock) { pendingCalls.remove(id) }
                        if (removed != null) {
                            removed.fail(
                                GatewayRpcException(
                                    "$method could not be sent — outcome unknown",
                                    RpcFailureKind.CONNECTION_LOST,
                                ),
                            )
                        }
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            // Only this call's own deadline becomes a typed outcome; an outer cancellation stays
            // cancellation so structured concurrency still works.
            if (!currentCoroutineContext().isActive) throw timeout
            synchronized(lock) { pendingCalls.remove(id) }
            throw GatewayRpcException(
                "$method timed out after ${timeoutMillis}ms — outcome unknown",
                RpcFailureKind.TIMEOUT,
            )
        }
    }

    /**
     * Stops the socket without emitting a state callback. A caller that just failed setup already
     * shows its own specific error; a queued "disconnected" callback must not overwrite it.
     * Background/foreground disconnects use [onForeground] and do update the status line.
     */
    fun disconnect() {
        val previous: WebSocket?
        val outstanding: List<PendingCall>
        synchronized(lock) {
            manualDisconnect = true
            generation += 1
            stateVersion += 1
            state = GatewayState.Disconnected
            reconnectToken += 1
            reconnectJob?.cancel()
            reconnectJob = null
            handshakeJob?.cancel()
            handshakeJob = null
            dialJob?.cancel()
            dialJob = null
            connected = false
            outstanding = takePendingCallsLocked()
            previous = socket
            socket = null
        }
        failPendingCalls(outstanding, "a disconnect")
        previous?.close(CLOSE_NORMAL, "Client closed")
    }

    /** Closing in the background is deliberate: no socket, no retries, an honest disconnected line. */
    fun onForeground(value: Boolean) {
        val closeSocket: WebSocket?
        val outstanding: List<PendingCall>
        val resume: Boolean
        synchronized(lock) {
            if (foreground == value) return
            foreground = value
            if (value) {
                closeSocket = null
                outstanding = emptyList()
                resume =
                    target != null &&
                    !manualDisconnect &&
                    !connected &&
                    state !is GatewayState.AuthFailed
            } else {
                generation += 1
                reconnectToken += 1
                reconnectJob?.cancel()
                reconnectJob = null
                handshakeJob?.cancel()
                handshakeJob = null
                dialJob?.cancel()
                dialJob = null
                connected = false
                outstanding = takePendingCallsLocked()
                closeSocket = socket
                socket = null
                resume = false
            }
        }
        failPendingCalls(outstanding, "the app going to the background")
        closeSocket?.close(CLOSE_NORMAL, "Background")
        if (!value) {
            setState(GatewayState.Disconnected)
        } else if (resume) {
            setState(GatewayState.Connecting)
            openSocket()
        }
    }

    private fun openSocket() {
        val connection: GatewayConnection
        val socketGeneration: Int
        synchronized(lock) {
            if (manualDisconnect || !foreground) return
            connection = target ?: return
            socketGeneration = ++generation
            connected = false
        }
        setState(GatewayState.Connecting)
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                val credential =
                    try {
                        connection.credentialProvider?.invoke() ?: connection.credential
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        null
                    }
                if (!isCurrent(socketGeneration)) return@launch
                if (credential.isNullOrBlank()) {
                    handleTransportFailure(
                        generation = socketGeneration,
                        message = "Could not obtain a live session credential",
                    )
                    return@launch
                }
                val url = connection.endpoint.webSocketUrl(connection.authParam, credential)
                val newSocket =
                    client.newWebSocket(Request.Builder().url(url).build(), SocketListener(socketGeneration))
                val keep: Boolean
                synchronized(lock) {
                    keep = socketGeneration == generation && !manualDisconnect
                    if (keep) socket = newSocket
                }
                if (!keep) {
                    newSocket.cancel()
                    return@launch
                }
                startHandshakeWatchdog(socketGeneration)
            }
        val started =
            synchronized(lock) {
                if (socketGeneration != generation || manualDisconnect || !foreground) {
                    false
                } else {
                    dialJob?.cancel()
                    dialJob = job
                    true
                }
            }
        if (!started) {
            job.cancel()
            return
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (dialJob === job) dialJob = null
            }
        }
        job.start()
    }

    private fun startHandshakeWatchdog(socketGeneration: Int) {
        synchronized(lock) {
            handshakeJob?.cancel()
            handshakeJob =
                scope.launch {
                    delay(config.handshakeTimeoutMillis)
                    if (isCurrent(socketGeneration) && !isConnected(socketGeneration)) {
                        handleTransportFailure(
                            generation = socketGeneration,
                            message = "Live connection timed out",
                        )
                    }
                }
        }
    }

    private fun markConnected(socketGeneration: Int) {
        synchronized(lock) {
            if (socketGeneration != generation) return
            connected = true
            attempt = 0
            handshakeJob?.cancel()
            handshakeJob = null
        }
        setState(GatewayState.Connected)
    }

    private fun sendCapabilities(
        webSocket: WebSocket,
        socketGeneration: Int,
    ) {
        synchronized(lock) {
            if (socketGeneration != generation || capabilitiesSentGeneration == socketGeneration) return
            capabilitiesSentGeneration = socketGeneration
        }
        val frame =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", "capabilities-$socketGeneration")
                put("method", "client.capabilities")
                put(
                    "params",
                    buildJsonObject {
                        put("server_requests", false)
                        put(
                            "client",
                            buildJsonObject {
                                put("name", CLIENT_NAME)
                                put("kind", "mobile")
                            },
                        )
                    },
                )
            }.toString()
        webSocket.send(frame)
    }

    private fun handleTransportFailure(
        generation: Int,
        message: String,
        authFailure: Boolean = false,
    ) {
        val socketToCancel: WebSocket?
        val outstanding: List<PendingCall>
        synchronized(lock) {
            if (generation != this.generation) return
            this.generation += 1
            socketToCancel = socket
            socket = null
            connected = false
            outstanding = takePendingCallsLocked()
            handshakeJob?.cancel()
            handshakeJob = null
            reconnectJob?.cancel()
            reconnectJob = null
            dialJob?.cancel()
            dialJob = null
        }
        failPendingCalls(outstanding, "the connection closing")
        socketToCancel?.cancel()
        if (authFailure) {
            setState(GatewayState.AuthFailed(message))
        } else {
            scheduleReconnect(message)
        }
    }

    private fun scheduleReconnect(lastError: String) {
        val attemptNumber: Int
        val delayMillis: Long
        val token: Int
        synchronized(lock) {
            if (manualDisconnect || !foreground || target == null || state is GatewayState.AuthFailed) return
            attempt += 1
            attemptNumber = attempt
            delayMillis = backoffDelayMillis(config, attempt)
            token = ++reconnectToken
            reconnectJob?.cancel()
            reconnectJob =
                scope.launch {
                    delay(delayMillis)
                    val shouldOpen =
                        synchronized(lock) {
                            if (token != reconnectToken) {
                                false
                            } else {
                                reconnectJob = null
                                !manualDisconnect && foreground && target != null
                            }
                        }
                    if (shouldOpen) openSocket()
                }
        }
        setState(
            GatewayState.Reconnecting(
                attempt = attemptNumber,
                delayMillis = delayMillis,
                lastError = lastError,
            ),
        )
    }

    private fun isCurrent(socketGeneration: Int): Boolean =
        synchronized(lock) {
            socketGeneration == generation && !manualDisconnect && foreground
        }

    private fun isConnected(socketGeneration: Int): Boolean = synchronized(lock) { socketGeneration == generation && connected }

    /** Must be called with [lock] held: snapshot and forget every in-flight command. */
    private fun takePendingCallsLocked(): List<PendingCall> {
        if (pendingCalls.isEmpty()) return emptyList()
        val calls = pendingCalls.values.toList()
        pendingCalls.clear()
        return calls
    }

    private fun failPendingCalls(
        calls: List<PendingCall>,
        reason: String,
    ) {
        calls.forEach { call ->
            call.fail(
                GatewayRpcException(
                    "${call.method} interrupted by $reason — outcome unknown",
                    RpcFailureKind.CONNECTION_LOST,
                ),
            )
        }
    }

    /**
     * Remove the call a reply belongs to. An id from another socket generation, an already-failed
     * call, or an unsolicited reply (such as the capabilities ack) matches nothing.
     */
    private fun takePendingReply(
        id: String,
        socketGeneration: Int,
    ): PendingCall? =
        synchronized(lock) {
            val pending = pendingCalls[id] ?: return@synchronized null
            if (pending.generation != socketGeneration) return@synchronized null
            pendingCalls.remove(id)
        }

    private fun completeResult(
        id: String,
        result: JsonElement?,
        socketGeneration: Int,
    ) {
        takePendingReply(id, socketGeneration)?.succeed(result)
    }

    private fun completeError(
        id: String,
        error: GatewayRpcError,
        socketGeneration: Int,
    ) {
        val call = takePendingReply(id, socketGeneration) ?: return
        val codeText = error.code?.let { " (code $it)" }.orEmpty()
        val detail =
            error.message
                ?.takeIf { it.isNotBlank() }
                ?.let { ": $it" }
                .orEmpty()
        call.fail(
            GatewayRpcException(
                "Dashboard rejected ${call.method}$codeText$detail",
                RpcFailureKind.REJECTED,
                error.code,
            ),
        )
    }

    private fun setState(next: GatewayState) {
        val version: Int
        synchronized(lock) {
            if (state == next) return
            state = next
            version = ++stateVersion
        }
        scope.launch {
            // A newer state already superseded this callback: dropping it keeps a queued
            // Disconnected/Connecting from overwriting the final error line.
            val current = synchronized(lock) { version == stateVersion }
            if (current) runCatching { listener.onState(next) }
        }
    }

    private class PendingCall(
        val method: String,
        val generation: Int,
        private val continuation: CancellableContinuation<JsonElement?>,
    ) {
        fun succeed(result: JsonElement?) {
            continuation.resumeWith(Result.success(result))
        }

        fun fail(exception: GatewayRpcException) {
            continuation.resumeWith(Result.failure(exception))
        }
    }

    private fun emit(
        event: GatewayEvent,
        socketGeneration: Int,
    ) {
        scope.launch {
            // Events from a socket that has since been replaced or closed must not mutate the new
            // endpoint's thread. isCurrent also checks foreground and manual disconnect.
            if (!isCurrent(socketGeneration)) return@launch
            runCatching { listener.onEvent(event) }
        }
    }

    private inner class SocketListener(
        private val socketGeneration: Int,
    ) : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            if (!isCurrent(socketGeneration)) {
                webSocket.close(CLOSE_NORMAL, "Superseded")
            }
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (!isCurrent(socketGeneration)) return
            when (val frame = GatewayEventParser.parse(text)) {
                is ParsedFrame.Event -> {
                    if (frame.event is GatewayEvent.Ready) {
                        sendCapabilities(webSocket, socketGeneration)
                        markConnected(socketGeneration)
                    }
                    emit(frame.event, socketGeneration)
                }

                is ParsedFrame.Response -> completeResult(frame.id, frame.result, socketGeneration)

                is ParsedFrame.ResponseError -> completeError(frame.id, frame.error, socketGeneration)

                is ParsedFrame.ServerRequest -> {
                    // The client keeps server_requests:false and never answers a same-id request.
                    // Approval/clarify prompts surface as events; the app answers them with an
                    // explicit approval.respond / clarify.respond call through request().
                    val event = frame.event ?: GatewayEvent.Unknown(frame.method, text)
                    emit(event, socketGeneration)
                }

                is ParsedFrame.Invalid -> Unit
            }
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            webSocket.close(CLOSE_NORMAL, null)
        }

        override fun onClosed(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            if (!isCurrent(socketGeneration)) return
            if (isAuthClose(code, reason)) {
                handleTransportFailure(
                    generation = socketGeneration,
                    message = "Dashboard rejected the live connection — sign in again",
                    authFailure = true,
                )
            } else {
                handleTransportFailure(
                    generation = socketGeneration,
                    message = "Live connection closed (code $code)",
                )
            }
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            if (!isCurrent(socketGeneration)) return
            val code = response?.code
            if (code == 401 || code == 403) {
                handleTransportFailure(
                    generation = socketGeneration,
                    message = "Dashboard rejected the live connection — sign in again",
                    authFailure = true,
                )
                return
            }
            val message =
                when {
                    code != null -> "Live connection refused (HTTP $code)"
                    t.message.isNullOrBlank() -> "Live connection failed"
                    else -> "Live connection failed — ${redactTransportError(t.message.orEmpty())}"
                }
            handleTransportFailure(generation = socketGeneration, message = message)
        }
    }

    private fun isAuthClose(
        code: Int,
        reason: String,
    ): Boolean =
        code == 4401 ||
            code == 4403 ||
            reason.contains("unauthorized", ignoreCase = true) ||
            reason.startsWith("auth:", ignoreCase = true)

    private fun redactTransportError(message: String): String =
        CREDENTIAL_QUERY.replace(message.replace(CREDENTIAL_WORD, "$1=REDACTED"), "<redacted>")

    companion object {
        private const val CLIENT_NAME = "hermes-light"
        private const val CLOSE_NORMAL = 1000

        /** Default bound on one [request]; after it the command's outcome is unknown. */
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 20_000L

        private val CREDENTIAL_WORD = Regex("""\b(token|ticket)=[^&\s]+""")
        private val CREDENTIAL_QUERY = Regex("""[?&](token|ticket)=[^&\s]+""")
    }
}
