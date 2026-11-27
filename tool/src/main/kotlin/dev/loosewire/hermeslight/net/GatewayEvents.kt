/*
 * Copyright 2026 LooseWireDev
 *
 * Gateway event names and payload fields are derived from Hermes Mobile
 * (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class ToolPhase {
    START,
    PROGRESS,
    COMPLETE,
    GENERATING,
}

enum class ApprovalKind {
    COMMAND,
    ACCESS,
    NETWORK,
}

data class ClarifyQuestion(
    val id: String,
    val question: String,
    val choices: List<String>,
    val multiSelect: Boolean,
)

/**
 * One parsed server-push event. [sessionId] is present whenever the gateway included it, so the app
 * can route events to the open thread without re-parsing the raw frame.
 */
sealed interface GatewayEvent {
    val sessionId: String?

    data class Ready(
        val changeEvents: Boolean,
        val replayEpoch: String?,
    ) : GatewayEvent {
        override val sessionId: String? = null
    }

    data class SessionInfo(
        val status: String?,
        val title: String?,
        override val sessionId: String?,
    ) : GatewayEvent

    data class SessionStatus(
        val status: String?,
        val running: Boolean?,
        override val sessionId: String?,
    ) : GatewayEvent

    data class MessageStart(
        val turnId: String?,
        override val sessionId: String?,
    ) : GatewayEvent

    data class MessageDelta(
        val text: String,
        override val sessionId: String?,
    ) : GatewayEvent

    data class MessageInterim(
        override val sessionId: String?,
    ) : GatewayEvent

    data class MessageComplete(
        val text: String,
        val reasoning: String?,
        val paused: Boolean,
        override val sessionId: String?,
    ) : GatewayEvent

    data class MessageDone(
        override val sessionId: String?,
    ) : GatewayEvent

    data class ReasoningDelta(
        val text: String,
        override val sessionId: String?,
    ) : GatewayEvent

    data class ReasoningAvailable(
        val text: String?,
        override val sessionId: String?,
    ) : GatewayEvent

    data class Tool(
        val phase: ToolPhase,
        val name: String?,
        val detail: String?,
        override val sessionId: String?,
    ) : GatewayEvent

    data class ApprovalRequested(
        val requestId: String?,
        val kind: ApprovalKind,
        val command: String?,
        val description: String?,
        val path: String?,
        val host: String?,
        val label: String?,
        val risk: String?,
        val choices: List<String>,
        override val sessionId: String?,
    ) : GatewayEvent

    data class ApprovalPending(
        override val sessionId: String?,
    ) : GatewayEvent

    data class ApprovalReceived(
        val requestId: String?,
        override val sessionId: String?,
    ) : GatewayEvent

    data class ClarifyRequested(
        val clarifyId: String?,
        val questionId: String?,
        val text: String?,
        val options: List<String>,
        val multiSelect: Boolean,
        val questions: List<ClarifyQuestion>,
        override val sessionId: String?,
    ) : GatewayEvent

    data class ClarifyExpired(
        val clarifyId: String?,
        val settled: Boolean,
        override val sessionId: String?,
    ) : GatewayEvent

    data class Process(
        val type: String,
        val payload: JsonObject,
        override val sessionId: String?,
    ) : GatewayEvent

    data class Status(
        val kind: String?,
        val text: String?,
        override val sessionId: String?,
    ) : GatewayEvent

    data class Done(
        override val sessionId: String?,
    ) : GatewayEvent

    data class Failure(
        val message: String?,
        override val sessionId: String?,
    ) : GatewayEvent

    data class Notice(
        val text: String,
        val level: String?,
        val kind: String?,
        override val sessionId: String?,
    ) : GatewayEvent

    data class Change(
        val type: String,
    ) : GatewayEvent {
        override val sessionId: String? = null
    }

    data class Unknown(
        val type: String,
        val raw: String,
    ) : GatewayEvent {
        override val sessionId: String? = null
    }
}

data class GatewayRpcError(
    val code: Int?,
    val message: String?,
)

/** Classification of one inbound JSON-RPC frame. */
sealed interface ParsedFrame {
    data class Event(
        val event: GatewayEvent,
    ) : ParsedFrame

    data class Response(
        val id: String,
        val result: JsonElement?,
    ) : ParsedFrame

