package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.GatewayEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val LIVE_STATUSES = setOf("starting", "waiting", "working", "streaming", "resuming")

/** An absent session in a valid active-list snapshot is idle; a malformed snapshot proves nothing. */
internal fun activeSessionIds(
    result: JsonElement?,
    storedId: (String) -> String,
): Set<String> {
    val entries = (result as? JsonObject)?.get("sessions") as? JsonArray
    checkNotNull(entries) { "Dashboard returned no active-session snapshot" }
    return buildSet {
        for (entry in entries) {
            val row = entry as? JsonObject ?: error("Dashboard returned an invalid active session")
            val id =
                (row["session_key"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: (row["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val status = (row["status"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            check(!id.isNullOrBlank() && !status.isNullOrBlank()) { "Dashboard returned an incomplete active session" }
            if (status.lowercase() in LIVE_STATUSES) add(storedId(id))
        }
    }
}

/** Used on open/resume and before send: remembered flags never win over a fresh gateway snapshot. */
internal fun AppState.reconcileActivity(
    active: Set<String>,
    changed: Set<String>,
): AppState =
    copy(
        liveSessions = (active - changed) + (liveSessions intersect changed),
        conversations =
            conversations.mapValues { (id, conversation) ->
                if (id in changed) {
                    conversation
                } else {
                    conversation.reduce(
                        GatewayEvent.SessionStatus(null, id in active, id),
                    )
                }
            },
    )
