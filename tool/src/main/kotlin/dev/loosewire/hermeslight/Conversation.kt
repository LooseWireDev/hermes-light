/*
 * Copyright 2026 LooseWireDev
 *
 * Conversation message shapes and decision parameters are derived from
 * Hermes Mobile (https://github.com/Hy4ri/hermes-mobile), licensed under the
 * Apache License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.data.TranscriptMessage
import dev.loosewire.hermeslight.net.ClarifyQuestion
import dev.loosewire.hermeslight.net.GatewayEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How a locally submitted prompt is doing. Only gateway events may claim delivery. */
enum class PromptStatus {
    PENDING,
    ACCEPTED,
    QUEUED,

    /** Local preflight only: no socket write yet, never promoted or reconciled. */
    PREPARING,

    /** The gateway started this turn; keep the user shadow visible until REST proves it. */
    STARTED,
    FAILED,
    CANCELLING,

    /** The submit left the socket but no receipt arrived. Never offer a blind resend. */
    UNKNOWN,
}

/** A local prompt row; it is never a delivered transcript message. */
data class PendingPrompt(
    val id: String,
    val text: String,
    val queued: Boolean,
    val status: PromptStatus,
    val error: String? = null,
    /** `prompt.submit` receipt used by `prompt.cancel {session_id, admission_id}`. */
    val admissionId: String? = null,
    /** True when a transport outcome is unknown and only a receipt/transcript may resolve it. */
    val outcomeUnknown: Boolean = false,
    /** Transcript row id present before the send; REST rows at or below it cannot prove delivery. */
    val baselineRowId: Int? = null,
)

/** The one live assistant reply. It is temporary until reconciled against REST. */
data class LiveReply(
    val text: String,
    val reasoning: String = "",
    /** One-line tool/process activity. */
    val tool: String? = null,
    val streaming: Boolean = true,
)

enum class DecisionStatus {
    PENDING,
    SENDING,
    SETTLED,
    EXPIRED,
}

data class ApprovalDecision(
    val request: GatewayEvent.ApprovalRequested,
    val status: DecisionStatus = DecisionStatus.PENDING,
    val error: String? = null,
    val choice: String? = null,
    /** `approval.received` is only a display acknowledgement, never resolution. */
    val acknowledged: Boolean = false,
)

data class ClarifyDecision(
    val request: GatewayEvent.ClarifyRequested,
    val status: DecisionStatus = DecisionStatus.PENDING,
    val error: String? = null,
    val executionGeneration: Int? = null,
    /** Selected option tokens per question id; multi-select keeps several. */
    val selectedChoices: Map<String, List<String>> = emptyMap(),
    /** Free-text answer per question id. */
    val freeText: Map<String, String> = emptyMap(),
    /** Answers already locked server-side; never echo these back. */
    val lockedAnswers: Map<String, String> = request.lockedAnswers,
)

/** Per-session live state: composer, local sends, one reply, and blocking decisions. */
data class Conversation(
    val composer: String = "",
    val prompts: List<PendingPrompt> = emptyList(),
    val running: Boolean = false,
    val stream: LiveReply? = null,
    val approvals: List<ApprovalDecision> = emptyList(),
    val clarifications: List<ClarifyDecision> = emptyList(),
    val error: String? = null,
    /** Bumped whenever [stream] changes, so a REST reconcile cannot wipe a newer reply. */
    val streamRevision: Long = 0L,
    val stopping: Boolean = false,
    val stopError: String? = null,
    /** Current gateway turn id, used to recognise a genuinely new turn. */
    val turnId: String? = null,
)

/**
 * Records a send locally and clears the composer. It never sets [Conversation.running]: only the
 * gateway may do that. [afterRowId] is the last transcript row id the UI knew about, so REST
 * reconcile cannot mistake an older identical row for this send's delivery.
 */
fun Conversation.submit(
    id: String,
    text: String,
    afterRowId: Int? = null,
): Conversation {
    val queued = running
    val prompt =
        PendingPrompt(
            id = id,
            text = text,
            queued = queued,
            status = if (queued) PromptStatus.QUEUED else PromptStatus.PENDING,
            baselineRowId = afterRowId,
        )
    return copy(
        composer = "",
        prompts = prompts + prompt,
        error = null,
    )
}