    data class ResponseError(
        val id: String,
        val error: GatewayRpcError,
    ) : ParsedFrame

    data class ServerRequest(
        val id: String,
        val method: String,
        val params: JsonObject,
        /**
         * Read-only projection for approval/clarify requests the gateway expects a response to.
         * The gateway client never answers in M0; it surfaces the notice instead of dropping it.
         */
        val event: GatewayEvent? = null,
    ) : ParsedFrame

    data class Invalid(
        val raw: String,
    ) : ParsedFrame
}

/**
 * Tolerant parser for the dashboard's JSON-RPC WebSocket.
 *
 * Regular RPC responses carry `id` without `method`; server-to-client requests carry both. Server
 * events arrive as `method: "event"` notifications whose `params.type` names the family and whose
 * `params.payload` carries the body. Unknown families become [GatewayEvent.Unknown]; malformed
 * frames become [ParsedFrame.Invalid]. The parser never throws.
 */
object GatewayEventParser {
    private val changeTypes =
        setOf(
            "cron.changed",
            "sessions.changed",
            "platforms.changed",
            "pairing.changed",
            "projects.changed",
        )

    private val liveStatuses =
        setOf(
            "starting",
            "waiting",
            "working",
            "streaming",
            "resuming",
        )

    private val readOnlyServerRequestMethods =
        setOf(
            "approval.request",
            "approval.pending",
            "approval.received",
            "clarify.request",
            "clarify.expire",
            "clarify.settled",
        )

    fun parse(
        raw: String,
        json: Json = hermesJson,
    ): ParsedFrame =
        try {
            parseFrame(raw, json)
        } catch (_: Exception) {
            ParsedFrame.Invalid(raw)
        }

    private fun parseFrame(
        raw: String,
        json: Json,
    ): ParsedFrame {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return ParsedFrame.Invalid(raw)
        val method = root.stringOrNull("method")
        val id = root.stringOrNull("id") ?: root["id"].asLongOrNull()?.toString()

        if (id != null && method != null) {
            val params = root.objectOrNull("params") ?: JsonObject(emptyMap())
            return ParsedFrame.ServerRequest(
                id = id,
                method = method,
                params = params,
                event = readOnlyServerRequestEvent(method, params, raw),
            )
        }
        if (id != null) {
            val error = root.objectOrNull("error")
            if (error != null) {
                return ParsedFrame.ResponseError(
                    id = id,
                    error =
                        GatewayRpcError(
                            code = error.intOrNull("code"),
                            message = error.stringOrNull("message"),
                        ),
                )
            }
            return ParsedFrame.Response(id = id, result = root["result"])
        }

        val params = root.objectOrNull("params") ?: return ParsedFrame.Invalid(raw)
        val type =
            params.stringOrNull("type")
                ?: method?.takeIf { it != "event" }
                ?: return ParsedFrame.Invalid(raw)
        return ParsedFrame.Event(parseEvent(type, params, raw))
    }

    /**
     * M0 advertises `server_requests: false`, but an older or forgetful gateway can still send a
     * same-id request. Approval and clarify requests are projected to the same read-only events as
     * their notification form; the client never sends a response frame.
     */
    private fun readOnlyServerRequestEvent(
        method: String,
        params: JsonObject,
        raw: String,
    ): GatewayEvent? {
        if (method !in readOnlyServerRequestMethods) return null
        val envelope =
            JsonObject(
                params +
                    mapOf(
                        "type" to JsonPrimitive(method),
                        "payload" to params,
                    ),
            )
        return parseEvent(method, envelope, raw)
    }

