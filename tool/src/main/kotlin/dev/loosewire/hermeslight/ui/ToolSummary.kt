package dev.loosewire.hermeslight.ui

internal fun ThreadMessageUi.toolSummary(): String {
    title?.takeIf { it.isNotBlank() }?.let { return it }
    val name = toolName?.takeIf { it.isNotBlank() } ?: "tool"
    val outcome = toolOutcome?.takeIf { it.isNotBlank() } ?: "unknown"
    return "· $name — $outcome"
}
