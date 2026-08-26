package com.m57.hermescontrol.ui.bots

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Flattens a message `content` payload to plain text. The gateway stores it as
 * a bare string on some turns and as structured content blocks on others
 * (`[{type:"text", text:"…"}]`), so both shapes have to collapse to something
 * the Bot Mode screens can read.
 *
 * Line breaks are PRESERVED — unlike [oneLine], which is the display-side
 * squash. Bot-to-bot attribution is a line-anchored grammar
 * (`BotDmAttribution`), so parsing has to happen before any whitespace
 * flattening or a multi-line delivery stops looking like one.
 */
internal fun JsonElement.flatText(): String? =
    when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> mapNotNull { it.flatText() }.joinToString("\n").takeIf { it.isNotBlank() }
        is JsonObject ->
            listOf("text", "content", "message", "body")
                .firstNotNullOfOrNull { key -> this[key]?.flatText() }
    }

/** Squashes whitespace runs so a multi-line body fits one list row. */
internal fun String.oneLine(): String = replace(WHITESPACE_RUN, " ").trim()

private val WHITESPACE_RUN = Regex("\\s+")