/**
 * Applies the `prompt.submit` RPC outcome. A rejection restores the text to the composer.
 * Acceptance is not delivery: the prompt stays local until `message.start` or REST evidence.
 */
fun Conversation.submitResult(
    id: String,
    result: JsonElement?,
    error: String? = null,
): Conversation {
    val prompt = prompts.firstOrNull { it.id == id } ?: return copy(error = error ?: this.error)
    val body = result as? JsonObject
    val status = body?.get("status").scalar()?.lowercase()
    val rejection =
        error
            ?: if (status != null && status in rejectedStatuses) {
                body?.failureMessage() ?: "send rejected"
            } else {
                null
            }
    if (rejection != null) {
        return copy(
            composer = restoredComposer(prompt.text),
            prompts = prompts.replace(id) { it.copy(status = PromptStatus.FAILED, error = rejection) },
            error = rejection,
        )
    }

    val queued = prompt.queued || status == "queued" || status == "queue"
    val admissionId =
        body?.get("admission_id").scalar()
            ?: body?.get("admissionId").scalar()
            ?: prompt.admissionId
    return copy(
        prompts =
            prompts.replace(id) {
                it.copy(
                    queued = queued,
                    status =
                        when {
                            it.status == PromptStatus.STARTED -> PromptStatus.STARTED
                            queued -> PromptStatus.QUEUED
                            else -> PromptStatus.ACCEPTED
                        },
                    error = null,
                    admissionId = admissionId,
                    outcomeUnknown = false,
                )
            },
        error = null,
    )
}

/**
 * Marks a submit whose transport outcome is unknown (timeout, lost socket). The text is not
 * restored and no retry is implied; a receipt or authoritative transcript must resolve it.
 */
fun Conversation.submitUnknown(
    id: String,
    note: String? = null,
): Conversation =
    copy(
        prompts =
            prompts.replace(id) {
                it.copy(
                    status = PromptStatus.UNKNOWN,
                    error = note,
                    outcomeUnknown = true,
                )
            },
        error = note,
    )

/** Marks a queued prompt as cancellation in flight; the row remains until the RPC result. */
fun Conversation.cancelQueued(id: String): Conversation =
    copy(
        prompts =
            prompts.replace(id) {
                if (it.status == PromptStatus.QUEUED) {
                    it.copy(status = PromptStatus.CANCELLING, error = null)
                } else {
                    it
                }
            },
    )

/**
 * Applies `prompt.cancel`. Callers must only pass `error = null` after an explicit
 * cancelled/canceled receipt; a generic socket ack is not proof. On that explicit success the
 * queued row is removed, otherwise it returns to queued with the failure shown.
 */
fun Conversation.cancelQueuedResult(
    id: String,
    error: String? = null,
): Conversation {
    if (error != null) {
        return copy(
            prompts =
                prompts.replace(id) { prompt ->
                    when (prompt.status) {
                        // message.start won the race; gateway evidence beats a late cancel failure.
                        PromptStatus.STARTED -> prompt
                        PromptStatus.CANCELLING ->
                            prompt.copy(
                                status = if (prompt.queued) PromptStatus.QUEUED else PromptStatus.PENDING,
                                error = error,
                            )

                        else -> prompt.copy(error = error)
                    }
                },
        )
    }
    return copy(prompts = prompts.filterNot { it.id == id })
}

/** Records that an interrupt was requested. The turn still runs until a gateway event ends it. */
fun Conversation.beginStop(): Conversation = copy(stopping = true, stopError = null)

/** Applies the interrupt RPC result. It never fabricates `done` or clears a running turn. */
fun Conversation.stopResult(error: String? = null): Conversation = copy(stopping = false, stopError = error, error = error ?: this.error)

/**
 * Folds one gateway push into this session. Unknown families are ignored; only `message.start`
 * promotes a queued prompt, and a queued row is never also kept as an optimistic message.
 */
