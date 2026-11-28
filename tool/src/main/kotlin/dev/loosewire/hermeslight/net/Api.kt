/*
 * Copyright 2026 LooseWireDev
 *
 * REST contracts and tolerant field handling are derived from Hermes Mobile
 * (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.net

import dev.loosewire.hermeslight.data.MessagePage
import dev.loosewire.hermeslight.data.MessageWindow
import dev.loosewire.hermeslight.data.SessionDetail
import dev.loosewire.hermeslight.data.SessionPage
import dev.loosewire.hermeslight.data.SessionSummary
import dev.loosewire.hermeslight.data.TranscriptMessage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

@Serializable
data class CronJobSummary(
    val id: String,
    val name: String,
    val state: String? = null,
)

enum class ApiErrorKind {
    UNAUTHORIZED,
    HTTP,
    NETWORK,
    INVALID_RESPONSE,

    /** The dashboard cannot answer this request (for example, row-id paging without row ids). */
    UNSUPPORTED,
}

/** Honest, user-showable REST failure. Messages never contain request headers or credentials. */
class ApiException(
    message: String,
    val kind: ApiErrorKind,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Dashboard REST for sessions, menu snapshots and voice transcription.
 *
 * Loopback token mode authenticates REST with `Authorization: Bearer <token>` (the dashboard
 * rejects a cookie-only request there); gated password mode passes no bearer token and relies on
 * the session cookie held by the shared [OkHttpClient] cookie jar.
 */
class Api(
    private val client: OkHttpClient = defaultHttpClient(),
    private val json: Json = hermesJson,
    private val tokenProvider: () -> String? = { null },
) {
    /** Empty speech is a successful empty transcript; malformed text is a wire failure. */
    suspend fun transcribe(
        endpoint: Endpoint,
        dataUrl: String,
    ): String {
        val payload =
            buildJsonObject {
                put("data_url", dataUrl)
                put("mime_type", "audio/wav")
            }.toString()
        val builder =
            Request
                .Builder()
                .url(endpoint.resolve("api/audio/transcribe"))
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json".toMediaType()))
        tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
            builder.header("Authorization", "Bearer $token")
        }
        try {
            client.newCall(builder.build()).awaitResponse().use { response ->
                val body = response.readBody()
                if (!response.isSuccessful) throw httpError(response.code)
                return decode<AudioTranscriptWire>(body).transcript.trim()
            }
        } catch (exception: IOException) {
            throw ApiException(
                message = "Dashboard unreachable — check address",
                kind = ApiErrorKind.NETWORK,
                cause = exception,
            )
        }
    }

    /** Read-only menu snapshot; zero is shown only after a successful jobs response. */
    suspend fun cronJobs(
        endpoint: Endpoint,
        profile: String? = null,
    ): List<CronJobSummary> = decode(getJson(endpoint, emptyList(), mapOf("profile" to profile), basePath = "api/cron/jobs"))

    suspend fun sessions(
        endpoint: Endpoint,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
        order: String = "recent",
        source: String? = null,
        excludeSources: String? = "cron",
        archived: String? = null,
        profile: String? = null,
    ): SessionPage {
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
        require(offset >= 0) { "offset must not be negative" }
        val body =
            getJson(
                endpoint,
                emptyList(),
                mapOf(
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                    "order" to order,
                    "source" to source,
                    "exclude_sources" to excludeSources,
                    "archived" to archived,
                    "profile" to profile,
                ),
            )
        val wire = decode<SessionListWire>(body)
        val sessions = sessionRows(wire)
        val total = wire.total ?: 0
        val pageLimit = wire.limit ?: limit
        val pageOffset = wire.offset ?: offset
        return SessionPage(
            sessions = sessions,
            total = total,
            limit = pageLimit,
            offset = pageOffset,
            hasMore = pageHasMore(total, pageOffset, sessions.size, pageLimit),
        )
    }

    /**
     * Server-wide session search. The dashboard may answer with the list envelope (`sessions`) or
     * the reference search envelope (`results` + `has_more`); rows map through the same projection,
     * deduplicated by id. [SessionPage.total] is the response total when supplied, otherwise the
     * number of unique rows returned.
     */
    suspend fun searchSessions(
        endpoint: Endpoint,
        query: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
        profile: String? = null,
        source: String? = null,
    ): SessionPage {
        require(query.isNotBlank()) { "Search query must not be blank" }
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
        require(offset >= 0) { "offset must not be negative" }
        val body =
            getJson(
                endpoint,
                emptyList(),
                mapOf(
                    "q" to query,
                    "profile" to profile,
                    "source" to source,
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                ),
                basePath = "api/sessions/search",
            )
        val wire = decode<SessionListWire>(body)
        val sessions = sessionRows(wire)
        val total = wire.total ?: sessions.size
        val pageLimit = wire.limit ?: limit
        val pageOffset = wire.offset ?: offset
        val hasMore =
            if (wire.total == null && wire.has_more != null) {
                wire.has_more
            } else {
                pageHasMore(total, pageOffset, sessions.size, pageLimit)
            }
        return SessionPage(
            sessions = sessions,
            total = total,
            limit = pageLimit,
            offset = pageOffset,
            hasMore = hasMore,
        )
    }

    suspend fun session(
        endpoint: Endpoint,
        id: String,
        profile: String? = null,
    ): SessionDetail {
        val body =
            getJson(
                endpoint,
                sessionPath(id),
                mapOf("profile" to profile),
            )
        val wire = decode<SessionDetailWire>(body)
        return SessionDetail(
            id = wire.session_id ?: id,
            sessionKey = wire.session_key,
            title = wire.title,
            status = wire.status,
            messageCount = wire.message_count.asIntOrNull(),
            inputTokens = wire.input_tokens.asLongOrNull(),
            outputTokens = wire.output_tokens.asLongOrNull(),
            updatedAtEpochMs =
                wire.last_active.asEpochMillisOrNull()
                    ?: wire.last_activity_at.asEpochMillisOrNull(),
        )
    }

    /** Stored transcript tip, not a runtime id; resume it before submitting a prompt. */
    suspend fun latestDescendant(
        endpoint: Endpoint,
        id: String,
        profile: String? = null,
    ): String {
        val body = getJson(endpoint, sessionPath(id) + "latest-descendant", mapOf("profile" to profile))
        return decode<LatestDescendantWire>(body).session_id?.takeIf { it.isNotBlank() }
            ?: throw ApiException(
                message = "Dashboard did not return the session's current id",
                kind = ApiErrorKind.INVALID_RESPONSE,
            )
    }

    suspend fun messages(
        endpoint: Endpoint,
        id: String,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
        order: String = "latest",
        includeCompacted: Boolean = true,
        profile: String? = null,
    ): MessagePage {
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
        require(offset >= 0) { "offset must not be negative" }
        val body =
            getJson(
                endpoint,
                sessionPath(id) + "messages",
                mapOf(
                    "limit" to limit.toString(),
                    "offset" to offset.toString(),
                    "order" to order,
                    "include_compacted" to includeCompacted.toString(),
                    "profile" to profile,
                ),
            )
        val wire = decode<MessagesWire>(body)
        val total = wire.total ?: 0
        val pageOffset = wire.pagination?.offset ?: wire.offset ?: offset
        val pageLimit = wire.pagination?.limit ?: limit
        val messages =
            wire.messages.mapIndexed { index, message ->
                toTranscriptMessage(
                    message = message,
                    syntheticBase = pageOffset,
                    viewIndex = index,
                    around = false,
                )
            }
        val returned = wire.pagination?.returned ?: messages.size
        val hasOlder =
            wire.pagination?.has_older
                ?: (total > 0 && pageOffset + returned < total)
        return MessagePage(
            messages = messages,
            total = total,
            offset = pageOffset,
            limit = pageLimit,
            hasOlder = hasOlder,
        )
    }

    suspend fun messagesAround(
        endpoint: Endpoint,
        id: String,
        rowId: Int,
        limit: Int = DEFAULT_PAGE_SIZE,
        profile: String? = null,
    ): MessageWindow {
        require(limit in 1..MAX_PAGE_SIZE) { "limit must be between 1 and $MAX_PAGE_SIZE" }
        if (rowId < 0) {
            throw ApiException(
                message = "Older history is unavailable for this dashboard — reopen from the latest page",
                kind = ApiErrorKind.UNSUPPORTED,
            )
        }
        val body =
            getJson(
                endpoint,
                sessionPath(id) + listOf("messages", "around"),
                mapOf(
                    "row_id" to rowId.toString(),
                    "limit" to limit.toString(),
                    "profile" to profile,
                ),
            )
        val wire = decode<MessagesAroundWire>(body)
        val windowBase =
            wire.pagination?.offset?.takeIf { it > 0 }
                ?: rowId
        val messages =
            wire.messages.mapIndexed { index, message ->
                toTranscriptMessage(
                    message = message,
                    syntheticBase = windowBase,
                    viewIndex = index,
                    around = true,
                )
            }
        return MessageWindow(
            messages = messages,
            hasOlder = wire.pagination?.has_older ?: false,
            hasNewer = wire.pagination?.has_newer ?: false,
            total = wire.pagination?.total ?: wire.total,
        )
    }

    /**
     * Rename only after the dashboard agrees; callers must not show the title before this returns.
     * Current dashboards refuse an unfenced PATCH (409 client_upgrade_required), so read the
     * session's mutation snapshot first and quote its counters.
     */
    suspend fun renameSession(
        endpoint: Endpoint,
        id: String,
        title: String,
    ) {
        sendFencedMutation(endpoint, id) { fence, requestId ->
            val payload =
                buildJsonObject {
                    put("title", title)
                    put("request_id", requestId)
                    put("expected_revision", fence.revision)
                    fence.generation?.let { put("expected_generation", it) }
                }.toString()
            Request
                .Builder()
                .url(sessionUrl(endpoint, id))
                .header("Accept", "application/json")
                .patch(payload.toRequestBody("application/json".toMediaType()))
        }
    }

    /** Delete only after the dashboard agrees; an empty successful body (204) is expected. */
    suspend fun deleteSession(
        endpoint: Endpoint,
        id: String,
    ) {
        sendFencedMutation(endpoint, id) { fence, requestId ->
            Request
                .Builder()
                .url(
                    sessionUrl(
                        endpoint,
                        id,
                        mapOf(
                            "request_id" to requestId,
                            "expected_revision" to fence.revisionText,
                            "expected_generation" to fence.generationText,
                        ),
                    ),
                ).header("Accept", "application/json")
                .delete()
        }
    }

    /**
     * Sends one fenced mutation. A relevant conflict (409 client_upgrade_required) re-reads the
     * snapshot and retries once with a fresh request id; a second conflict is the caller's cue to
     * reload. Any other failure propagates immediately and is never retried.
     */
    private suspend fun sendFencedMutation(
        endpoint: Endpoint,
        id: String,
        build: (MutationFence, String) -> Request.Builder,
    ) {
        var fence = mutationFence(endpoint, id)
        var conflicts = 0
        while (true) {
            val requestId = UUID.randomUUID().toString()
            if (sendMutation(build(fence, requestId).authorize()) == MutationOutcome.SUCCESS) return
            conflicts++
            if (conflicts >= MAX_MUTATION_ATTEMPTS) {
                throw ApiException(
                    message = "changed elsewhere — retry",
                    kind = ApiErrorKind.HTTP,
                    httpCode = 409,
                )
            }
            fence = mutationFence(endpoint, id)
        }
    }

    private suspend fun sendMutation(builder: Request.Builder): MutationOutcome =
        try {
            client.newCall(builder.build()).awaitResponse().use { response ->
                val body = response.readBody()
                when {
                    response.isSuccessful -> MutationOutcome.SUCCESS
                    response.code == 409 && body.contains(CLIENT_UPGRADE_REQUIRED) ->
                        MutationOutcome.UPGRADE_REQUIRED

                    else -> throw httpError(response.code)
                }
            }
        } catch (exception: IOException) {
            throw ApiException(
                message = "Dashboard unreachable — check address",
                kind = ApiErrorKind.NETWORK,
                cause = exception,
            )
        }

    /**
     * Owner counters a destructive write must quote. Missing or malformed counters are rejected
     * rather than silently dropping a fence. Generation is optional only when absent or null.
     */
    private suspend fun mutationFence(
        endpoint: Endpoint,
        id: String,
    ): MutationFence {
        val body = getJson(endpoint, sessionPath(id) + "mutation-snapshot", emptyMap())
        val wire = decode<SessionMutationSnapshotWire>(body)
        if (wire.exists != true) {
            val stale = wire.exists == false
            throw ApiException(
                message =
                    if (stale) {
                        "Session is no longer on the dashboard"
                    } else {
                        "Dashboard returned an incomplete session snapshot"
                    },
                kind = if (stale) ApiErrorKind.HTTP else ApiErrorKind.INVALID_RESPONSE,
                httpCode = if (stale) 404 else null,
            )
        }
        val revision =
            wire.runtime_revision.mutationCounterOrNull()
                ?: throw ApiException(
                    message = "Dashboard did not return the session's current revision",
                    kind = ApiErrorKind.INVALID_RESPONSE,
                )
        val generationElement = wire.runtime_generation?.takeUnless { it == JsonNull }
        val generation = generationElement.mutationCounterOrNull()
        if (generationElement != null && generation == null) {
            throw ApiException(
                message = "Dashboard returned an unexpected session snapshot",
                kind = ApiErrorKind.INVALID_RESPONSE,
            )
        }
        return MutationFence(
            revision = revision,
            generation = generation,
        )
    }

    /**
     * Snapshot counters arrive as JSON numbers or numeric strings; both round-trip unchanged and
     * arbitrary-length integer strings are kept rather than narrowed through Long parsing.
     */
    private fun JsonElement?.mutationCounterOrNull(): JsonElement? {
        val primitive = this as? JsonPrimitive ?: return null
        if (primitive == JsonNull) return null
        val text = primitive.content
        return primitive.takeIf { text.isNotEmpty() && text.all { it in '0'..'9' } }
    }

    /**
     * Rows from either the list envelope (`sessions`) or the search envelope (`results`), ordered
     * and deduplicated by id because message-hit results can repeat one session. A response with
     * neither collection is malformed, so search callers can fall back to local filtering.
     */
    private fun sessionRows(wire: SessionListWire): List<SessionSummary> {
        val rows =
            wire.sessions ?: wire.results
                ?: throw ApiException(
                    message = "Dashboard returned an unexpected response",
                    kind = ApiErrorKind.INVALID_RESPONSE,
                )
        return rows
            .mapNotNull { item ->
                val id =
                    item.id.takeIf { it.isNotBlank() }
                        ?: item.session_id?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                SessionSummary(
                    id = id,
                    title = item.title ?: item.display_name ?: "(untitled)",
                    source = item.source,
                    profile = item.profile,
                    preview = item.preview ?: item.snippet,
                    displayName = item.display_name,
                    model = item.model,
                    messageCount = item.message_count.asIntOrNull(),
                    status = item.status,
                    updatedAtEpochMs =
                        item.last_active.asEpochMillisOrNull()
                            ?: item.last_activity_at.asEpochMillisOrNull()
                            ?: item.session_started.asEpochMillisOrNull()
                            ?: item.started_at.asEpochMillisOrNull(),
                    unread = item.unread.asLenientBooleanOrNull(),
                    pinned = item.pinned.asLenientBooleanOrNull() ?: false,
                    archived = item.archived.asLenientBooleanOrNull() ?: false,
                )
            }.distinctBy { it.id }
    }

    private fun pageHasMore(
        total: Int,
        offset: Int,
        returned: Int,
        limit: Int,
    ): Boolean =
        if (total > 0) {
            offset + returned < total
        } else {
            returned > 0 && returned >= limit
        }

    private suspend fun getJson(
        endpoint: Endpoint,
        path: List<String>,
        query: Map<String, String?>,
        basePath: String = "api/sessions",
    ): String {
        val urlBuilder = endpoint.buildUrl(basePath, query).newBuilder()
        path.forEach { urlBuilder.addPathSegment(it) }
        return send(
            Request
                .Builder()
                .url(urlBuilder.build().toString())
                .header("Accept", "application/json")
                .get()
                .authorize(),
        )
    }

    private suspend fun send(builder: Request.Builder): String =
        try {
            client.newCall(builder.build()).awaitResponse().use { response ->
                val body = response.readBody()
                if (!response.isSuccessful) throw httpError(response.code)
                body
            }
        } catch (exception: IOException) {
            throw ApiException(
                message = "Dashboard unreachable — check address",
                kind = ApiErrorKind.NETWORK,
                cause = exception,
            )
        }

    private fun Request.Builder.authorize(): Request.Builder =
        apply {
            tokenProvider()?.takeIf { it.isNotBlank() }?.let { token ->
                header("Authorization", "Bearer $token")
            }
        }

    /** One encoded path segment, so ids containing `/` or reserved characters stay one id. */
    private fun sessionUrl(
        endpoint: Endpoint,
        id: String,
        query: Map<String, String?> = emptyMap(),
    ): String {
        val builder = endpoint.buildUrl("api/sessions", query).newBuilder()
        sessionPath(id).forEach { builder.addPathSegment(it) }
        return builder.build().toString()
    }

    private inline fun <reified T> decode(body: String): T =
        try {
            json.decodeFromString<T>(body)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            throw ApiException(
                message = "Dashboard returned an unexpected response",
                kind = ApiErrorKind.INVALID_RESPONSE,
                cause = exception,
            )
        }

    private fun httpError(code: Int): ApiException {
        val kind = if (code == 401 || code == 403) ApiErrorKind.UNAUTHORIZED else ApiErrorKind.HTTP
        val message =
            when {
                code == 401 || code == 403 -> "Sign-in expired — reconnect from Setup"
                code == 404 -> "Not found on the dashboard (HTTP 404)"
                code == 429 -> "Dashboard busy — try again"
                code in 500..599 -> "Dashboard error (HTTP $code)"
                else -> "Dashboard returned HTTP $code"
            }
        return ApiException(message = message, kind = kind, httpCode = code)
    }

    private fun sessionPath(id: String): List<String> {
        require(id.isNotBlank()) { "Session id must not be blank" }
        return listOf(id)
    }

    private fun toTranscriptMessage(
        message: MessageWire,
        syntheticBase: Int,
        viewIndex: Int,
        around: Boolean,
    ): TranscriptMessage {
        val rowId =
            message.id.asIntOrNull()?.takeIf { it > 0 }
                ?: syntheticRowId(
                    base = syntheticBase,
                    index = viewIndex,
                    around = around,
                )
        val reasoningElement =
            message.display_reasoning?.takeIf { it is JsonPrimitive && it.isString }
                ?: message.reasoning
                ?: message.reasoning_text
        val tool =
            if (message.role == "tool" || message.type == "tool") {
                toolMetadata(message.content, message.tool_name?.takeIf { it.isNotBlank() } ?: message.name)
            } else {
                null
            }
        return TranscriptMessage(
            rowId = rowId,
            role = message.role.orEmpty(),
            text = displayText(message.display_content, message.content),
            reasoning = reasoningElement?.let(::flattenText)?.takeIf { it.isNotBlank() },
            type = message.type,
            toolCallId = message.tool_call_id,
            timestampEpochMs = message.timestamp.asEpochMillisOrNull(),
            displayKind = message.display_kind,
            toolName = tool?.name,
            toolOutcome = tool?.outcome,
            toolCalls = toolCallNames(message.tool_calls),
        )
    }

    /**
     * Missing ids become negative ids in two disjoint ranges, so a thread can hold latest-page and
     * around-window rows without duplicate Compose keys. The same page refetched maps to the same
     * synthetic id, which lets the reducer dedupe instead of duplicating.
     */
    private fun syntheticRowId(
        base: Int,
        index: Int,
        around: Boolean,
    ): Int {
        val rangeBase = if (around) AROUND_SYNTHETIC_BASE else 0L
        val value = rangeBase + base.coerceAtLeast(0).toLong() + index + 1L
        return -value.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun displayText(
        preferred: JsonElement?,
        fallback: JsonElement?,
    ): String {
        val element = preferred?.takeUnless { it is JsonNull } ?: fallback
        return flattenText(element)
    }

    private fun flattenText(element: JsonElement?): String =
        when (element) {
            null, JsonNull -> ""
            is JsonPrimitive -> element.content
            is JsonArray ->
                element
                    .mapNotNull { part ->
                        when (part) {
                            is JsonPrimitive -> part.takeIf { it.isString }?.content
                            is JsonObject -> {
                                val type = part.stringOrNull("type")
                                if (type != null && type != "text") null else part.stringOrNull("text")
                            }

                            else -> null
                        }
                    }.joinToString("\n")
            is JsonObject -> element.stringOrNull("text") ?: element.toString()
        }

    companion object {
        const val DEFAULT_PAGE_SIZE: Int = 50
        const val MAX_PAGE_SIZE: Int = 500
        private const val AROUND_SYNTHETIC_BASE: Long = 1_000_000_000L
        private const val MAX_MUTATION_ATTEMPTS: Int = 2
        private const val CLIENT_UPGRADE_REQUIRED: String = "client_upgrade_required"
    }
}

/** Owner counters a fenced mutation quotes; elements keep the dashboard's number/string encoding. */
private data class MutationFence(
    val revision: JsonElement,
    val generation: JsonElement?,
) {
    val revisionText: String get() = (revision as JsonPrimitive).content
    val generationText: String? get() = (generation as? JsonPrimitive)?.content
}

private enum class MutationOutcome {
    SUCCESS,
    UPGRADE_REQUIRED,
}

@Serializable
private data class SessionMutationSnapshotWire(
    val session_id: String? = null,
    val exists: Boolean? = null,
    val runtime_revision: JsonElement? = null,
    val runtime_generation: JsonElement? = null,
)

@Serializable
private data class AudioTranscriptWire(
    val transcript: String,
)

@Serializable
private data class LatestDescendantWire(
    val session_id: String? = null,
)

@Serializable
private data class SessionListWire(
    val sessions: List<SessionWire>? = null,
    val results: List<SessionWire>? = null,
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
    val has_more: Boolean? = null,
)

@Serializable
private data class SessionWire(
    val id: String = "",
    val session_id: String? = null,
    val title: String? = null,
    val display_name: String? = null,
    val source: String? = null,
    val profile: String? = null,
    val preview: String? = null,
    val snippet: String? = null,
    val model: String? = null,
    val message_count: JsonElement? = null,
    val status: String? = null,
    val last_active: JsonElement? = null,
    val last_activity_at: JsonElement? = null,
    val session_started: JsonElement? = null,
    val started_at: JsonElement? = null,
    val unread: JsonElement? = null,
    val pinned: JsonElement? = null,
    val archived: JsonElement? = null,
)

@Serializable
private data class SessionDetailWire(
    val session_id: String? = null,
    val session_key: String? = null,
    val title: String? = null,
    val status: String? = null,
    val message_count: JsonElement? = null,
    val input_tokens: JsonElement? = null,
    val output_tokens: JsonElement? = null,
    val last_active: JsonElement? = null,
    val last_activity_at: JsonElement? = null,
)

@Serializable
private data class MessagesWire(
    val messages: List<MessageWire> = emptyList(),
    val total: Int? = null,
    val offset: Int? = null,
    val pagination: PaginationWire? = null,
)

@Serializable
private data class MessagesAroundWire(
    val session_id: String? = null,
    val messages: List<MessageWire> = emptyList(),
    val total: Int? = null,
    val pagination: AroundPaginationWire? = null,
)

@Serializable
private data class PaginationWire(
    val limit: Int? = null,
    val offset: Int? = null,
    val order: String? = null,
    val returned: Int? = null,
    val has_older: Boolean? = null,
)

@Serializable
private data class AroundPaginationWire(
    val row_id: Int? = null,
    val limit: Int? = null,
    val returned: Int? = null,
    val order: String? = null,
    val offset: Int? = null,
    val total: Int? = null,
    val has_older: Boolean? = null,
    val has_newer: Boolean? = null,
)

@Serializable
private data class MessageWire(
    val id: JsonElement? = null,
    val role: String? = null,
    val content: JsonElement? = null,
    val display_content: JsonElement? = null,
    val timestamp: JsonElement? = null,
    val type: String? = null,
    val reasoning: JsonElement? = null,
    val reasoning_text: JsonElement? = null,
    val display_reasoning: JsonElement? = null,
    val tool_call_id: String? = null,
    val display_kind: String? = null,
    val name: String? = null,
    val tool_name: String? = null,
    val tool_calls: JsonElement? = null,
)
