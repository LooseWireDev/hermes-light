package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.GatewayEvent
import dev.loosewire.hermeslight.net.GatewayState

internal data class DesktopNotice(
    val id: String,
    val clarification: Boolean,
    val title: String,
    val origin: String,
    val detail: String? = null,
)

internal fun connectionLabel(state: GatewayState): String =
    when (state) {
        GatewayState.Disconnected -> "disconnected"
        GatewayState.Connecting -> "connecting…"
        GatewayState.Connected -> "connected"
        is GatewayState.Reconnecting -> "reconnecting (${state.attempt})"
        is GatewayState.AuthFailed -> "error: ${state.message}"
    }

/** Session identity comes from the frame, never from whichever screen happens to be open. */
internal fun reduceGatewayEvent(
    state: AppState,
    event: GatewayEvent,
): AppState {
    val session = event.sessionId
    val updated =
        if (session == null) {
            state
        } else {
            state.copy(conversations = state.conversations + (session to (state.conversations[session] ?: Conversation()).reduce(event)))
        }
    return reduceAttention(updated, event)
}

private fun reduceAttention(
    state: AppState,
    event: GatewayEvent,
): AppState {
    val session = event.sessionId
    val key = session ?: GLOBAL_NOTICE
    val origin = session ?: "dashboard — session unspecified"

    fun notice(value: DesktopNotice): AppState {
        val placeholder = if (value.clarification) "clarify:$key" else "approval:$key"
        val notices = state.desktopNotices[key].orEmpty().filterNot { it.id == value.id || it.id == placeholder } + value
        return state.copy(desktopNotices = state.desktopNotices + (key to notices))
    }

    fun remove(
        clarification: Boolean,
        id: String?,
    ): AppState {
        val notices =
            state.desktopNotices[key].orEmpty().filterNot {
                val placeholder = if (clarification) "clarify:$key" else "approval:$key"
                it.clarification == clarification && (id == null || it.id == id || it.id == placeholder)
            }
        return state.copy(desktopNotices = state.desktopNotices + (key to notices))
    }

    fun live(running: Boolean): AppState =
        if (session == null) {
            state
        } else {
            state.copy(
                liveSessions = if (running) state.liveSessions + session else state.liveSessions - session,
            )
        }
    return when (event) {
        is GatewayEvent.MessageStart -> live(true)
        is GatewayEvent.SessionStatus ->
            when (event.running) {
                true -> live(true)
                false -> live(false)
                null -> state
            }
        is GatewayEvent.Done -> live(false)
        is GatewayEvent.Failure -> live(false).copy(operationError = "error: ${event.message ?: "gateway error"}")
        is GatewayEvent.ApprovalRequested ->
            notice(
                DesktopNotice(
                    id = event.requestId ?: "approval:$key",
                    clarification = false,
                    title = "approval pending",
                    origin = origin,
                    detail = event.label ?: event.description ?: event.command ?: event.path,
                ),
            )
        is GatewayEvent.ApprovalPending ->
            if (state.desktopNotices[key].orEmpty().any { !it.clarification }) {
                state
            } else {
                notice(DesktopNotice("approval:$key", false, "approval pending — respond on desktop", origin))
            }
        is GatewayEvent.ApprovalReceived -> state
        is GatewayEvent.ApprovalCancelled -> {
            val ids = event.requestIds
            val notices =
                state.desktopNotices[key].orEmpty().filterNot {
                    !it.clarification && (ids.isEmpty() || it.id in ids || it.id == "approval:$key")
                }
            state.copy(desktopNotices = state.desktopNotices + (key to notices))
        }
        is GatewayEvent.ClarifyRequested ->
            notice(
                DesktopNotice(
                    id = event.clarifyId ?: "clarify:$key",
                    clarification = true,
                    title = "clarification pending",
                    origin = origin,
                    detail = event.text ?: event.questions.firstOrNull()?.question,
                ),
            )
        is GatewayEvent.ClarifyExpired -> remove(true, event.clarifyId)
        else -> state
    }
}

/** A confirmed RPC resolution or pending snapshot, not a display acknowledgement. */
internal fun AppState.settleApproval(
    session: String,
    requestId: String?,
): AppState {
    val notices =
        desktopNotices[session].orEmpty().filterNot {
            !it.clarification && (requestId == null || it.id == requestId || it.id == "approval:$session")
        }
    val conversation = conversations[session]?.settleApprovalResponse(requestId)
    return copy(
        desktopNotices = desktopNotices + (session to notices),
        conversations = if (conversation == null) conversations else conversations + (session to conversation),
    )
}

internal fun AppState.clearMissingSession(id: String): AppState =
    if (selectedSession != id) {
        this
    } else {
        copy(
            selectedSession = null,
            messages = emptyList(),
            positionRow = null,
            positionOffset = 0,
            threadHasOlder = false,
            threadHasNewer = false,
            threadLoading = false,
            operationError = null,
            conversations = conversations - id,
            desktopNotices = desktopNotices - id,
            liveSessions = liveSessions - id,
            sessionsRequired = true,
        )
    }

internal const val GLOBAL_NOTICE = "\u0000dashboard"
