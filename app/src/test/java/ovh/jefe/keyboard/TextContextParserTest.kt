package ovh.jefe.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class TextContextParserTest {
    @Test
    fun `parses active and previous French words`() {
        assertEquals(TextContext("bon", "je"), TextContextParser.parse("je bon"))
    }

    @Test
    fun `ignores punctuation around the completed word`() {
        assertEquals(TextContext("", "bonjour"), TextContextParser.parse("bonjour, "))
    }

    @Test
    fun `keeps straight apostrophes and accented letters inside a token`() {
        assertEquals(TextContext("l'été", null), TextContextParser.parse("l'été"))
    }

    @Test
    fun `keeps typographic apostrophes inside a token`() {
        assertEquals(TextContext("aujourd’hui", null), TextContextParser.parse("aujourd’hui"))
    }
}
