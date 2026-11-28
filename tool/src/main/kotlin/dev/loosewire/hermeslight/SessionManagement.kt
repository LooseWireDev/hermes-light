package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.data.SessionPage
import dev.loosewire.hermeslight.data.SessionSummary
import dev.loosewire.hermeslight.net.ApiErrorKind
import dev.loosewire.hermeslight.net.ApiException
import dev.loosewire.hermeslight.ui.SessionActionMode
import dev.loosewire.hermeslight.ui.SessionActionUi
import kotlinx.coroutines.CancellationException

/** Shortest query worth a dashboard round trip; shorter input stays a local filter. */
private const val SEARCH_MIN_LENGTH = 2

/**
 * Search, rename and delete transitions for the loaded session list.
 *
 * The coordinator is deliberately network-agnostic: [rename] and [delete] only resolve after the
 * dashboard acknowledged the request. Nothing is removed or retitled before then, and a failure
 * leaves the old row intact. [onDeleted] reports an acknowledged removal so the controller can
 * adjust paging and persisted state without reloading the list.
 *
 * [search] updates the query synchronously and [loadSearch] fills [AppState.sessionsSearch] from
 * [searchRequest]. A response is installed only while its query and generation are still current;
 * an endpoint failure falls back to local title/preview filtering. The controller owns debouncing
 * and cancelling the coroutine that runs [loadSearch].
 */
internal class SessionManagement(
    private val state: () -> AppState,
    private val update: ((AppState) -> AppState) -> Unit,
    private val rename: suspend (id: String, title: String) -> Unit,
    private val delete: suspend (id: String) -> Unit,
    private val onChanged: () -> Unit = {},
    private val onDeleted: (id: String, selected: Boolean, loaded: Boolean) -> Unit = { _, _, _ -> },
    private val current: () -> Boolean = { true },
    private val searchRequest: suspend (query: String) -> SessionPage = {
        throw ApiException("search unavailable on this dashboard", ApiErrorKind.UNSUPPORTED)
    },
) {
    private var operation = 0L
    private var searchGeneration = 0L

    /** A profile or scope switch makes any in-flight acknowledgement or search unusable. */
    fun invalidate() {
        operation++
        searchGeneration++
        update { it.copy(sessionsSearchLoading = false) }
    }

    /**
     * Immediately reflects [query] in the text field. A long enough query starts the loading state
     * and drops the previous server page so local filtering applies until [loadSearch] settles.
     */
    fun search(query: String) {
        val searching = query.trim().length >= SEARCH_MIN_LENGTH
        searchGeneration++
        update {
            it.copy(
                sessionsQuery = query,
                sessionsSearch = null,
                sessionsSearchLoading = searching,
            )
        }
    }

    /**
     * Runs a dashboard search and installs the page only if [query] is still the current one and no
     * newer search or acknowledged mutation superseded it. Failures leave local filtering in place.
     * Cancellation always propagates; a cancelled stale load does not touch the loading flag.
     */
    suspend fun loadSearch(query: String) {
        if (state().sessionsQuery != query) return
        val requested = query.trim()
        if (requested.length < SEARCH_MIN_LENGTH) {
            searchGeneration++
            update { it.copy(sessionsSearch = null, sessionsSearchLoading = false) }
            return
        }
        val token = ++searchGeneration
        update { it.copy(sessionsSearchLoading = true) }
        val page =
            try {
                searchRequest(requested)
            } catch (error: CancellationException) {
                if (token == searchGeneration) update { it.copy(sessionsSearchLoading = false) }
                throw error
            } catch (error: Exception) {
                if (token != searchGeneration) return
                update { it.copy(sessionsSearch = null, sessionsSearchLoading = false) }
                return
            }
        if (token != searchGeneration || state().sessionsQuery != query) return
        update { it.copy(sessionsSearch = page, sessionsSearchLoading = false) }
    }

    fun showActions(id: String) {
        val opening = state()
        if (opening.historyBusy) return
        val session =
            opening.sessionsSearch?.sessions?.firstOrNull { it.id == id }
                ?: opening.sessions.firstOrNull { it.id == id }
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
            searchGeneration++
            update { it.renamedSession(action.id, trimmed).copy(sessionsSearchLoading = false) }
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
            searchGeneration++
            update { it.deletedSession(action.id).copy(sessionsSearchLoading = false) }
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

/** Rows the Sessions screen renders: the server page when one exists, else local filtering. */
internal fun AppState.visibleSessions(): List<SessionSummary> = sessionsSearch?.sessions ?: filterSessions(sessions, sessionsQuery)

/** Count shown beside the Sessions screen: the server total while a search page is installed. */
internal fun AppState.sessionSearchTotal(): Int = sessionsSearch?.total ?: sessions.size

/** Server-acknowledged rename applied to the Sessions list, an active search page and the menu. */
internal fun AppState.renamedSession(
    id: String,
    title: String,
): AppState {
    fun List<SessionSummary>.retitled() = map { row -> if (row.id == id) row.copy(title = title) else row }
    return copy(
        sessions = sessions.retitled(),
        sessionsSearch = sessionsSearch?.let { it.copy(sessions = it.sessions.retitled()) },
        menuSessions = menuSessions.retitled(),
        historyAction = null,
        historyError = null,
    )
}

/**
 * Server-acknowledged delete. Only the acknowledged id leaves the rows; an open thread for that
 * id is closed and related per-session state is dropped. Paging is adjusted by the controller.
 * A search page loses the row and one from its total only when the acknowledged id is in it.
 */
internal fun AppState.deletedSession(id: String): AppState {
    val wasSelected = selectedSession == id
    return copy(
        sessions = sessions.filterNot { it.id == id },
        sessionsSearch =
            sessionsSearch?.let { page ->
                if (page.sessions.none { it.id == id }) {
                    page
                } else {
                    page.copy(
                        sessions = page.sessions.filterNot { it.id == id },
                        total = (page.total - 1).coerceAtLeast(0),
                    )
                }
            },
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
