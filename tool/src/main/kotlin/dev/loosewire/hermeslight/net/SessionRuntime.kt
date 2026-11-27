/*
 * Copyright 2026 LooseWireDev
 *
 * Stored/runtime session identity and the latest-descendant contract follow Hermes Mobile
 * (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Foreground-scope owned; replace on profile/connection changes, never persist runtime ids. */
internal class SessionRuntime(
    private val resolve: suspend (String) -> String,
    private val request: suspend (JsonObject) -> JsonElement?,
) {
    private val currentIds = mutableMapOf<String, String>()
    private val storedIds = mutableMapOf<String, String>()

    fun runtimeId(storedId: String): String = currentIds[storedId] ?: storedId

    // Retain old aliases until reconnect: an earlier turn may still emit its final events.
    fun storedId(runtimeId: String): String = storedIds[runtimeId] ?: runtimeId

    suspend fun submit(
        storedId: String,
        text: String,
        queued: Boolean,
    ): JsonElement? {
        val current = currentIds[storedId] ?: refresh(storedId)
        try {
            return submitTo(current, text, queued)
        } catch (error: GatewayRpcException) {
            if (!error.isSessionNotFound()) throw error
        }
        // Only an explicit refusal is safe to retry. Unknown transport outcomes pass through.
        currentIds.remove(storedId)
        val refreshed = refresh(storedId)
        try {
            return submitTo(refreshed, text, queued)
        } catch (error: GatewayRpcException) {
            if (!error.isSessionNotFound()) throw error
            currentIds.remove(storedId)
            throw GatewayRpcException(
                message = "Session not found after refreshing its current id — reopen from Sessions or create on desktop",
                kind = RpcFailureKind.REJECTED,
                code = 4001,
                cause = error,
            )
        }
    }

    private suspend fun refresh(storedId: String): String {
        val current = resolve(storedId)
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
