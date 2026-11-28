package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.data.ProfileStorage
import dev.loosewire.hermeslight.data.ProfileStore
import dev.loosewire.hermeslight.data.SecretCipher
import dev.loosewire.hermeslight.ui.VoicePhase
import dev.loosewire.hermeslight.voice.Listener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoicePanelTest {
    @Test
    fun `panel maps capture and release then returns the final text for review`() {
        val idle = AppState(selectedSession = "a")
        val listening = idle.copy(voiceState = Listener.State.Listening)
        val transcribing = listening.copy(voiceState = Listener.State.Transcribing)
        val complete =
            transcribing.copy(
                voiceState = Listener.State.Idle,
                conversations = mapOf("a" to Conversation(composer = "First line\nSecond line")),
            )

        assertEquals(
            listOf(VoicePhase.Idle, VoicePhase.Listening, VoicePhase.Transcribing, VoicePhase.Idle),
            listOf(idle, listening, transcribing, complete).map { it.threadUi().voicePhase },
        )
        assertEquals("", listening.threadUi().composer)
        assertEquals("", transcribing.threadUi().composer)
        assertEquals("First line\nSecond line", complete.threadUi().composer)
        assertTrue(complete.threadUi().messages.isEmpty(), "Dictation must not become a submitted prompt")
    }

    @Test
    fun `cancel silence and error return to the untouched draft`() {
        for (terminal in listOf(Listener.State.Idle, Listener.State.NoSpeech, Listener.State.Error)) {
            val state =
                AppState(
                    selectedSession = "a",
                    voiceState = terminal,
                    conversations = mapOf("a" to Conversation(composer = "Unfinished\nmessage")),
                )
            assertEquals(VoicePhase.Idle, state.threadUi().voicePhase)
            assertEquals("Unfinished\nmessage", state.threadUi().composer)
            assertTrue(state.threadUi().messages.isEmpty())
        }
    }

    @Test
    fun `multiline draft survives Menu History Setup and back with independent session drafts`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        // This fixture never resumes a connection or loads a profile; only hoisted draft/navigation
        // lifecycle methods run. Do not close the process-wide shared HTTP client in a unit test.
        val controller = AppController(unusedStore(), scope)
        val stack = mutableListOf(Route.Home)
        val draft = "Keep the first line\nand the second line"
        try {
            controller.editDraft("a", draft)
            controller.editDraft("b", "Another session")
            for (destination in listOf(Route.Menu, Route.Sessions, Route.Thread, Route.Setup)) {
                destination.openFrom(
                    from = stack.last(),
                    back = { stack.removeAt(stack.lastIndex) },
                    forward = {
                        controller.cancelVoice()
                        stack.add(it)
                    },
                )
            }
            controller.pause()
            while (stack.size > 1) {
                controller.cancelVoice()
                stack.removeAt(stack.lastIndex)
            }
            controller.sessionsShown()
            controller.selectBot(null)
            val state = controller.state.value
            assertEquals(Route.Home, stack.last())
            assertEquals(draft, state.copy(selectedSession = "a").threadUi().composer)
            assertEquals("Another session", state.copy(selectedSession = "b").threadUi().composer)
            assertTrue(state.conversations.values.all { it.prompts.isEmpty() })
        } finally {
            scope.cancel()
        }
    }

    private fun unusedStore(): ProfileStore =
        ProfileStore(
            object : ProfileStorage {
                override fun read(): ByteArray? = error("Draft navigation must not read credentials")

                override fun write(bytes: ByteArray) = error("Draft navigation must not write credentials")

                override fun clear() = error("Draft navigation must not clear credentials")
            },
            object : SecretCipher {
                override fun encrypt(plain: ByteArray): ByteArray = error("No profile to encrypt")

                override fun decrypt(encrypted: ByteArray): ByteArray = error("No profile to decrypt")
            },
        )
}
