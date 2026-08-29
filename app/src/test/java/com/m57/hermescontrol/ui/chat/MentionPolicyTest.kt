package com.m57.hermescontrol.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for @mention autocomplete's pure core (§P4).
 *
 * The whole risk of the feature is *where a mention starts and stops*, so this
 * is where it is pinned down: an email address must not open a bot list, a
 * mention edited mid-sentence must replace only itself, and a caret that moved
 * away must close the dropdown.
 */
class MentionPolicyTest {
    // ── queryAt ───────────────────────────────────────────────────────────

    @Test
    fun `a bare at sign opens the roster`() {
        val query = MentionPolicy.queryAt(field("@"))!!

        assertEquals(0, query.start)
        assertEquals(1, query.end)
        assertEquals("", query.prefix)
    }

    @Test
    fun `a partial name is the prefix`() {
        val query = MentionPolicy.queryAt(field("ask @cod"))!!

        assertEquals(4, query.start)
        assertEquals(8, query.end)
        assertEquals("cod", query.prefix)
    }

    @Test
    fun `an email address is not a mention`() {
        // The '@' has to OPEN a word. This is the case that would otherwise
        // pop a bot list every time the user types an address.
        assertNull(MentionPolicy.queryAt(field("mail tupay@gmail")))
    }

    @Test
    fun `whitespace closes the token`() {
        // Caret sits after "coder ", past the mention: nothing to complete.
        assertNull(MentionPolicy.queryAt(field("ask @coder ")))
    }

    @Test
    fun `a caret before the at sign is not inside a mention`() {
        assertNull(MentionPolicy.queryAt(TextFieldValue("ask @coder", TextRange(2))))
    }

    @Test
    fun `a selected range never triggers a replacement`() {
        assertNull(MentionPolicy.queryAt(TextFieldValue("ask @coder", TextRange(4, 10))))
    }

    @Test
    fun `empty input has no mention`() {
        assertNull(MentionPolicy.queryAt(field("")))
        assertNull(MentionPolicy.queryAt(field("plain text")))
    }

    @Test
    fun `a mention being edited mid-sentence is found`() {
        // Caret inside "@cod", with text after it.
        val value = TextFieldValue("tell @cod about the deploy", TextRange(9))

        val query = MentionPolicy.queryAt(value)!!

        assertEquals(5, query.start)
        assertEquals(9, query.end)
        assertEquals("cod", query.prefix)
    }

    @Test
    fun `prose is not scanned back forever`() {
        val value = field("@coder said the build is broken and then")

        // The caret is deep in prose; the nearest whitespace ends the search.
        assertNull(MentionPolicy.queryAt(value))
    }

    // ── suggestions ───────────────────────────────────────────────────────

    private val roster = listOf("default", "coder", "secretaria", "telegram-connector")

    @Test
    fun `an empty prefix offers the roster`() {
        assertEquals(roster, MentionPolicy.suggestions("", roster))
    }

    @Test
    fun `prefix matches outrank substring matches`() {
        // "coder" starts with it; "telegram-connector" only contains it.
        val hits = MentionPolicy.suggestions("co", roster)

        assertEquals(listOf("coder", "telegram-connector"), hits)
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(listOf("coder"), MentionPolicy.suggestions("CODE", roster))
    }

    @Test
    fun `an unknown name offers nothing`() {
        // Nothing to offer ∴ the composer leaves the text alone, which is what
        // §P4 asks for: an '@' the roster does not know passes through intact.
        assertEquals(emptyList<String>(), MentionPolicy.suggestions("zzz", roster))
    }

    @Test
    fun `the list is capped and deduped`() {
        val many = (1..20).map { "bot$it" } + "bot1"

        val hits = MentionPolicy.suggestions("bot", many)

        assertEquals(MentionPolicy.MAX_SUGGESTIONS, hits.size)
        assertEquals(hits.size, hits.distinct().size)
    }

    @Test
    fun `blank roster entries are never offered`() {
        assertEquals(listOf("coder"), MentionPolicy.suggestions("", listOf("", "  ", "coder")))
    }

    // ── apply ─────────────────────────────────────────────────────────────

    @Test
    fun `accepting a suggestion completes the token and trails a space`() {
        val value = field("ask @cod")
        val query = MentionPolicy.queryAt(value)!!

        val result = MentionPolicy.apply(value, query, "coder")

        assertEquals("ask @coder ", result.text)
        // Caret after the space: leaving it on the name re-opens the dropdown
        // over the name just accepted.
        assertEquals(11, result.selection.start)
        assertEquals(true, result.selection.collapsed)
    }

    @Test
    fun `accepting mid-sentence replaces only the token`() {
        val value = TextFieldValue("tell @cod about the deploy", TextRange(9))
        val query = MentionPolicy.queryAt(value)!!

        val result = MentionPolicy.apply(value, query, "coder")

        assertEquals("tell @coder  about the deploy", result.text)
        assertEquals(12, result.selection.start)
    }

    @Test
    fun `a stale query is dropped rather than splicing at the wrong offset`() {
        val stale = MentionQuery(start = 40, end = 44, prefix = "cod")

        val result = MentionPolicy.apply(field("short"), stale, "coder")

        assertEquals("short", result.text)
    }

    private fun field(text: String) = TextFieldValue(text, TextRange(text.length))
}
