package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.data.SessionSummary
import dev.loosewire.hermeslight.ui.SessionActionMode
import dev.loosewire.hermeslight.ui.SessionActionUi
import kotlinx.coroutines.CancellationException

/**
 * Search, rename and delete transitions for the loaded session list.
 *
 * The coordinator is deliberately network-agnostic: [rename] and [delete] only resolve after the
 * dashboard acknowledged the request. Nothing is removed or retitled before then, and a failure
 * leaves the old row intact. [onDeleted] reports an acknowledged removal so the controller can
 * adjust paging and persisted state without reloading the list.
 */
internal class SessionManagement(
    private val state: () -> AppState,
    private val update: ((AppState) -> AppState) -> Unit,
    private val rename: suspend (id: String, title: String) -> Unit,
    private val delete: suspend (id: String) -> Unit,
    private val onChanged: () -> Unit = {},
    private val onDeleted: (id: String, selected: Boolean, loaded: Boolean) -> Unit = { _, _, _ -> },
    private val current: () -> Boolean = { true },
) {
    private var operation = 0L

    /** A profile or scope switch makes any in-flight acknowledgement unusable. */
    fun invalidate() {
        operation++
    }

    fun search(query: String) {
        update { it.copy(sessionsQuery = query) }
    }

    fun showActions(id: String) {
        val opening = state()
        if (opening.historyBusy) return
        val session =
            opening.sessions.firstOrNull { it.id == id }
                ?: opening.menuSessions.firstOrNull { it.id == id }
                ?: return
        update {
            it.copy(
                historyAction = SessionActionUi(session.id, session.title, SessionActionMode.Actions),
                historyError = null,
            )
        }
    }

    fun beginRename() {
        stage(SessionActionMode.Rename)
    }

    fun beginDelete() {
        stage(SessionActionMode.ConfirmDelete)
    }

    fun dismiss() {
        update {
            if (it.historyBusy) {
                it
            } else {
                it.copy(historyAction = null, historyError = null)
            }
        }
    }

    suspend fun renameSession(title: String) {
        val opening = state()
        val action = opening.historyAction ?: return
        if (opening.historyBusy || action.mode != SessionActionMode.Rename) return
        val trimmed = title.trim()
        if (trimmed.isEmpty()) {
            update { it.copy(historyError = "title cannot be empty") }
            return
        }
        val token = ++operation
        update { it.copy(historyBusy = true, historyError = null) }
        try {
            rename(action.id, trimmed)
            if (token != operation || !current()) return
            update { it.renamedSession(action.id, trimmed) }
            onChanged()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (token != operation || !current()) return
            update {
                it.copy(
                    historyAction = null,
                    historyError = "rename failed — ${failureReason(error)}",
                )
            }
        } finally {
            if (token == operation && current()) update { it.copy(historyBusy = false) }
        }
    }

    suspend fun confirmSessionDelete() {
        val opening = state()
        val action = opening.historyAction ?: return
        if (opening.historyBusy || action.mode != SessionActionMode.ConfirmDelete) return
        val token = ++operation
        update { it.copy(historyBusy = true, historyError = null) }
        try {
            delete(action.id)
            if (token != operation || !current()) return
            val selected = state().selectedSession == action.id
            val loaded = state().sessions.any { it.id == action.id }
            update { it.deletedSession(action.id) }
            onChanged()
            onDeleted(action.id, selected, loaded)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (token != operation || !current()) return
            update {
                it.copy(
                    historyAction = null,
                    historyError = "delete failed — ${failureReason(error)}",
                )
            }
        } finally {
            if (token == operation && current()) update { it.copy(historyBusy = false) }
        }
    }

    private fun stage(mode: SessionActionMode) {
        update {
            val action = it.historyAction
            if (it.historyBusy || action == null || action.mode != SessionActionMode.Actions) {
                it
            } else {
                it.copy(historyAction = action.copy(mode = mode), historyError = null)
            }
        }
    }
}

/** Case-insensitive title/preview filter for the loaded list; an empty query is the full list. */
internal fun filterSessions(
    sessions: List<SessionSummary>,
    query: String,
): List<SessionSummary> {
    if (query.isEmpty()) return sessions
    return sessions.filter { session ->
        session.title.contains(query, ignoreCase = true) ||
            session.preview?.contains(query, ignoreCase = true) == true
    }
}

/** Server-acknowledged rename applied to both the Sessions list and the menu snapshot. */
internal fun AppState.renamedSession(
    id: String,
    title: String,
): AppState {
    fun List<SessionSummary>.retitled() = map { row -> if (row.id == id) row.copy(title = title) else row }
    return copy(
        sessions = sessions.retitled(),
        menuSessions = menuSessions.retitled(),
        historyAction = null,
        historyError = null,
    )
}

/**
 * Server-acknowledged delete. Only the acknowledged id leaves the rows; an open thread for that
 * id is closed and related per-session state is dropped. Paging is adjusted by the controller.
 */
internal fun AppState.deletedSession(id: String): AppState {
    val wasSelected = selectedSession == id
    return copy(
        sessions = sessions.filterNot { it.id == id },
        menuSessions = menuSessions.filterNot { it.id == id },
        menuSessionCount = menuSessionCount?.let { (it - 1).coerceAtLeast(0) },
        selectedSession = selectedSession?.takeUnless { it == id },
        createdSession = createdSession?.takeUnless { it == id },
        messages = if (wasSelected) emptyList() else messages,
        threadLoading = if (wasSelected) false else threadLoading,
        threadHasOlder = if (wasSelected) false else threadHasOlder,
        threadHasNewer = if (wasSelected) false else threadHasNewer,
        positionRow = if (wasSelected) null else positionRow,
        positionOffset = if (wasSelected) 0 else positionOffset,
        conversations = conversations - id,
        desktopNotices = desktopNotices - id,
        liveSessions = liveSessions - id,
        sessionsRequired = if (wasSelected) true else sessionsRequired,
        historyAction = null,
        historyError = null,
    )
}

private fun failureReason(error: Exception): String =
    error.message
        ?.trim()
        ?.replace(Regex("\\s+"), " ")
        ?.takeIf { it.isNotEmpty() }
        ?.take(120)
        ?: "dashboard request failed"