fun Conversation.reduce(event: GatewayEvent): Conversation =
    when (event) {
        is GatewayEvent.MessageStart -> messageStart(event)
        is GatewayEvent.MessageDelta -> appendText(event.text)
        is GatewayEvent.MessageInterim -> interimMessage(event.text)
        is GatewayEvent.MessageComplete -> completeMessage(event)
        is GatewayEvent.MessageDone -> finishMessage()
        is GatewayEvent.ReasoningDelta -> appendReasoning(event.text)
        is GatewayEvent.ReasoningAvailable -> availableReasoning(event.text)
        is GatewayEvent.Tool -> toolActivity(event)
        is GatewayEvent.Process -> processActivity(event)
        is GatewayEvent.ApprovalRequested -> addApproval(event)
        is GatewayEvent.ApprovalPending -> this
        is GatewayEvent.ApprovalReceived -> acknowledgeApproval(event.requestId)
        is GatewayEvent.ApprovalCancelled -> cancelApprovals(event.requestIds, event.reason)
        is GatewayEvent.ClarifyRequested -> addClarify(event)
        is GatewayEvent.ClarifyExpired -> expireClarify(event.clarifyId, event.settled)
        is GatewayEvent.SessionStatus -> gatewayRunning(event.running)
        is GatewayEvent.Failure -> failTurn(event.message)
        is GatewayEvent.Done -> finishTurn()
        is GatewayEvent.Ready,
        is GatewayEvent.SessionInfo,
        is GatewayEvent.Status,
        is GatewayEvent.Notice,
        is GatewayEvent.Change,
        is GatewayEvent.Unknown,
        -> this
    }

/**
 * Reconciliation evidence from the authoritative REST transcript. A temporary stream is removed
 * only when it has not changed since [expectedStreamRevision] was captured and it is no longer a
 * live streaming reply, so a mid-stream REST snapshot cannot delete visible text. Prompts are
 * pruned only by matching user rows; queued/cancelling rows require a durable baseline and a
 * negative synthetic baseline is never trusted for chronology.
 */
fun Conversation.reconcile(
    messages: List<TranscriptMessage> = emptyList(),
    expectedStreamRevision: Long = streamRevision,
): Conversation {
    val rows =
        messages
            .filter { it.role.equals("user", ignoreCase = true) }
            .map { it.rowId to normalizeText(it.text) }
            .toMutableList()
    val remaining = mutableListOf<PendingPrompt>()
    for (prompt in prompts) {
        val baseline = prompt.baselineRowId
        val usableBaseline = baseline == null || baseline >= 0
        val eligible =
            usableBaseline &&
                when (prompt.status) {
                    PromptStatus.ACCEPTED,
                    PromptStatus.STARTED,
                    PromptStatus.UNKNOWN,
                    -> true

                    PromptStatus.QUEUED,
                    PromptStatus.CANCELLING,
                    -> baseline != null

                    else -> false
                }
        val matchIndex =
            if (eligible) {
                rows.indexOfFirst { (rowId, text) ->
                    (baseline == null || rowId > baseline) && text == normalizeText(prompt.text)
                }
            } else {
                -1
            }
        if (matchIndex >= 0) {
            rows.removeAt(matchIndex)
        } else {
            remaining += prompt
        }
    }

    val reply = stream
    val clearStream =
        expectedStreamRevision == streamRevision &&
            reply != null &&
            (!running || !reply.streaming)
    return copy(
        prompts = remaining,
        stream = if (clearStream) null else stream,
        streamRevision = if (clearStream) streamRevision + 1 else streamRevision,
    )
}

/** Wire params for cancelling this queued prompt; null when the admission receipt is missing. */
fun PendingPrompt.cancelParams(sessionId: String?): JsonObject? {
    val admission = admissionId ?: return null
    val session = sessionId?.takeIf { it.isNotBlank() } ?: return null
    return buildJsonObject {
        put("session_id", session)
        put("admission_id", admission)
    }
}

/**
 * Choices the phone may offer. `session` is intentionally never exposed, and a request with no
 * advertised choices stays a non-actionable placeholder instead of inventing buttons.
 */
