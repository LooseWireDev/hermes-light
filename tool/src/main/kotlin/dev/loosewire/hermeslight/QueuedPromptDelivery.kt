/*
 * Copyright 2026 LooseWireDev
 *
 * Queued-send recovery: a queued admission that never starts is cancelled with an explicit
 * receipt before one direct resend. Unknown outcomes are never retried blindly.
 */
package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.net.GatewayRpcException
import dev.loosewire.hermeslight.net.RpcFailureKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

/** Row error for a promoted direct send that is proven not to have started. */
internal const val QUEUED_START_FAILURE: String = "could not start — retry"

private const val WAIT_MILLIS: Long = 5_000L
private const val UNKNOWN_CANCEL: String = "Queued prompt could not be cancelled — reconcile with gateway"
private const val UNKNOWN_CANCEL_AGAIN: String = "Prompt was queued again and could not be cancelled — reconcile with gateway"
private const val UNKNOWN_NO_RECEIPT: String = "Queued prompt has no cancellation receipt — reconcile with gateway"
private const val UNKNOWN_POLL: String = "Prompt state could not be verified — reconcile with gateway"
private const val UNKNOWN_SUBMIT: String = "Prompt outcome unknown — reconcile before resending"

/**
 * Watches one locally submitted prompt after its initial `prompt.submit` result. A queued row is
 * monitored until the gateway starts it or goes idle; an idle queue is cancelled with an explicit
 * receipt, promoted in place, and resent exactly once. A promoted row is only checked for start.
 *
 * All state is read through [conversation] and mutated through [update], and is re-read after every
 * suspending call so a gateway event or transcript reconcile always wins a race.
 */
