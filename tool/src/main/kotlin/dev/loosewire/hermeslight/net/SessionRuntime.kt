/*
 * Copyright 2026 LooseWireDev
 *
 * Stored/runtime session identity, latest-descendant and resume contracts follow Hermes Mobile
 * (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Foreground-scope owned; replace on profile/connection changes, never persist runtime ids. */
internal class SessionRuntime(
    private val resolve: suspend (String) -> String,
    private val resumeSession: suspend (String) -> String,
    private val request: suspend (JsonObject) -> JsonElement?,
) {
    private val currentIds = mutableMapOf<String, String>()
    private val storedIds = mutableMapOf<String, String>()
    private val resumeMutex = Mutex()

    fun runtimeId(storedId: String): String = currentIds[storedId] ?: storedId

    // Retain old aliases until reconnect: an earlier turn may still emit its final events.
    fun storedId(runtimeId: String): String = storedIds[runtimeId] ?: runtimeId

    /**
     * A freshly created session is already live: its minted id is both the stored identity and
     * the runtime id. Seeding both maps lets an immediate open or first submit use it as-is,
     * with no descendant lookup or resume. A later 4001 still invalidates it like any runtime id.
     */
    fun registerCreated(id: String) {
        require(id.isNotBlank()) { "created session id must not be blank" }
        currentIds[id] = id
        storedIds[id] = id
    }

    /** Opening a thread and its first submit share one resume, even when they overlap. */
    suspend fun resume(storedId: String): String =
        resumeMutex.withLock {
            currentIds[storedId] ?: refresh(storedId)
        }

    suspend fun submit(
        storedId: String,
        text: String,
        queued: Boolean,
    ): JsonElement? {
        val current = resume(storedId)
        try {
            return submitTo(current, text, queued)
        } catch (error: GatewayRpcException) {
            if (!error.isSessionNotFound()) throw error
        }
        // Only an explicit refusal is safe to retry. Unknown transport outcomes pass through.
        val refreshed =
            resumeMutex.withLock {
                val cached = currentIds[storedId]
                if (cached != null && cached != current) {
                    cached
                } else {
                    currentIds.remove(storedId)
                    refresh(storedId)
                }
            }
        try {
            return submitTo(refreshed, text, queued)
        } catch (error: GatewayRpcException) {
            if (!error.isSessionNotFound()) throw error
            resumeMutex.withLock {
                if (currentIds[storedId] == refreshed) currentIds.remove(storedId)
            }
            throw GatewayRpcException(
                message = "Session not found after resuming again — reopen from Sessions or create on desktop",
                kind = RpcFailureKind.REJECTED,
                code = 4001,
                cause = error,
            )
        }
    }

    private suspend fun refresh(storedId: String): String {
        // descendant (stored tip) → resume (runtime id) → submit; REST alone never creates a runtime.
        val tip = resolve(storedId)
        val current =
            try {
                resumeSession(tip)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A resume timeout is not an unknown prompt outcome: no prompt was sent yet.
                throw IllegalStateException(
                    "Session resume failed; prompt was not sent: ${error.message ?: "dashboard unavailable"}",
                    error,
                )
            }
        currentIds[storedId] = current
        storedIds[current] = storedId
        return current
    }

    private suspend fun submitTo(
        session: String,
        text: String,
        queued: Boolean,
    ): JsonElement? =
        request(
            buildJsonObject {
                put("session_id", session)
                put("text", text)
                if (queued) put("queued", true)
            },
        )
}

private fun GatewayRpcException.isSessionNotFound(): Boolean =
    kind == RpcFailureKind.REJECTED && code == 4001 && message.orEmpty().contains("session not found", ignoreCase = true)