fun ApprovalDecision.allowedChoices(): List<String> {
    val hasContext =
        listOf(request.label, request.description, request.command, request.path, request.host)
            .any { !it.isNullOrBlank() }
    if (!hasContext) return emptyList()

    val offered = request.choices.map { it.lowercase() }.toSet()
    val choices = mutableListOf<String>()
    if ("once" in offered) choices += "once"
    if ("deny" in offered) choices += "deny"
    if (request.allowAll && "always" in offered) choices += "always"
    return choices
}

/**
 * Builds `approval.respond` params. Only `once`, `deny`, and a permitted `always` are allowed;
 * `all` must stay false, and no response is built for a terminal or already-sending decision.
 */
fun ApprovalDecision.respondParams(
    choice: String,
    sessionId: String? = request.sessionId,
    all: Boolean = false,
): JsonObject? {
    if (status != DecisionStatus.PENDING || all) return null
    val normalized = choice.trim().lowercase()
    if (normalized !in allowedChoices()) return null
    val session = sessionId?.takeIf { it.isNotBlank() } ?: return null

    val generation = request.executionGeneration
    val promptId = request.promptId
    if (generation != null && promptId != null) {
        return buildJsonObject {
            put("session_id", session)
            put("execution_generation", generation)
            put("prompt_id", promptId)
            put("choice", normalized)
        }
    }
    return buildJsonObject {
        put("session_id", session)
        request.requestId?.let { put("request_id", it) }
        put("choice", normalized)
        put("all", false)
    }
}

/**
 * Marks an approval settled after an explicit successful `approval.respond` result or an absent
 * pending-approval snapshot. A null [requestId] settles every live decision in this session.
 */
fun Conversation.settleApprovalResponse(requestId: String?): Conversation {
    var settled = false
    val next =
        approvals.map { decision ->
            val live =
                decision.status == DecisionStatus.PENDING || decision.status == DecisionStatus.SENDING
            val matches =
                requestId == null ||
                    decision.request.requestId == requestId ||
                    decision.request.promptId == requestId
            if (live && matches) {
                settled = true
                decision.copy(status = DecisionStatus.SETTLED, error = null)
            } else {
                decision
            }
        }
    return if (settled) copy(approvals = next) else this
}

/** Marks the named approval as sent, showing "sending" until a push settles it. */
fun Conversation.markApprovalSending(
    requestId: String?,
    choice: String,
): Conversation =
    copy(
        approvals =
            approvals.markApprovalsSending(requestId) {
                it.copy(choice = choice, acknowledged = false)
            },
    )

/** Restores a pending approval after an explicit send failure. */
fun Conversation.markApprovalError(
    requestId: String?,
    error: String,
): Conversation =
    copy(
        approvals =
            approvals.markApprovalsError(requestId) {
                it.copy(error = error, acknowledged = false)
            },
    )

/** Marks the named clarify as sent; settlement still requires a gateway push. */
fun Conversation.markClarifySending(clarifyId: String?): Conversation =
    copy(clarifications = clarifications.markClarificationsSending(clarifyId) { it })

/** Restores a pending clarify after an explicit send failure. */
fun Conversation.markClarifyError(
    clarifyId: String?,
    error: String,
): Conversation = copy(clarifications = clarifications.markClarificationsError(clarifyId) { it.copy(error = error) })

/** Normalized questions; a bare request keeps one placeholder question with no invented choices. */
fun ClarifyDecision.questions(): List<ClarifyQuestion> =
    if (request.questions.isNotEmpty()) {
        request.questions
    } else {
        listOf(
            ClarifyQuestion(
                id = request.questionId ?: "q0",
                question = request.text.orEmpty(),
                choices = request.options,
                multiSelect = request.multiSelect,
            ),
        )
    }

