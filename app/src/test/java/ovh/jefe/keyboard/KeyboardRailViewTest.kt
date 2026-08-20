package ovh.jefe.keyboard

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KeyboardRailViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `empty rail contains clipboard tab and no suggestion`() {
        val rail = KeyboardRailView(context)
        rail.render(TopRailState.Empty)

        assertEquals(1, rail.touchControls().size)
        assertEquals("Presse-papiers", rail.touchControls().single().contentDescription)
        assertTrue(rail.suggestionViews().isEmpty())
    }

    @Test
    fun `one two and three suggestions create the exact native views`() {
        val rail = KeyboardRailView(context)

        (1..3).forEach { count ->
            val values = listOf("un", "deux", "trois").take(count)
            rail.render(TopRailState.Suggestions(values))

            assertEquals(values, rail.suggestionViews().map { it.text.toString() })
        }
    }

    @Test
    fun `all controls keep a forty four dp target`() {
        val rail = KeyboardRailView(context)
        rail.render(TopRailState.Suggestions(listOf("un", "deux", "trois")))
        measureAndLayout(rail, 1080, 48.dp(context))
        val minimum = 44.dp(context)

        assertTrue(rail.touchControls().all { it.height >= minimum })
    }

    @Test
    fun `narrow prompt ellipsizes only preview and keeps action and type`() {
        val rail = KeyboardRailView(context)
        rail.render(
            TopRailState.ClipboardPrompt(
                ClipboardPromptUi("1", "rapport annuel très long", "PDF", "Coller le PDF", false),
            ),
        )
        measureAndLayout(rail, 240.dp(context), 48.dp(context))

        assertTrue(rail.visibleTexts().contains("Coller"))
        assertTrue(rail.visibleTexts().contains("PDF"))
    }

    @Test
    fun `native rail controls route every callback`() {
        val rail = KeyboardRailView(context)
        val events = mutableListOf<String>()
        rail.onClipboardTabClick = { events += "clipboard" }
        rail.onSuggestionClick = { events += "suggest:$it" }
        rail.onTranslationRetryClick = { events += "retry" }
        rail.onClipboardPromptClick = { events += "prompt:$it" }
        rail.onClipboardPromptDismiss = { events += "dismiss" }

        rail.render(TopRailState.Suggestions(listOf("bonjour")))
        rail.touchControls().first { it.contentDescription == "Presse-papiers" }.performClick()
        rail.suggestionViews().single().performClick()
        rail.render(TopRailState.Translation(TranslationFeedback.Error))
        rail.retryButton().performClick()
        rail.render(
            TopRailState.ClipboardPrompt(
                ClipboardPromptUi("entry-7", "rapport", "PDF", "Coller le PDF", false),
            ),
        )
        rail.touchControls().first { it.contentDescription == "Coller le PDF" }.performClick()
        rail.touchControls().first {
            it.contentDescription == "Masquer la proposition de collage"
        }.performClick()

        assertEquals(
            listOf("clipboard", "suggest:bonjour", "retry", "prompt:entry-7", "dismiss"),
            events,
        )
    }

    @Test
    fun `sensitive prompt masks preview and accessibility description`() {
        val rail = KeyboardRailView(context)
        rail.render(
            TopRailState.ClipboardPrompt(
                ClipboardPromptUi("secret", "mot de passe", "Texte", "Coller mot de passe", true),
            ),
        )

        assertTrue(rail.visibleTexts().contains("Contenu sensible ••••••"))
        assertTrue(rail.visibleTexts().none { it.contains("mot de passe") })
        assertTrue(
            rail.touchControls().any { it.contentDescription == "Coller le contenu sensible" },
        )
    }

    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).roundToInt()

    private fun measureAndLayout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }
}
