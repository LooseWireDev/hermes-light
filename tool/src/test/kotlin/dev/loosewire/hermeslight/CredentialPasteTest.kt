package dev.loosewire.hermeslight

import dev.loosewire.hermeslight.ui.pasteCredential
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class CredentialPasteTest {
    @Test
    fun `paste replaces a field with trimmed text and preserves internal whitespace`() {
        val result = pasteCredential(" \t  user name with spaces \r\n")
        assertEquals("user name with spaces", result.value)
        assertEquals("pasted 21 characters", result.message)
    }

    @Test
    fun `absent empty or whitespace clipboard leaves the current field unchanged`() {
        for (clipboard in listOf(null, "", " \t\r\n")) {
            val result = pasteCredential(clipboard)
            assertNull(result.value)
            assertEquals("clipboard is empty", result.message)
        }
    }

    @Test
    fun `secret confirmation reports trimmed length without revealing its contents`() {
        val secret = "unique-password-42"
        val result = pasteCredential(" \n$secret\t")
        assertEquals(secret, result.value)
        assertEquals("pasted 18 characters", result.message)
        assertFalse(result.message.contains(secret))
    }

    @Test
    fun `long masked token reports full length rather than the capped bullet count`() {
        val token = "secret-token-".repeat(10)
        val result = pasteCredential("\n$token\n")
        assertEquals(token, result.value)
        assertEquals("pasted 130 characters", result.message)
        assertFalse(result.message.contains(token))
    }
}