/** Final answer text for one question: selected tokens plus free text, joined like desktop. */
fun ClarifyDecision.answerFor(questionId: String): String? {
    if (questionId in lockedAnswers) return null
    val selected = selectedChoices[questionId].orEmpty().filter { it.isNotBlank() }
    val free = freeText[questionId]?.trim().orEmpty()
    val parts = (selected + if (free.isNotEmpty()) listOf(free) else emptyList()).distinct()
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** All answerable questions with a non-empty answer. */
fun ClarifyDecision.answerMap(): Map<String, String> =
    questions()
        .mapNotNull { question -> answerFor(question.id)?.let { question.id to it } }
        .toMap()

/**
 * Toggles or selects one option without sending anything. Multi-select questions keep several
 * tokens; a single-select question replaces its previous token. The parent sends explicitly.
 */
fun ClarifyDecision.toggleChoice(
    questionId: String,
    choice: String,
): ClarifyDecision {
    val question = questions().firstOrNull { it.id == questionId }
    val current = selectedChoices[questionId].orEmpty()
    val next =
        if (question?.multiSelect == true) {
            if (choice in current) current - choice else current + choice
        } else {
            listOf(choice)
        }
    return copy(selectedChoices = selectedChoices + (questionId to next))
}

/** Records a free-text answer without sending anything. */
fun ClarifyDecision.withAnswer(
    questionId: String,
    answer: String,
): ClarifyDecision = copy(freeText = freeText + (questionId to answer))

data class ClarifyResponseCall(
    val method: String,
    val params: JsonObject,
)

/**
 * Builds the exact clarify response calls for this request. Canonical single questions use
 * `clarify.respond {session_id, execution_generation, prompt_id, answer}`; canonical batches lock
 * each answered question via `clarify.lock {request_id, question_id, answer}`. Legacy requests use
 * one `clarify.respond` per answer. Nothing is sent here, and no answer means no call.
 */
fun ClarifyDecision.responseCalls(
    sessionId: String? = request.sessionId,
    answers: Map<String, String> = emptyMap(),
): List<ClarifyResponseCall> {
    if (status != DecisionStatus.PENDING) return emptyList()
    val resolvedAnswers = answers.ifEmpty { answerMap() }
    val questions = questions()
    val canonicalPromptId = request.promptId ?: request.clarifyId
    val generation = executionGeneration ?: request.executionGeneration

    if (generation != null && canonicalPromptId != null) {
        val session = sessionId?.takeIf { it.isNotBlank() } ?: return emptyList()
        if (questions.size <= 1) {
            val questionId = questions.firstOrNull()?.id
            val answer =
                questionId?.let { resolvedAnswers[it] } ?: resolvedAnswers.values.firstOrNull()
            if (answer.isNullOrBlank() || questionId != null && questionId in lockedAnswers) {
                return emptyList()
            }
            return listOf(
                ClarifyResponseCall(
                    method = "clarify.respond",
                    params =
                        buildJsonObject {
                            put("session_id", session)
                            put("execution_generation", generation)
                            put("prompt_id", canonicalPromptId)
                            put("answer", answer)
                        },
                ),
            )
        }
        return questions.mapNotNull { question ->
            val answer = resolvedAnswers[question.id]
            if (answer.isNullOrBlank() || question.id in lockedAnswers) return@mapNotNull null
            ClarifyResponseCall(
                method = "clarify.lock",
                params =
                    buildJsonObject {
                        put("request_id", canonicalPromptId)
                        put("question_id", question.id)
                        put("answer", answer)
                    },
            )
        }
    }

    val session = sessionId?.takeIf { it.isNotBlank() } ?: return emptyList()
    if (questions.size <= 1) {
        val question = questions.firstOrNull()
        val answer =
            question?.let { resolvedAnswers[it.id] } ?: resolvedAnswers.values.firstOrNull()
        if (answer.isNullOrBlank() || question != null && question.id in lockedAnswers) {
            return emptyList()
        }
        val explicitQuestionId = request.questionId
        return listOf(
            ClarifyResponseCall(
                method = "clarify.respond",
                params =
                    buildJsonObject {
                        put("session_id", session)
                        put("response", answer)
                        put("answer", answer)
                        request.clarifyId?.let {
                            put("clarify_id", it)
                            put("request_id", it)
                        }
                        if (explicitQuestionId != null) put("question_id", explicitQuestionId)
                    },
            ),
        )
    }
    return questions.mapNotNull { question ->
        val answer = resolvedAnswers[question.id]
        if (answer.isNullOrBlank() || question.id in lockedAnswers) return@mapNotNull null
        ClarifyResponseCall(
            method = "clarify.respond",
            params =
                buildJsonObject {
                    put("session_id", session)
                    put("response", answer)
                    put("answer", answer)
                    request.clarifyId?.let {
                        put("clarify_id", it)
                        put("request_id", it)
                    }
                    put("question_id", question.id)
                },
        )
    }
}

private fun Conversation.messageStart(event: GatewayEvent.MessageStart): Conversation {
    val newTurn = event.turnId != null && event.turnId != turnId || !running
    val nextPrompts = if (newTurn) promotePrompt() else prompts
    return copy(
        prompts = nextPrompts,
        running = true,
        stream = LiveReply(text = ""),
        streamRevision = streamRevision + 1,
        error = null,
        turnId = event.turnId ?: turnId,
    )
}

/**
 * A new turn starts exactly one local prompt. A non-queued accepted/pending original wins over a
 * queued row; queued rows are only promoted on a genuinely new turn, never on a mid-turn segment.
 */
private fun Conversation.promotePrompt(): List<PendingPrompt> {
    val index =
        prompts
            .indexOfFirst {
                !it.queued && (it.status == PromptStatus.ACCEPTED || it.status == PromptStatus.PENDING)
            }.takeIf { it >= 0 }
            ?: prompts.indexOfFirst {
                it.status == PromptStatus.QUEUED ||
                    it.status == PromptStatus.CANCELLING ||
                    it.status == PromptStatus.UNKNOWN
            }
    if (index < 0) return prompts
    return prompts.mapIndexed { position, prompt ->
        if (position == index) {
            prompt.copy(status = PromptStatus.STARTED, error = null, outcomeUnknown = false)
        } else {
            prompt
        }
    }
}

private fun Conversation.appendText(text: String): Conversation {
    val reply = stream ?: LiveReply(text = "")
    return copy(
        running = true,
        stream = reply.copy(text = reply.text + text, streaming = true),
        streamRevision = streamRevision + 1,
    )
}

/**
 * `message.interim` carries an interim text snapshot when the gateway has one; replace rather than
 * append so replaying a snapshot cannot duplicate words. A seal-only frame just keeps the caret.
 */
private fun Conversation.interimMessage(text: String?): Conversation {
    val reply = stream ?: LiveReply(text = "")
    val snapshot = text?.takeIf { it.isNotEmpty() } ?: reply.text
    return copy(
        running = true,
        stream = reply.copy(text = snapshot, streaming = true),
        streamRevision = streamRevision + 1,
    )
}

private fun Conversation.completeMessage(event: GatewayEvent.MessageComplete): Conversation {
    val reply = stream ?: LiveReply(text = "")
    return copy(
        stream =
            reply.copy(
                text = event.text.ifEmpty { reply.text },
                reasoning = event.reasoning ?: reply.reasoning,
                streaming = !event.paused,
            ),
        streamRevision = streamRevision + 1,
    )
}

private fun Conversation.finishMessage(): Conversation {
    val reply = stream
    return copy(
        running = false,
        stream = reply?.copy(streaming = false),
        streamRevision = if (reply != null) streamRevision + 1 else streamRevision,
    )
}

private fun Conversation.finishTurn(): Conversation {
    val reply = stream
    return copy(
        running = false,
        stream = reply?.copy(streaming = false),
        streamRevision = if (reply != null) streamRevision + 1 else streamRevision,
    )
}

private fun Conversation.appendReasoning(text: String): Conversation {
    val reply = stream ?: LiveReply(text = "")
    return copy(
        stream = reply.copy(reasoning = reply.reasoning + text),
        streamRevision = streamRevision + 1,
    )
}

private fun Conversation.availableReasoning(text: String?): Conversation {
    if (text == null) return this
    val reply = stream ?: LiveReply(text = "")
    return copy(
        stream = reply.copy(reasoning = text),
        streamRevision = streamRevision + 1,
    )
}

private fun Conversation.toolActivity(event: GatewayEvent.Tool): Conversation {
    val line = oneLine(event.name, event.detail) ?: return this
    val reply = stream ?: LiveReply(text = "")
    return copy(
        stream = reply.copy(tool = line),
        streamRevision = streamRevision + 1,
    )
}

private fun Conversation.processActivity(event: GatewayEvent.Process): Conversation {
    val payload = event.payload
    val detail =
        payload["text"].scalar()
            ?: payload["message"].scalar()
            ?: payload["detail"].scalar()
            ?: payload["description"].scalar()
            ?: payload["command"].scalar()
    val label =
        event.type
            .removePrefix("process.")
            .replace('_', ' ')
            .ifBlank { "process" }
    val line = detail ?: label
    val reply = stream ?: LiveReply(text = "")
    return copy(
        stream = reply.copy(tool = line),
        streamRevision = streamRevision + 1,
    )
}

private fun Conversation.gatewayRunning(value: Boolean?): Conversation {
    if (value == null) return this
    val reply = stream
    val stopCaret = value == false && reply?.streaming == true
    return copy(
        running = value,
        stream = if (stopCaret) reply.copy(streaming = false) else reply,
        streamRevision = if (stopCaret) streamRevision + 1 else streamRevision,
    )
}

private fun Conversation.failTurn(message: String?): Conversation {
    val reply = stream
    return copy(
        running = false,
        stream = reply?.copy(streaming = false),
        error = message ?: "gateway error",
        streamRevision = if (reply != null) streamRevision + 1 else streamRevision,
    )
}

private fun Conversation.addApproval(request: GatewayEvent.ApprovalRequested): Conversation {
    val exists =
        approvals.any { decision ->
            if (request.requestId != null) {
                decision.request.requestId == request.requestId
            } else {
                decision.request == request
            }
        }
    if (exists) return this
    return copy(approvals = approvals + ApprovalDecision(request = request))
}

/** `approval.received` acknowledges delivery only; the decision stays sending until resolved. */
private fun Conversation.acknowledgeApproval(requestId: String?): Conversation {
    var changed = false
    val next =
        approvals.map { decision ->
            val live =
                decision.status == DecisionStatus.PENDING || decision.status == DecisionStatus.SENDING
            val matches =
                requestId == null ||
                    decision.request.requestId == requestId ||
                    decision.request.promptId == requestId
            if (live && matches) {
                changed = true
                decision.copy(acknowledged = true, error = null)
            } else {
                decision
            }
        }
    return if (changed) copy(approvals = next) else this
}

/** `approval.cancelled` is the real expiry: empty ids means every live approval was dropped. */
private fun Conversation.cancelApprovals(
    requestIds: List<String>,
    reason: String?,
): Conversation {
    val targets = requestIds.toSet()
    var changed = false
    val next =
        approvals.map { decision ->
            val live =
                decision.status == DecisionStatus.PENDING || decision.status == DecisionStatus.SENDING
            val requestId = decision.request.requestId
            val promptId = decision.request.promptId
            val matches =
                targets.isEmpty() ||
                    (requestId != null && requestId in targets) ||
                    (promptId != null && promptId in targets)
            if (live && matches) {
                changed = true
                decision.copy(status = DecisionStatus.EXPIRED, error = reason)
            } else {
                decision
            }
        }
    return if (changed) copy(approvals = next) else this
}

private fun Conversation.addClarify(request: GatewayEvent.ClarifyRequested): Conversation {
    val index =
        clarifications.indexOfFirst { decision ->
            val newId = request.clarifyId ?: request.promptId
            val oldId = decision.request.clarifyId ?: decision.request.promptId
            if (newId != null) oldId == newId else decision.request == request
        }
    if (index < 0) {
        return copy(
            clarifications =
                clarifications +
                    ClarifyDecision(
                        request = request,
                        executionGeneration = request.executionGeneration,
                        lockedAnswers = request.lockedAnswers,
                    ),
        )
    }
    val existing = clarifications[index]
    if (existing.status == DecisionStatus.SETTLED || existing.status == DecisionStatus.EXPIRED) {
        return this
    }
    val sameGeneration = existing.executionGeneration == request.executionGeneration
    val merged =
        if (sameGeneration) {
            existing.copy(
                request = request,
                executionGeneration = request.executionGeneration,
                lockedAnswers = existing.lockedAnswers + request.lockedAnswers,
            )
        } else {
            ClarifyDecision(
                request = request,
                executionGeneration = request.executionGeneration,
                lockedAnswers = request.lockedAnswers,
            )
        }
    return copy(
        clarifications =
            clarifications.mapIndexed { position, decision ->
                if (position == index) merged else decision
            },
    )
}

private fun Conversation.expireClarify(
    clarifyId: String?,
    settled: Boolean,
): Conversation {
    var changed = false
    val next =
        clarifications.map { decision ->
            val live =
                decision.status == DecisionStatus.PENDING || decision.status == DecisionStatus.SENDING
            val matches =
                clarifyId == null ||
                    decision.request.clarifyId == clarifyId ||
                    decision.request.promptId == clarifyId
            if (live && matches) {
                changed = true
                decision.copy(
                    status = if (settled) DecisionStatus.SETTLED else DecisionStatus.EXPIRED,
                    error = null,
                )
            } else {
                decision
            }
        }
    return if (changed) copy(clarifications = next) else this
}

private inline fun List<ApprovalDecision>.markApprovalsSending(
    requestId: String?,
    update: (ApprovalDecision) -> ApprovalDecision,
): List<ApprovalDecision> {
    var done = false
    return map { decision ->
        val live = decision.status == DecisionStatus.PENDING
        val matches = requestId == null || decision.request.requestId == requestId
        if (!done && live && matches) {
            done = true
            update(decision).copy(status = DecisionStatus.SENDING, error = null)
        } else {
            decision
        }
    }
}

private inline fun List<ApprovalDecision>.markApprovalsError(
    requestId: String?,
    update: (ApprovalDecision) -> ApprovalDecision,
): List<ApprovalDecision> {
    var done = false
    return map { decision ->
        val live =
            decision.status == DecisionStatus.PENDING || decision.status == DecisionStatus.SENDING
        val matches = requestId == null || decision.request.requestId == requestId
        if (!done && live && matches) {
            done = true
            update(decision).copy(status = DecisionStatus.PENDING)
        } else {
            decision
        }
    }
}

private inline fun List<ClarifyDecision>.markClarificationsSending(
    clarifyId: String?,
    update: (ClarifyDecision) -> ClarifyDecision,
): List<ClarifyDecision> {
    var done = false
    return map { decision ->
        val live = decision.status == DecisionStatus.PENDING
        val matches =
            clarifyId == null ||
                decision.request.clarifyId == clarifyId ||
                decision.request.promptId == clarifyId
        if (!done && live && matches) {
            done = true
            update(decision).copy(status = DecisionStatus.SENDING, error = null)
        } else {
            decision
        }
    }
}

private inline fun List<ClarifyDecision>.markClarificationsError(
    clarifyId: String?,
    update: (ClarifyDecision) -> ClarifyDecision,
): List<ClarifyDecision> {
    var done = false
    return map { decision ->
        val live =
            decision.status == DecisionStatus.PENDING || decision.status == DecisionStatus.SENDING
        val matches =
            clarifyId == null ||
                decision.request.clarifyId == clarifyId ||
                decision.request.promptId == clarifyId
        if (!done && live && matches) {
            done = true
            update(decision).copy(status = DecisionStatus.PENDING)
        } else {
            decision
        }
    }
}

private fun List<PendingPrompt>.replace(
    id: String,
    update: (PendingPrompt) -> PendingPrompt,
): List<PendingPrompt> = map { if (it.id == id) update(it) else it }

private fun Conversation.restoredComposer(text: String): String =
    when {
        text.isBlank() -> composer
        composer.isBlank() -> text
        else -> "$text\n$composer"
    }

private val rejectedStatuses = setOf("rejected", "denied", "failed", "error", "refused")

private fun JsonObject.failureMessage(): String? = get("message").scalar() ?: get("error").scalar() ?: get("detail").scalar()

private fun JsonElement?.scalar(): String? = (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

private fun oneLine(
    name: String?,
    detail: String?,
): String? {
    val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
    val cleanDetail = detail?.trim()?.takeIf { it.isNotEmpty() }
    return when {
        cleanName != null && cleanDetail != null -> "$cleanName — $cleanDetail"
        cleanName != null -> cleanName
        cleanDetail != null -> cleanDetail
        else -> null
    }
}

private fun normalizeText(text: String): String = text.trim().replace(Regex("\\s+"), " ")