internal class QueuedPromptDelivery(
    private val conversation: () -> Conversation,
    private val update: ((Conversation) -> Conversation) -> Unit,
    private val poll: suspend () -> Boolean?,
    private val cancel: suspend (PendingPrompt) -> Boolean,
    private val submit: suspend (String) -> JsonElement?,
    private val waitForCheck: suspend () -> Unit = { delay(WAIT_MILLIS) },
) {
    /**
     * Resumes recovery for [id]. Queued rows may be promoted once; a promoted row is only checked
     * for start. Callers rearm this after a pause, so already-promoted rows never resend.
     */
    suspend fun confirm(id: String) {
        val initial = prompt(id) ?: return
        if (initial.promoted) {
            // One-shot promotion: a promoted row is only checked for start, even if a later
            // receipt queued it again. Its late admission is cancelled, never sent again.
            if (initial.status == PromptStatus.QUEUED ||
                (
                    !initial.queued &&
                        (initial.status == PromptStatus.ACCEPTED || initial.status == PromptStatus.PENDING)
                )
            ) {
                monitorDirect(id)
            }
            return
        }
        if (initial.queued && initial.status == PromptStatus.QUEUED) monitorQueued(id)
    }

    /** Waits for the queue to start, or for the gateway to go idle with the row still queued. */
    private suspend fun monitorQueued(id: String) {
        while (true) {
            waitForCheck()
            val waiting = prompt(id) ?: return
            if (waiting.status != PromptStatus.QUEUED || !waiting.queued) return
            val running =
                try {
                    poll()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    markUnknown(id, UNKNOWN_POLL)
                    return
                }
            val queued = prompt(id) ?: return
            if (queued.status != PromptStatus.QUEUED || !queued.queued) return
            // A busy gateway (true) or a raced snapshot (null) keeps a legitimate queue waiting.
            if (running != false) continue

            val admission = queued.admissionId
            if (admission == null) {
                markUnknown(id, UNKNOWN_NO_RECEIPT)
                return
            }
            val text = queued.text
            val cancelled =
                try {
                    cancel(queued)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    false
                }

            // Gateway evidence or a transcript reconcile may have won while cancelling.
            val after = prompt(id) ?: return
            if (after.status != PromptStatus.QUEUED || !after.queued) return
            if (!cancelled) {
                markUnknown(id, UNKNOWN_CANCEL)
                return
            }
            if (after.admissionId != admission) continue

            var promoted = false
            update { current ->
                val row = current.prompts.firstOrNull { it.id == id }
                if (row == null ||
                    row.status != PromptStatus.QUEUED ||
                    !row.queued ||
                    row.admissionId != admission
                ) {
                    current
                } else {
                    promoted = true
                    current.copy(
                        prompts =
                            current.prompts.map { prompt ->
                                if (prompt.id == id) {
                                    prompt.copy(
                                        queued = false,
                                        promoted = true,
                                        status = PromptStatus.PENDING,
                                        admissionId = null,
                                        outcomeUnknown = false,
                                        error = null,
                                    )
                                } else {
                                    prompt
                                }
                            },
                    )
                }
            }
            if (!promoted) return

            deliverDirect(id, text)
            return
        }
    }

    /** Sends the promoted text once with `queued = false` and records the exact RPC outcome. */
    private suspend fun deliverDirect(
        id: String,
        text: String,
    ) {
        // Everything between cancellation and this point is synchronous, but a gateway event on
        // another thread may still have won; never send unless the in-place promotion is intact.
        val ready = prompt(id) ?: return
        if (ready.status != PromptStatus.PENDING || ready.queued || !ready.promoted) return

        val result =
            try {
                submit(text)
            } catch (error: CancellationException) {
                markUnknown(id, UNKNOWN_SUBMIT)
                throw error
            } catch (error: GatewayRpcException) {
                if (error.kind == RpcFailureKind.TIMEOUT || error.kind == RpcFailureKind.CONNECTION_LOST) {
                    markUnknown(id, error.message ?: UNKNOWN_SUBMIT)
                } else {
                    markRejected(id, error.code)
                }
                return
            } catch (error: Exception) {
                markUnknown(id, error.message ?: UNKNOWN_SUBMIT)
                return
            }

        var applied = false
        update { current ->
            val row = current.prompts.firstOrNull { it.id == id }
            if (row == null || row.isSettled()) {
                current
            } else {
                applied = true
                current.submitResult(id, result)
            }
        }

        val after = prompt(id) ?: return
        if (!applied) return
        when (after.status) {
            // A rejection body must expose the same safe retry row as a rejected RPC, unless the
            // dashboard reported the session-conflict code: that label drives new-chat recovery.
            PromptStatus.FAILED -> {
                update { current ->
                    val row = current.prompts.firstOrNull { it.id == id }
                    if (row?.status != PromptStatus.FAILED || row.rejectionCode == SESSION_CONFLICT_CODE) {
                        current
                    } else {
                        current.copy(
                            prompts =
                                current.prompts.map { prompt ->
                                    if (prompt.id == id) prompt.copy(error = QUEUED_START_FAILURE) else prompt
                                },
                        )
                    }
                }
                return
            }

            PromptStatus.ACCEPTED,
            PromptStatus.PENDING,
            PromptStatus.QUEUED,
            -> monitorDirect(id)

            else -> return
        }
    }

    /** Checks a promoted direct send for a start; it never promotes or submits again. */
    private suspend fun monitorDirect(id: String) {
        while (true) {
            waitForCheck()
            val waiting = prompt(id) ?: return
            if (waiting.isSettled()) return
            val running =
                try {
                    poll()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    markUnknown(id, UNKNOWN_POLL)
                    return
                }
            val current = prompt(id) ?: return
            when (current.status) {
                PromptStatus.STARTED,
                PromptStatus.FAILED,
                PromptStatus.CANCELLING,
                PromptStatus.UNKNOWN,
                -> return

                PromptStatus.QUEUED -> {
                    if (running != false) continue
                    // A late queued admission must be cancelled with a receipt before retry.
                    val admission = current.admissionId
                    if (admission == null) {
                        markUnknown(id, UNKNOWN_NO_RECEIPT)
                        return
                    }
                    val cancelled =
                        try {
                            cancel(current)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            false
                        }
                    val after = prompt(id) ?: return
                    when (after.status) {
                        PromptStatus.STARTED,
                        PromptStatus.FAILED,
                        PromptStatus.CANCELLING,
                        PromptStatus.UNKNOWN,
                        -> return

                        PromptStatus.QUEUED -> {
                            if (!cancelled) {
                                markUnknown(id, UNKNOWN_CANCEL_AGAIN)
                                return
                            }
                            if (after.admissionId != admission) continue
                            markFailed(id)
                            return
                        }

                        else -> {
                            markFailed(id)
                            return
                        }
                    }
                }

                PromptStatus.PENDING,
                PromptStatus.ACCEPTED,
                -> {
                    when (running) {
                        // Active state confirms a start; keep the row awaiting transcript evidence.
                        true -> return
                        false -> {
                            markFailed(id)
                            return
                        }
                        null -> Unit
                    }
                }

                PromptStatus.PREPARING -> return
            }
        }
    }

    private fun markUnknown(
        id: String,
        note: String,
    ) {
        updateLive(id) { it.submitUnknown(id, note) }
    }

    private fun markFailed(id: String) {
        updateLive(id) { current ->
            current.copy(
                prompts =
                    current.prompts.map { prompt ->
                        if (prompt.id == id) {
                            prompt.copy(
                                queued = false,
                                status = PromptStatus.FAILED,
                                error = QUEUED_START_FAILURE,
                                admissionId = null,
                                outcomeUnknown = false,
                                rejectionCode = null,
                                recovering = false,
                            )
                        } else {
                            prompt
                        }
                    },
            )
        }
    }

    /** Applies an explicit direct-send rejection only while the row is still live. */
    private fun markRejected(
        id: String,
        rejectionCode: Int? = null,
    ) {
        update { current ->
            val row = current.prompts.firstOrNull { it.id == id }
            if (row == null || row.isSettled()) {
                current
            } else {
                current.submitResult(id, null, QUEUED_START_FAILURE, rejectionCode)
            }
        }
    }

    /** Applies a terminal change only while the row is still live, so a started row always wins. */
    private fun updateLive(
        id: String,
        transform: (Conversation) -> Conversation,
    ) {
        update { current ->
            val row = current.prompts.firstOrNull { it.id == id }
            if (row == null || row.isSettled()) current else transform(current)
        }
    }

    private fun prompt(id: String): PendingPrompt? = conversation().prompts.firstOrNull { it.id == id }

    private fun PendingPrompt.isSettled(): Boolean =
        status == PromptStatus.STARTED ||
            status == PromptStatus.FAILED ||
            status == PromptStatus.CANCELLING ||
            status == PromptStatus.UNKNOWN
}
