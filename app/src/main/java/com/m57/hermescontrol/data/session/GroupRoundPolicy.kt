package com.m57.hermescontrol.data.session

/**
 * Who speaks next in a group room, and when the room stops (§P3).
 *
 * Pure and free of networking: the orchestration is a scheduling rule over
 * text, and the part that can actually go wrong — a room that never stops, two
 * bots answering each other forever, a mention that scopes to nobody — is
 * decided here where it can be tested without a gateway.
 *
 * The caller drives the loop; this only answers "given what has been said, who
 * is next and may we keep going".
 */
object GroupRoundPolicy {
    /** Serial rounds one human message may trigger. */
    const val MAX_ROUNDS = 3

    /**
     * Bot messages one human message may produce, across all rounds. Reached
     * before [MAX_ROUNDS] in a busy room — six members answering twice is
     * already past it — and that is the point: the cap is on volume, not
     * depth.
     */
    const val MAX_MESSAGES_PER_SEND = 10

    /** The handle that means the human, not a bot. */
    const val USER_HANDLE = "user"

    /**
     * Members named by `@handle` in [text], in the order they appear, deduped.
     *
     * Matched against the room's own [members] rather than a handle charset:
     * that is what makes an unknown `@` — an email address, a bot that left,
     * a literal `@` in prose — pass through as text instead of scoping the
     * round to nobody. Case-insensitive, since gateway profile names are
     * free-form.
     */
    fun mentionedMembers(
        text: String,
        members: List<String>,
    ): List<String> {
        if (text.isEmpty() || members.isEmpty()) return emptyList()
        val handles = handlesIn(text)
        if (handles.isEmpty()) return emptyList()
        // Longest first: with members `code` and `coder`, the token "coder"
        // must not resolve to `code` on a prefix match.
        val byLength = members.filter { it.isNotBlank() }.sortedByDescending { it.length }
        return handles
            .mapNotNull { handle -> byLength.firstOrNull { it.equals(handle, ignoreCase = true) } }
            .distinctBy { it.lowercase() }
    }

    /**
     * Whether [text] escalates to the human — the `@user` that earns a room its
     * "needs you" badge.
     */
    fun escalatesToUser(text: String): Boolean = handlesIn(text).any { it.equals(USER_HANDLE, ignoreCase = true) }

    /**
     * Who answers [text] in a room of [members].
     *
     * Mentions scope the round: name somebody and only they answer. Name
     * nobody and the whole room does — it is a shared room, and a message to
     * everyone is the default reading of one addressed to no one.
     *
     * [speaker] never answers itself, which is what stops the trivial loop of
     * a bot re-triggering on its own reply.
     */
    fun respondersFor(
        text: String,
        members: List<String>,
        speaker: String? = null,
    ): List<String> {
        val others = members.filter { it.isNotBlank() && !it.equals(speaker, ignoreCase = true) }
        val mentioned = mentionedMembers(text, others)
        return mentioned.ifEmpty {
            // A message that only escalates to the human is not a prompt for
            // the room — answering it would talk over the person it asked for.
            if (escalatesToUser(text) && mentionedMembers(text, members).isEmpty()) emptyList() else others
        }
    }

    /**
     * Whether another round may run.
     *
     * [round] is how many have already completed, [messagesSoFar] how many bot
     * messages this send has produced. Both caps are hard: a room that hits
     * either stops, and the UI says so rather than silently truncating.
     */
    fun canContinue(
        round: Int,
        messagesSoFar: Int,
    ): Boolean = round < MAX_ROUNDS && messagesSoFar < MAX_MESSAGES_PER_SEND

    /**
     * How many of [responders] may actually speak, given the budget already
     * spent. Trims the tail rather than dropping the round, so the room always
     * makes progress up to the cap.
     */
    fun admit(
        responders: List<String>,
        messagesSoFar: Int,
    ): List<String> {
        val room = MAX_MESSAGES_PER_SEND - messagesSoFar
        return if (room <= 0) emptyList() else responders.take(room)
    }

    /**
     * `@handle` tokens in [text], in order and as written — callers compare
     * case-insensitively rather than folding case here, so a handle can still
     * be shown back the way the sender typed it.
     *
     * The `@` must OPEN a word — the same rule the composer's autocomplete
     * uses, and the reason `tupay@gmail.com` is not a mention of `gmail`.
     * Trailing punctuation is dropped so "ask @coder, then @writer" resolves
     * both.
     */
    private fun handlesIn(text: String): List<String> =
        HANDLE
            .findAll(text)
            .map { it.groupValues[1].trimEnd(*TRAILING_PUNCTUATION) }
            .filter { it.isNotEmpty() }
            .toList()

    /** `@` at the start of the string or after whitespace, then a run of non-space. */
    private val HANDLE = Regex("""(?<=^|\s)@(\S+)""")

    private val TRAILING_PUNCTUATION = charArrayOf(',', '.', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
}
