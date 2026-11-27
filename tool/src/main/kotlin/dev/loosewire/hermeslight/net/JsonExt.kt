/*
 * Copyright 2026 LooseWireDev
 *
 * Part of Hermes Light. Dashboard shapes are derived from Hermes Mobile
 * (https://github.com/Hy4ri/hermes-mobile), licensed under the Apache
 * License, Version 2.0. See NOTICE.
 */
package dev.loosewire.hermeslight.net

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.Instant

/** String value only when the JSON node truly is a string. */
internal fun JsonElement?.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Scalar content regardless of whether the gateway encoded it as a string or a number. */
internal fun JsonElement?.scalarTextOrNull(): String? = (this as? JsonPrimitive)?.content

/**
 * Dashboard booleans arrive as JSON booleans, SQLite 0/1 numbers, or strings. Null means absent.
 */
internal fun JsonElement?.asLenientBooleanOrNull(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    primitive.intOrNull?.let { return it != 0 }
    return when (primitive.content.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}

internal fun JsonElement?.asIntOrNull(): Int? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.longOrNull?.toInt() ?: primitive.content.toIntOrNull()
}

internal fun JsonElement?.asLongOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.longOrNull ?: primitive.doubleOrNull?.toLong() ?: primitive.content.toLongOrNull()
}

internal fun JsonElement?.asDoubleOrNull(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.longOrNull?.toDouble() ?: primitive.content.toDoubleOrNull()
}

/**
 * Epoch milliseconds from either seconds, milliseconds, a numeric string, or an ISO-8601 string.
 * Values below 10^10 are treated as seconds, matching the dashboard's mixed timestamp columns.
 */
internal fun JsonElement?.asEpochMillisOrNull(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    val numeric = primitive.longOrNull ?: primitive.doubleOrNull?.toLong()
    if (numeric != null) return if (numeric < 10_000_000_000L) numeric * 1000L else numeric
    val text = primitive.takeIf { it.isString }?.content ?: return null
    text.toLongOrNull()?.let { return if (it < 10_000_000_000L) it * 1000L else it }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

internal fun JsonObject.stringOrNull(key: String): String? = this[key].asStringOrNull()

internal fun JsonObject.booleanOrNull(key: String): Boolean? = this[key].asLenientBooleanOrNull()

internal fun JsonObject.intOrNull(key: String): Int? = this[key].asIntOrNull()

internal fun JsonObject.longOrNull(key: String): Long? = this[key].asLongOrNull()

internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject
