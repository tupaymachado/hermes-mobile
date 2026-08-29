package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The `@token` the caret currently sits inside.
 *
 * [start] is the index of the `@` itself and [end] the caret, so the pair is
 * exactly the span an accepted suggestion replaces — the rest of the message,
 * on both sides, is never touched.
 */
data class MentionQuery(
    val start: Int,
    val end: Int,
    val prefix: String,
)

/**
 * Pure decision logic for @mention autocomplete in the composer (§P4).
 *
 * Split from the Compose layer for the same reason [ChatInputPolicy] is: the
 * interesting part is *where a mention begins and ends*, and that is a string
 * problem with a lot of edges — an email address, a mention being edited in the
 * middle of a sentence, a caret that moved away — none of which need an
 * emulator to pin down.
 *
 * Scope of the first cut, per §P4: names come from the roster, the token
 * inserted is a plain `@name`, and there is no cross-device resolution. An `@`
 * the roster does not know stays untouched text.
 */
object MentionPolicy {
    /** Rows the dropdown offers at once. */
    const val MAX_SUGGESTIONS = 6

    /**
     * Longest prefix still treated as an in-progress mention. Past this the
     * user is clearly typing prose, not a name.
     */
    private const val MAX_PREFIX = 64

    /**
     * The mention being typed at the caret, or null when there is none.
     *
     * Rules, each one a case that would otherwise misfire:
     *  - the selection must be collapsed — with a range selected, replacing
     *    text under it is not what the user asked for;
     *  - the `@` has to OPEN a word (start of text, or whitespace before it),
     *    so `tupay@gmail.com` never opens a bot list;
     *  - no whitespace between the `@` and the caret, so the token ends where
     *    the name ends;
     *  - an empty prefix (a bare `@`) is a valid query — it offers the whole
     *    roster, which is the natural affordance.
     */
    fun queryAt(value: TextFieldValue): MentionQuery? {
        val selection = value.selection
        if (!selection.collapsed) return null

        val text = value.text
        val caret = selection.start
        if (caret < 1 || caret > text.length) return null

        var index = caret - 1
        while (index >= 0) {
            val char = text[index]
            if (char == '@') break
            // Whitespace before finding an '@' means the caret is in ordinary
            // prose. Bail rather than scanning the whole message.
            if (char.isWhitespace()) return null
            index--
        }
        if (index < 0 || text[index] != '@') return null
        if (index > 0 && !text[index - 1].isWhitespace()) return null

        val prefix = text.substring(index + 1, caret)
        if (prefix.length > MAX_PREFIX) return null
        return MentionQuery(start = index, end = caret, prefix = prefix)
    }

    /**
     * Bots worth offering for [prefix], best match first.
     *
     * Ranks names that START with what was typed above names that merely
     * contain it: typing `co` should put `coder` before `telegram-connector`.
     * Matching is case-insensitive because profile names are free-form on the
     * gateway, and the order is otherwise the roster's own.
     */
    fun suggestions(
        prefix: String,
        bots: List<String>,
        limit: Int = MAX_SUGGESTIONS,
    ): List<String> {
        val needle = prefix.trim()
        val candidates =
            bots
                .asSequence()
                .filter { it.isNotBlank() }
                .distinct()
        if (needle.isEmpty()) return candidates.take(limit).toList()
        return candidates
            .filter { it.contains(needle, ignoreCase = true) }
            .sortedByDescending { it.startsWith(needle, ignoreCase = true) }
            .take(limit)
            .toList()
    }

    /**
     * Accepts [name] for [query], returning the whole field value to set.
     *
     * The token is replaced in place and a single trailing space is added so
     * the user can keep typing — and so the NEXT keystroke is not read as part
     * of the mention. The caret lands after that space; leaving it anywhere
     * else re-opens the dropdown on the name just accepted.
     */
    fun apply(
        value: TextFieldValue,
        query: MentionQuery,
        name: String,
    ): TextFieldValue {
        val text = value.text
        // Defensive: a stale query (the text changed under it) must not throw
        // or splice at the wrong offset — dropping the insert is recoverable,
        // a crash is not.
        if (query.start < 0 || query.end > text.length || query.start > query.end) return value

        val token = "@${name.trim()} "
        val updated = text.substring(0, query.start) + token + text.substring(query.end)
        val caret = query.start + token.length
        return TextFieldValue(text = updated, selection = TextRange(caret))
    }
}
