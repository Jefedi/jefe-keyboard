package ovh.jefe.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TopRailStateTest {
    private val prompt = ClipboardPromptUi(
        entryId = "entry-1",
        preview = "bonjour…",
        typeLabel = "Texte",
        contentDescription = "Coller le texte bonjour",
        isSensitive = false,
    )

    @Test
    fun `translation wins over clipboard and suggestions`() {
        assertEquals(
            TopRailState.Translation(TranslationFeedback.Loading),
            TopRailResolver.resolve(
                TopRailInputs(
                    translation = TranslationFeedback.Loading,
                    clipboardPrompt = prompt,
                    suggestions = listOf("bonjour"),
                ),
            ),
        )
    }

    @Test
    fun `clipboard wins over suggestions`() {
        assertEquals(
            TopRailState.ClipboardPrompt(prompt),
            TopRailResolver.resolve(
                TopRailInputs(clipboardPrompt = prompt, suggestions = listOf("bonjour")),
            ),
        )
    }

    @Test
    fun `suggestions remove blanks preserve order and cap at three`() {
        assertEquals(
            TopRailState.Suggestions(listOf("un", "deux", "trois")),
            TopRailResolver.resolve(
                TopRailInputs(suggestions = listOf("un", " ", "deux", "trois", "quatre")),
            ),
        )
    }

    @Test
    fun `idle inputs are empty`() {
        assertTrue(TopRailResolver.resolve(TopRailInputs()) is TopRailState.Empty)
    }

    @Test
    fun `payload-bearing rail models redact their string representations`() {
        val secret = "secret-ne-jamais-journaliser"
        val sensitivePrompt = ClipboardPromptUi(
            entryId = "entry-1",
            preview = secret,
            typeLabel = secret,
            contentDescription = secret,
            isSensitive = true,
        )

        assertFalse(sensitivePrompt.toString().contains(secret))
        assertFalse(TopRailInputs(clipboardPrompt = sensitivePrompt, suggestions = listOf(secret)).toString().contains(secret))
        assertFalse(TopRailState.Suggestions(listOf(secret)).toString().contains(secret))
    }
}
