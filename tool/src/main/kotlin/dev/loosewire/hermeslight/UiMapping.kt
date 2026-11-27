package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.ui.SessionRowUi
import dev.loosewire.hermeslight.ui.StatusLineModel
import dev.loosewire.hermeslight.ui.StatusStripUi
import dev.loosewire.hermeslight.ui.StatusTone
import dev.loosewire.hermeslight.ui.ThreadMessageKind
import dev.loosewire.hermeslight.ui.ThreadMessageUi
import dev.loosewire.hermeslight.ui.ThreadNoticeKind
import dev.loosewire.hermeslight.ui.ThreadNoticeUi
import dev.loosewire.hermeslight.ui.ThreadPosition
import dev.loosewire.hermeslight.ui.ThreadUi
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun AppState.statusUi(): StatusLineModel =
    StatusLineModel(
        status,
        when {
            status.startsWith("error:") -> StatusTone.Error
            status == "connected" -> StatusTone.Ok
            status.startsWith("connecting") -> StatusTone.Busy
            status.startsWith("reconnecting") -> StatusTone.Warn
            else -> StatusTone.Idle
        },
    )

/** Pending approvals win over running turns, even when their session is not currently selected. */
internal fun AppState.statusStripUi(): StatusStripUi? {
    var approvals = 0
    var clarifications = 0
    var approvalSession: String? = null
    var clarificationSession: String? = null
    for ((session, notices) in desktopNotices) {
        for (notice in notices) {
            if (notice.clarification) {
                if (clarifications == 0) clarificationSession = session
                clarifications++
            } else {
                if (approvals == 0) approvalSession = session
                approvals++
            }
        }
    }
    if (approvals == 0 && clarifications == 0 && liveSessions.isEmpty()) return null
    val summary =
        buildString {
            if (approvals > 0) {
                append(approvals).append(if (approvals == 1) " approval" else " approvals")
            }
            if (clarifications > 0) {
                if (isNotEmpty()) append(" · ")
                append(clarifications).append(if (clarifications == 1) " clarification" else " clarifications")
            }
            if (liveSessions.isNotEmpty()) {
                if (isNotEmpty()) append(" · ")
                append(liveSessions.size).append(" running")
            }
        }
    val session =
        when {
            approvals > 0 -> approvalSession
            clarifications > 0 -> clarificationSession
            else -> liveSessions.first()
        }
    return StatusStripUi(summary, session?.takeUnless { it == GLOBAL_NOTICE })
}

internal fun AppState.sessionRows(now: Long): List<SessionRowUi> =
    sessions.map { session ->
        SessionRowUi(
            id = session.id,
            title = session.title.ifBlank { session.id },
            source = session.source ?: "unknown",
            age = ageLabel(session.updatedAtEpochMs, now),
            live = session.id in liveSessions,
            profileId = session.profile,
            detail = session.preview,
        )
    }

internal fun ageLabel(
    timestamp: Long?,
    now: Long,
): String {
    if (timestamp == null) return "age unknown"
    val minutes = ((now - timestamp).coerceAtLeast(0) / 60_000)
    return when {
        minutes == 0L -> "now"
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h"
        else -> "${minutes / 1440}d"
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("MMM d HH:mm")

internal fun AppState.threadUi(): ThreadUi {
    val toolNames = buildMap { messages.forEach { putAll(it.toolCalls) } }
    val rows =
        messages.flatMap { message ->
            val id = message.rowId.toString()
            val timestamp =
                message.timestampEpochMs?.let {
                    timeFormat.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                }
            val body =
                ThreadMessageUi(
                    id = id,
                    kind =
                        when {
                            message.displayKind != null -> ThreadMessageKind.System
                            message.role == "tool" || message.type == "tool" -> ThreadMessageKind.Tool
                            message.type == "reasoning" -> ThreadMessageKind.Reasoning
                            message.role == "user" -> ThreadMessageKind.User
                            message.role == "assistant" -> ThreadMessageKind.Assistant
                            else -> ThreadMessageKind.System
                        },
                    body = message.text,
                    timestamp = timestamp,
                    toolName = message.toolName ?: toolNames[message.toolCallId] ?: message.toolCallId,
                    toolOutcome = message.toolOutcome,
                    expandableText = message.text,
                )
            if (message.reasoning.isNullOrBlank()) {
                listOf(body)
            } else {
                listOf(
                    ThreadMessageUi(
                        id = "$id:reasoning",
                        kind = ThreadMessageKind.Reasoning,
                        body = message.reasoning,
                        timestamp = timestamp,
                        expandableText = message.reasoning,
                    ),
                    body,
                )
            }
        }
    val notices =
        (desktopNotices[GLOBAL_NOTICE].orEmpty() + desktopNotices[selectedSession].orEmpty()).map { notice ->
            ThreadNoticeUi(
                notice.id,
                if (notice.clarification) ThreadNoticeKind.Clarify else ThreadNoticeKind.Approval,
                notice.title,
                notice.origin,
                notice.detail,
            )
        }
    return ThreadUi(
        sessionId = selectedSession,
        title = sessions.firstOrNull { it.id == selectedSession }?.title ?: "Thread",
        botName = bots.firstOrNull { it.id == selectedBot }?.name ?: "June",
        messages = rows,
        notices = notices,
        position = positionRow?.let { ThreadPosition(it.toString(), positionOffset) },
        hasOlder = threadHasOlder,
        hasNewer = threadHasNewer,
        loading = threadLoading && messages.isEmpty(),
        loadingOlder = threadLoading && messages.isNotEmpty(),
        error = operationError,
        emptyText = if (selectedSession == null) "Use History to choose a session." else "No messages yet.",
        status = statusUi(),
    )
}
