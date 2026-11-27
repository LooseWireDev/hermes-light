package dev.loosewire.hermeslight.ui

internal data class CredentialPasteResult(
    /** Null leaves the current field unchanged. */
    val value: String?,
    val message: String,
)

internal fun pasteCredential(clipboardText: String?): CredentialPasteResult {
    val value = clipboardText?.trim()?.takeIf { it.isNotEmpty() }
    return CredentialPasteResult(
        value = value,
        message = if (value == null) "clipboard is empty" else "pasted ${value.length} characters",
    )
}
