package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.SessionRuntime
import kotlinx.coroutines.CancellationException

/** One explicit user action; creation is never retried and an unsent recovery stays in its old row. */
internal class SessionCreation(
    private val state: () -> AppState,
    private val update: ((AppState) -> AppState) -> Unit,
    private val runtime: SessionRuntime,
    private val create: suspend () -> String,
    private val opened: (String) -> Unit,
    private val submit: (String, String) -> Unit,
) {
    suspend fun start() {
        createAndOpen(null, null)
    }

    suspend fun recover(
        session: String,
        promptId: String,
    ) {
        val prompt =
            state().conversations[session]?.prompts?.firstOrNull {
                it.id == promptId && it.status == PromptStatus.FAILED && it.rejectionCode == SESSION_CONFLICT_CODE && !it.recovering
            } ?: return
        createAndOpen(session, prompt)
    }

    private suspend fun createAndOpen(
        session: String?,
        prompt: PendingPrompt?,
    ) {
        if (state().creatingSession) return
        update { current ->
            val marked =
                if (session == null || prompt == null) {
                    current
                } else {
                    current.withRecovery(session, prompt.id, true)
                }
            marked.copy(creatingSession = true, operationError = null)
        }
        try {
            val id = create()
            runtime.registerCreated(id)
            // A fresh session is already a runtime. Do not REST-hydrate/resume it as an old thread.
            update { it.openCreatedSession(id, prompt?.text.orEmpty()) }
            opened(id)
            if (prompt != null && session != null) {
                submit(id, prompt.text)
                update { current ->
                    val source = current.conversations[session] ?: return@update current
                    current.copy(
                        conversations =
                            current.conversations +
                                (session to source.copy(prompts = source.prompts.filterNot { it.id == prompt.id })),
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            update { it.copy(operationError = error.message ?: "Unable to create session — text retained") }
        } finally {
            update { current ->
                val restored =
                    if (session == null || prompt == null) {
                        current
                    } else {
                        current.withRecovery(session, prompt.id, false)
                    }
                restored.copy(creatingSession = false)
            }
        }
    }
}

internal fun AppState.openCreatedSession(
    id: String,
    text: String = "",
): AppState =
    copy(
        selectedSession = id,
        createdSession = id,
        messages = emptyList(),
        threadLoading = false,
        threadHasOlder = false,
        threadHasNewer = false,
        positionRow = null,
        positionOffset = 0,
        sessionsRequired = false,
        operationError = null,
        conversations = conversations + (id to (conversations[id] ?: Conversation()).copy(composer = text)),
    )

private fun AppState.withRecovery(
    session: String,
    promptId: String,
    recovering: Boolean,
): AppState {
    val conversation = conversations[session] ?: return this
    val prompts = conversation.prompts.map { if (it.id == promptId) it.copy(recovering = recovering) else it }
    return copy(
        conversations = conversations + (session to conversation.copy(prompts = prompts)),
    )
}
