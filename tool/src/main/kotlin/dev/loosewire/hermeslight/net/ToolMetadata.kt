/*
 * Copyright 2026 LooseWireDev
 *
 * Tool envelope and result handling are derived from Hermes Mobile
 * (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal data class ToolMetadata(
    val name: String?,
    val outcome: String,
)

/** REST result rows settle a tool, not the containing turn. Inspect raw content, not display text. */
internal fun toolMetadata(
    content: JsonElement?,
    name: String?,
): ToolMetadata {
    val envelope = content.toolObjectOrNull()
    val result = if (envelope?.containsKey("result") == true) envelope["result"] else content
    val record = result.toolObjectOrNull()
    val resolvedName =
        name?.takeIf { it.isNotBlank() }
            ?: envelope?.stringOrNull("name")?.takeIf { it.isNotBlank() }
            ?: envelope?.stringOrNull("tool_name")?.takeIf { it.isNotBlank() }
    val outcome =
        when {
            result == null || result is JsonNull -> "unknown"
            record?.booleanOrNull("success") == true || record?.booleanOrNull("ok") == true -> "ok"
            envelope?.booleanOrNull("is_error") == true || envelope?.booleanOrNull("isError") == true -> "fail"
            record?.booleanOrNull("is_error") == true || record?.booleanOrNull("isError") == true -> "fail"
            record?.booleanOrNull("success") == false || record?.booleanOrNull("ok") == false -> "fail"
            record?.get("error").hasError() -> "fail"
            record?.stringOrNull("status")?.lowercase() in failedStatuses -> "fail"
            record?.intOrNull("exit_code")?.let { it != 0 } == true -> "fail"
            result.asStringOrNull()?.trimStart()?.startsWith("Error:", ignoreCase = true) == true -> "fail"
            else -> "ok"
        }
    return ToolMetadata(resolvedName, outcome)
}

internal fun toolCallNames(calls: JsonElement?): Map<String, String> =
    buildMap {
        (calls as? JsonArray)?.forEach { element ->
            val call = element as? JsonObject ?: return@forEach
            val id = call.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return@forEach
            val name = call.objectOrNull("function")?.stringOrNull("name") ?: call.stringOrNull("name")
            if (!name.isNullOrBlank()) put(id, name)
        }
    }

private val failedStatuses = setOf("error", "failed", "failure", "fail")

private fun JsonElement?.toolObjectOrNull(): JsonObject? {
    if (this is JsonObject) return this
    val text = asStringOrNull()?.trimStart()?.takeIf { it.startsWith("{") } ?: return null
    return runCatching { hermesJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
}

private fun JsonElement?.hasError(): Boolean =
    when (this) {
        null, JsonNull -> false
        else -> asLenientBooleanOrNull() ?: (asStringOrNull()?.isNotBlank() ?: true)
    }
