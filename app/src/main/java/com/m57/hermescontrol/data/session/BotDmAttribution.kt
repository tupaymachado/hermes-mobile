package com.m57.hermescontrol.data.session

/**
 * Bot Mode PM1: attribution for bot-to-bot DMs.
 *
 * When bot A messages bot B, the gateway's messaging protocol makes A deliver
 * the text through B's canonical Bot Chat with a fixed prefix:
 *
 * ```
 * Message from 🤖 Research Buddy (@research): what is the deploy status?
 * ```
 *
 * The receiving bot sees that line as the first line of its user turn; the
 * reply it produces is an ordinary assistant message in the same thread. The
 * mobile chat therefore needs to (a) DETECT such messages and (b) render the
 * sender as an author badge instead of raw prefix text — same information the
 * Desktop shows, adapted to the phone's bubble layout.
 *
 * The grammar mirrors the upstream Desktop's `AGENT_MESSAGE_RE`
 * (`apps/desktop/src/components/assistant-ui/thread/user-message.tsx`), which
 * is the source of truth for the wire format: the 🤖 glyph and the `(@handle)`
 * group are BOTH optional (`hermes peer dm` emits the handle form, older
 * senders emit the bare one), and the body starts right after the colon on the
 * SAME line — it is not a separate line.
 */
data class BotDmAttribution(
    /** Display name as written by the sending gateway (e.g. "Research Buddy"). */
    val displayName: String,
    /**
     * The sender's profile handle (e.g. "research") from the `(@handle)` part.
     * Falls back to [displayName] when the sender omitted the group, which is
     * what the Desktop does — a delivery always has an attributable author.
     */
    val handle: String,
) {
    companion object {
        /**
         * `Message from [🤖 ]<display>[ (@<handle>)]: <body>`, anchored at
         * position 0 — a message that merely QUOTES the prefix mid-text (a bot
         * explaining the protocol) is not a delivery.
         *
         * The display group excludes `:`, `(` and newlines so the lazy match
         * cannot swallow the separator it is supposed to stop before. The
         * handle class is deliberately one notch more permissive than the
         * Desktop's (which is lowercase-only): profile names are free-form on
         * the gateway, and rejecting `@Research` would drop a real delivery
         * back to raw prefix text.
         */
        private val DELIVERY =
            Regex(
                """^Message from (?:🤖\s*)?([^:\n(]{1,64}?)(?:\s*\(@([A-Za-z0-9][A-Za-z0-9_-]{0,63})\))?:[ \t]*""",
            )

        /** Returns the sender, or null when [content] is not a bot-to-bot delivery. */
        fun parse(content: String): BotDmAttribution? = split(content)?.first

        /**
         * Strips the attribution prefix, leaving only the delivered body.
         * A non-delivery is returned trimmed but otherwise untouched.
         */
        fun stripPrefix(content: String): String = split(content)?.second ?: content.trim()

        /** Single match shared by [parse] and [stripPrefix] so they cannot disagree. */
        private fun split(content: String): Pair<BotDmAttribution, String>? {
            // Cheap reject first: this runs per message on every chat
            // recomposition, and the overwhelming majority are not deliveries.
            if (!content.startsWith("Message from ")) return null
            val match = DELIVERY.find(content) ?: return null
            val display = match.groupValues[1].trim()
            if (display.isBlank()) return null
            val handle = match.groupValues[2].trim().ifEmpty { display }
            return BotDmAttribution(displayName = display, handle = handle) to
                content.substring(match.value.length).trim()
        }
    }
}