    private fun parseEvent(
        type: String,
        params: JsonObject,
        raw: String,
    ): GatewayEvent {
        val payload = params.objectOrNull("payload") ?: JsonObject(emptyMap())
        val sessionId =
            params.stringOrNull("session_id")
                ?: payload.stringOrNull("session_id")
                ?: payload.stringOrNull("stored_session_id")

        return when (type) {
            "gateway.ready" ->
                GatewayEvent.Ready(
                    changeEvents = payload.booleanOrNull("change_events") ?: false,
                    replayEpoch = payload.stringOrNull("replay_epoch"),
                )

            "session.info" ->
                GatewayEvent.SessionInfo(
                    status = payload.stringOrNull("status"),
                    title = payload.stringOrNull("title"),
                    sessionId = sessionId,
                )

            "session.status" -> {
                val status = payload.stringOrNull("status")
                GatewayEvent.SessionStatus(
                    status = status,
                    running = status?.lowercase()?.let { it in liveStatuses },
                    sessionId = sessionId,
                )
            }

            "message.start" ->
                GatewayEvent.MessageStart(
                    turnId = payload.stringOrNull("turn_id"),
                    sessionId = sessionId,
                )

            "message.token", "message.delta" ->
                GatewayEvent.MessageDelta(
                    text = eventText(payload, params),
                    sessionId = sessionId,
                )

            "message.interim" -> GatewayEvent.MessageInterim(sessionId = sessionId)

            "message.complete" ->
                GatewayEvent.MessageComplete(
                    text = eventText(payload, params),
                    reasoning = payload.stringOrNull("reasoning"),
                    paused = payload.booleanOrNull("paused") ?: false,
                    sessionId = sessionId,
                )

            "message.done" -> GatewayEvent.MessageDone(sessionId = sessionId)

            "thinking.delta", "reasoning.delta" ->
                GatewayEvent.ReasoningDelta(
                    text = eventText(payload, params),
                    sessionId = sessionId,
                )

            "reasoning.available" ->
                GatewayEvent.ReasoningAvailable(
                    text = payload.stringOrNull("text"),
                    sessionId = sessionId,
                )

            "tool.start" ->
                GatewayEvent.Tool(
                    phase = ToolPhase.START,
                    name = payload.stringOrNull("name"),
                    detail = payload.stringOrNull("description"),
                    sessionId = sessionId,
                )

            "tool.progress" ->
                GatewayEvent.Tool(
                    phase = ToolPhase.PROGRESS,
                    name = payload.stringOrNull("name"),
                    detail = payload.stringOrNull("preview"),
                    sessionId = sessionId,
                )

            "tool.generating" ->
                GatewayEvent.Tool(
                    phase = ToolPhase.GENERATING,
                    name = payload.stringOrNull("name"),
                    detail = null,
                    sessionId = sessionId,
                )

            "tool.complete" ->
                GatewayEvent.Tool(
                    phase = ToolPhase.COMPLETE,
                    name = payload.stringOrNull("name"),
                    detail =
                        payload.stringOrNull("summary")
                            ?: payload.stringOrNull("error")
                            ?: payload.stringOrNull("description"),
                    sessionId = sessionId,
                )

            "tool.output_risk" ->
                GatewayEvent.Tool(
                    phase = ToolPhase.PROGRESS,
                    name = payload.stringOrNull("name"),
                    detail = payload.stringOrNull("risk"),
                    sessionId = sessionId,
                )

            "approval.request" -> parseApprovalRequested(payload, sessionId)
            "approval.pending" -> GatewayEvent.ApprovalPending(sessionId = sessionId)
            "approval.received" ->
                GatewayEvent.ApprovalReceived(
                    requestId = payload.stringOrNull("request_id"),
                    sessionId = sessionId,
                )

            "clarify.request" -> parseClarifyRequested(payload, sessionId)
            "clarify.expire" ->
                GatewayEvent.ClarifyExpired(
                    clarifyId = clarifyId(payload),
                    settled = false,
                    sessionId = sessionId,
                )

            "clarify.settled" ->
                GatewayEvent.ClarifyExpired(
                    clarifyId = clarifyId(payload),
                    settled = true,
                    sessionId = sessionId,
                )

            "error" ->
                GatewayEvent.Failure(
                    message =
                        payload.stringOrNull("message")
                            ?: payload.stringOrNull("error")
                            ?: params.stringOrNull("message")
                            ?: params.stringOrNull("error"),
                    sessionId = sessionId,
                )

            "done" -> GatewayEvent.Done(sessionId = sessionId)

            "status.update" ->
                GatewayEvent.Status(
                    kind = payload.stringOrNull("kind"),
                    text = payload.stringOrNull("text"),
                    sessionId = sessionId,
                )

            "notification.show" -> {
                val text = payload.stringOrNull("text")
                if (text.isNullOrBlank()) {
                    GatewayEvent.Unknown(type, raw)
                } else {
                    GatewayEvent.Notice(
                        text = text,
                        level = payload.stringOrNull("level"),
                        kind = payload.stringOrNull("kind"),
                        sessionId = sessionId,
                    )
                }
            }

            in changeTypes -> GatewayEvent.Change(type)

            else -> {
                if (type.startsWith("process.")) {
                    GatewayEvent.Process(type = type, payload = payload, sessionId = sessionId)
                } else {
                    GatewayEvent.Unknown(type, raw)
                }
            }
        }
    }

