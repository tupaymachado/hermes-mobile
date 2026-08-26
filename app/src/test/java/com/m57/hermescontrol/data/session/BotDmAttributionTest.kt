package com.m57.hermescontrol.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BotDmAttributionTest {
    @Test
    fun `parses the gateway prefix with display name and handle`() {
        val content = "Message from 🤖 Research Buddy (@research): what is the deploy status?"
        val a = BotDmAttribution.parse(content)!!
        assertEquals("Research Buddy", a.displayName)
        assertEquals("research", a.handle)
        assertEquals("what is the deploy status?", BotDmAttribution.stripPrefix(content))
    }

    @Test
    fun `parses multi-line body keeping rest intact`() {
        val content =
            "Message from 🤖 Hermes (@hermes): two things:\n" +
                "1. disk is at 91%\n" +
                "2. backup ran fine"
        val a = BotDmAttribution.parse(content)!!
        assertEquals("Hermes", a.displayName)
        assertEquals("hermes", a.handle)
        assertEquals(
            "two things:\n1. disk is at 91%\n2. backup ran fine",
            BotDmAttribution.stripPrefix(content),
        )
    }

    @Test
    fun `parses plain-text fallback without the emoji`() {
        val content = "Message from Dixie (@dixie): all good here"
        val a = BotDmAttribution.parse(content)!!
        assertEquals("Dixie", a.displayName)
        assertEquals("dixie", a.handle)
    }

    @Test
    fun `null for ordinary user or assistant messages`() {
        assertNull(BotDmAttribution.parse("Hello, can you check the logs?"))
        assertNull(BotDmAttribution.parse("Sure — the deploy status is green."))
    }

    @Test
    fun `null when the prefix is quoted mid-message not delivered`() {
        // A bot EXPLAINING the protocol quotes it mid-text; only a
        // line-0 match is a real delivery.
        assertNull(
            BotDmAttribution.parse(
                "The DM protocol says: Message from 🤖 X (@x): is how bots talk.",
            ),
        )
    }

    @Test
    fun `null on blank display or handle`() {
        assertNull(BotDmAttribution.parse("Message from 🤖  (@x): hi"))
        assertNull(BotDmAttribution.parse("Message from 🤖 Name (@): hi"))
    }

    @Test
    fun `stripPrefix keeps the body that follows the colon on the same line`() {
        // The wire format puts the body on the SAME line as the prefix
        // (upstream AGENT_MESSAGE_RE captures it as the trailing group), so a
        // strip that dropped the whole first line would delete the message.
        val content = "Message from 🤖 A (@a): ok"
        assertEquals("ok", BotDmAttribution.stripPrefix(content))
    }

    @Test
    fun `parses the handle-less form and falls back to the display name`() {
        // `Message from 🤖 worker: …` is what older senders emit; the Desktop
        // resolves the handle to the sender name in that case.
        val a = BotDmAttribution.parse("Message from 🤖 worker: hello, remember this")!!
        assertEquals("worker", a.displayName)
        assertEquals("worker", a.handle)
        assertEquals(
            "hello, remember this",
            BotDmAttribution.stripPrefix("Message from 🤖 worker: hello, remember this"),
        )
    }

    @Test
    fun `parses a capitalised handle`() {
        // Profile names are free-form on the gateway; rejecting @Research
        // would leave a real delivery rendering as raw prefix text.
        val a = BotDmAttribution.parse("Message from 🤖 Research (@Research): ping")!!
        assertEquals("Research", a.handle)
    }

    @Test
    fun `stripPrefix leaves a non-delivery untouched`() {
        val plain = "Sure — the deploy status is green."
        assertEquals(plain, BotDmAttribution.stripPrefix(plain))
    }
}
