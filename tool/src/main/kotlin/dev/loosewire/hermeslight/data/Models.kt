/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Dashboard shapes are derived from Hermes Mobile
 * (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.data

import kotlinx.serialization.Serializable

/** How the stored profile authenticates against the dashboard. */
@Serializable
enum class AuthMode {
    /** Loopback/ungated dashboard: the SPA token rides the WebSocket query. */
    TOKEN,

    /** Gated dashboard: username/password login plus a short-lived WS ticket. */
    PASSWORD,
}

/**
 * Persisted connection profile. Encrypted at rest by [ProfileStore]; never written as plain JSON.
 *
 * [token] is the loopback session token, [password] the gated login secret. Only one mode's
 * credential is meaningful, but keeping both lets the setup screen switch modes without losing the
 * typed value.
 *
 * [readPositions] maps a session id to the last visible transcript row id; [readOffsets] keeps the
 * pixel offset within that row so reopening a thread restores both the anchor and the scroll.
 */
@Serializable
data class Profile(
    val endpoint: String,
    val authMode: AuthMode = AuthMode.TOKEN,
    val token: String? = null,
    val username: String? = null,
    val password: String? = null,
    val provider: String? = null,
    val botProfile: String? = null,
    val selectedSessionId: String? = null,
    val readPositions: Map<String, Int> = emptyMap(),
    val readOffsets: Map<String, Int> = emptyMap(),
)

/** A dashboard session row as shown by the Sessions screen. */
data class SessionSummary(
    val id: String,
    val title: String,
    val source: String?,
    /** Dashboard profile label when the backend sends one; bots stay source-scoped otherwise. */
    val profile: String?,
    val preview: String?,
    val displayName: String?,
    val model: String?,
    val messageCount: Int?,
    val status: String?,
    val updatedAtEpochMs: Long?,
    val unread: Boolean?,
    val pinned: Boolean,
    val archived: Boolean,
)

/** One page of `GET api/sessions`. */
data class SessionPage(
    val sessions: List<SessionSummary>,
    val total: Int,
    val limit: Int,
    val offset: Int,
    val hasMore: Boolean,
)

/** `GET api/sessions/{id}` fields the phone renders. */
data class SessionDetail(
    val id: String,
    val sessionKey: String?,
    val title: String?,
    val status: String?,
    val messageCount: Int?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val updatedAtEpochMs: Long?,
)

/**
 * One transcript row. [text] is the display projection when the dashboard supplied one, otherwise
 * the raw content flattened from string or text-part arrays.
 *
 * [rowId] is the gateway's durable SQLite row id when it sent one (always positive). Older backends
 * omit ids; those rows get a stable negative synthetic id derived from the page offset/index so list
 * keys stay unique and refetching the same page deduplicates. Negative ids are display-only: passing
 * one to `messagesAround` is refused because the dashboard cannot address it.
 */
data class TranscriptMessage(
    val rowId: Int,
    val role: String,
    val text: String,
    val reasoning: String?,
    val type: String?,
    val toolCallId: String?,
    val timestampEpochMs: Long?,
    val displayKind: String?,
)

/** One chronological page of `GET api/sessions/{id}/messages`. */
data class MessagePage(
    val messages: List<TranscriptMessage>,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val hasOlder: Boolean,
)

/** A bounded window returned by `GET api/sessions/{id}/messages/around`. */
data class MessageWindow(
    val messages: List<TranscriptMessage>,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
    val total: Int?,
)