    private fun parseApprovalRequested(
        payload: JsonObject,
        sessionId: String?,
    ): GatewayEvent.ApprovalRequested {
        val rawKind = payload.stringOrNull("kind")?.lowercase()
        val kind =
            when (rawKind) {
                "access" -> ApprovalKind.ACCESS
                "network" -> ApprovalKind.NETWORK
                else -> ApprovalKind.COMMAND
            }
        val providedChoices = stringList(payload["choices"])
        val smartDenied = payload.booleanOrNull("smart_denied")
        val allowSession = payload.booleanOrNull("allow_session")
        val allowPermanent = payload.booleanOrNull("allow_permanent")
        val choices =
            providedChoices.ifEmpty {
                if (smartDenied == true) {
                    listOf("once", "deny")
                } else {
                    buildList {
                        add("once")
                        if (allowSession != false) {
                            add("session")
                            if (allowPermanent != false) add("always")
                        }
                        add("deny")
                    }
                }
            }
        return GatewayEvent.ApprovalRequested(
            requestId = payload.stringOrNull("request_id") ?: payload.stringOrNull("prompt_id"),
            kind = kind,
            command = payload.stringOrNull("command"),
            description = payload.stringOrNull("description"),
            path = payload.stringOrNull("path"),
            host = payload.stringOrNull("host"),
            label = payload.stringOrNull("label"),
            risk = payload.stringOrNull("risk"),
            choices = choices,
            sessionId = sessionId,
        )
    }

    private fun parseClarifyRequested(
        payload: JsonObject,
        sessionId: String?,
    ): GatewayEvent.ClarifyRequested {
        val rawQuestions = payload["questions"] as? JsonArray
        val questions =
            rawQuestions
                ?.mapIndexedNotNull { index, item ->
                    val question = item as? JsonObject ?: return@mapIndexedNotNull null
                    val text = question.stringOrNull("question") ?: return@mapIndexedNotNull null
                    ClarifyQuestion(
                        id = question.stringOrNull("qid") ?: "q$index",
                        question = text,
                        choices = stringList(question["choices"]),
                        multiSelect = question.booleanOrNull("multi_select") ?: false,
                    )
                }.orEmpty()
        val primary = questions.firstOrNull()
        val legacyText =
            payload.stringOrNull("question") ?: payload.stringOrNull("text")
        val legacyOptions = stringList(payload["choices"] ?: payload["options"])
        val text =
            when {
                questions.size > 1 ->
                    questions
                        .mapIndexed { index, question -> "${index + 1}. ${question.question}" }
                        .joinToString("\n\n")
                primary != null -> primary.question
                else -> legacyText
            }
        return GatewayEvent.ClarifyRequested(
            clarifyId = clarifyId(payload),
            questionId =
                primary?.id
                    ?: payload.stringOrNull("qid")
                    ?: payload.stringOrNull("question_id"),
            text = text,
            options = primary?.choices ?: legacyOptions,
            multiSelect = primary?.multiSelect ?: payload.booleanOrNull("multi_select") ?: false,
            questions = questions,
            sessionId = sessionId,
        )
    }

    private fun clarifyId(payload: JsonObject): String? =
        payload.stringOrNull("clarify_id")
            ?: payload.stringOrNull("request_id")
            ?: payload.stringOrNull("prompt_id")

    private fun eventText(
        payload: JsonObject,
        params: JsonObject,
    ): String =
        payload.stringOrNull("text")
            ?: payload.stringOrNull("delta")
            ?: payload.stringOrNull("content")
            ?: params.stringOrNull("text")
            ?: ""

    private fun stringList(element: JsonElement?): List<String> =
        (element as? JsonArray)?.mapNotNull { part ->
            (part as? JsonPrimitive)?.takeIf { it.isString }?.content
        } ?: emptyList()
}
