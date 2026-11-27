package dev.loosewire.hermeslight.ui

internal fun ThreadMessageUi.toolSummary(): String {
    val name = toolName?.takeIf { it.isNotBlank() } ?: "tool"
    val outcome = toolOutcome?.takeIf { it.isNotBlank() } ?: "unknown"
    return "· $name — $outcome"
}
