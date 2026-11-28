package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.ui.ThreadMessageKind
import dev.loosewire.hermeslight.ui.ThreadMessageUi

internal fun ApprovalDecision.rowKey(): String = request.requestId ?: "pending"

internal fun ClarifyDecision.rowKey(): String = request.clarifyId ?: request.promptId ?: "pending"

/** Temporary rows never claim prompt delivery or request settlement from a button press. */
internal fun Conversation.liveRows(): List<ThreadMessageUi> =
    buildList {
        fun line(
            id: String,
            text: String,
            action: String? = null,
            kind: ThreadMessageKind = ThreadMessageKind.System,
        ) {
            add(ThreadMessageUi(id = id, kind = kind, body = text, action = action))
        }

        fun promptRow(prompt: PendingPrompt) {
            val state =
                when (prompt.status) {
                    PromptStatus.PREPARING -> "preparing"
                    PromptStatus.PENDING -> "pending"
                    PromptStatus.ACCEPTED -> "accepted — awaiting transcript"
                    PromptStatus.QUEUED -> "queued"
                    PromptStatus.CANCELLING -> "cancelling…"
                    PromptStatus.FAILED -> "rejected"
                    PromptStatus.UNKNOWN -> "outcome unknown — reconcile before resending"
                    PromptStatus.STARTED -> ""
                }
            if (prompt.status == PromptStatus.STARTED) {
                line("prompt:${prompt.id}", prompt.text, kind = ThreadMessageKind.User)
            } else {
                line("prompt:${prompt.id}", "$state: ${prompt.text}")
            }
            prompt.error?.let { line("prompt:${prompt.id}:error", it, kind = ThreadMessageKind.Error) }
            if (prompt.status == PromptStatus.FAILED) {
                line("prompt:${prompt.id}:retry", "Retry", "retry:${prompt.id}")
            }
            if (prompt.status == PromptStatus.QUEUED) {
                if (prompt.admissionId != null) {
                    line("prompt:${prompt.id}:cancel", "Cancel queued prompt", "cancel:${prompt.id}")
                } else {
                    line("prompt:${prompt.id}:receipt", "Queued — cancellation receipt unavailable")
                }
            }
        }

        for (prompt in prompts) if (!prompt.queued || prompt.status == PromptStatus.STARTED) promptRow(prompt)
        stream?.let { reply ->
            if (reply.reasoning.isNotBlank()) {
                add(ThreadMessageUi("live:reasoning", ThreadMessageKind.Reasoning, reply.reasoning))
            }
            reply.tool?.let {
                add(ThreadMessageUi("live:tool", ThreadMessageKind.Tool, body = it, title = it))
            }
            if (reply.text.isNotBlank() || reply.streaming) {
                add(
                    ThreadMessageUi(
                        id = "live:assistant",
                        kind = ThreadMessageKind.Assistant,
                        body = reply.text + if (reply.streaming) " ▏" else "",
                    ),
                )
            }
        }
        for (prompt in prompts) if (prompt.queued && prompt.status != PromptStatus.STARTED) promptRow(prompt)
        if (running) line("control:stop", if (stopping) "stopping…" else "Stop", if (stopping) null else "stop")
        stopError?.let { line("control:error", it, kind = ThreadMessageKind.Error) }
        error?.let { line("live:error", it, kind = ThreadMessageKind.Error) }

        for (approval in approvals) {
            val key = approval.rowKey()
            val request = approval.request
            val question = request.label ?: request.description ?: request.command ?: request.path ?: request.host ?: "Approval"
            line("approval:$key", question)
            listOfNotNull(request.host, request.path, request.risk).distinct().takeIf { it.isNotEmpty() }?.let {
                line("approval:$key:origin", it.joinToString(" · "))
            }
            when (approval.status) {
                DecisionStatus.PENDING -> {
                    val choices = approval.allowedChoices()
                    if (choices.isEmpty()) line("approval:$key:unavailable", "Details unavailable — answer on desktop")
                    for (choice in choices) {
                        val label =
                            when (choice) {
                                "once" -> "Yes"
                                "deny" -> "No"
                                else -> "Always"
                            }
                        line("approval:$key:$choice", label, "approval:$key:$choice")
                    }
                }
                DecisionStatus.SENDING -> line("approval:$key:sending", "sending…")
                DecisionStatus.SETTLED -> line("approval:$key:settled", "approval settled")
                DecisionStatus.EXPIRED -> line("approval:$key:expired", "expired — answer on desktop")
            }
            approval.error?.let { line("approval:$key:error", it, kind = ThreadMessageKind.Error) }
        }
        for (clarify in clarifications) {
            val key = clarify.rowKey()
            when (clarify.status) {
                DecisionStatus.PENDING -> {
                    val questions = clarify.questions()
                    for (question in questions) {
                        line("clarify:$key:${question.id}", question.question)
                        if (question.id in clarify.lockedAnswers) {
                            line("clarify:$key:${question.id}:locked", "answered — ${clarify.lockedAnswers[question.id]}")
                            continue
                        }
                        if (question.choices.isEmpty()) {
                            line("clarify:$key:${question.id}:desktop", "No options supplied — answer on desktop")
                        }
                        for ((index, choice) in question.choices.withIndex()) {
                            val selected = choice in clarify.selectedChoices[question.id].orEmpty()
                            line(
                                "clarify:$key:${question.id}:$index",
                                (if (selected) "✓ " else "") + choice,
                                "clarify:$key:${question.id}:$index",
                            )
                        }
                    }
                    if (questions.size > 1 || questions.any { it.multiSelect }) {
                        val ready =
                            questions.any { it.id !in clarify.lockedAnswers } &&
                                questions.all { it.id in clarify.lockedAnswers || !clarify.answerFor(it.id).isNullOrBlank() }
                        line("clarify:$key:send", "Send answers", if (ready) "clarify:$key:send" else null)
                    }
                }
                DecisionStatus.SENDING -> line("clarify:$key:sending", "sending…")
                DecisionStatus.SETTLED -> line("clarify:$key:settled", "clarification settled")
                DecisionStatus.EXPIRED -> line("clarify:$key:expired", "expired — answer on desktop")
            }
            clarify.error?.let { line("clarify:$key:error", it, kind = ThreadMessageKind.Error) }
        }
    }
