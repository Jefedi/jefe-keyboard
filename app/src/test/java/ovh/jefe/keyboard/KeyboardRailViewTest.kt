package ovh.jefe.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.test.core.app.ApplicationProvider
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

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
    fun `translation error is the only clickable feedback and retries through a native button`() {
        val rail = KeyboardRailView(context)
        var retries = 0
        rail.onTranslationRetryClick = { retries += 1 }

        listOf(TranslationFeedback.Loading, TranslationFeedback.Success).forEach { feedback ->
            rail.render(TopRailState.Translation(feedback))
            assertEquals(1, rail.touchControls().size)
        }

        rail.render(TopRailState.Translation(TranslationFeedback.Error))
        val retry = rail.retryButton()
        retry.performClick()

        assertEquals(Button::class.java, retry.javaClass)
        assertEquals("Traduction impossible · Réessayer", retry.text.toString())
        assertTrue(retry.isEnabled)
        assertEquals(1, retries)
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

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `rail text roles and pressed feedback remain legible`() {
        val rail = KeyboardRailView(context)
        val surface = ContextCompat.getColor(context, R.color.keyboard_surface)
        val pressed = ContextCompat.getColor(context, R.color.suggestion_pressed)
        val keyText = ContextCompat.getColor(context, R.color.key_text)
        val secondaryText = ContextCompat.getColor(context, R.color.secondary_text)
        val actionText = ContextCompat.getColor(context, R.color.pen_blue)

        rail.render(TopRailState.Suggestions(listOf("bonjour")))
        val suggestion = rail.suggestionViews().single()
        assertEquals(keyText, suggestion.currentTextColor)
        assertContrast(suggestion.currentTextColor, surface)
        assertContrast(suggestion.currentTextColor, pressed)
        measureAndLayout(rail, 320.dp(context), 48.dp(context))
        suggestion.isPressed = true
        suggestion.refreshDrawableState()
        val bitmap = Bitmap.createBitmap(suggestion.width, suggestion.height, Bitmap.Config.ARGB_8888)
        suggestion.draw(Canvas(bitmap))
        assertEquals(pressed, bitmap.getPixel(suggestion.width - 2, suggestion.height / 2))

        listOf(TranslationFeedback.Loading, TranslationFeedback.Success).forEach { feedback ->
            rail.render(TopRailState.Translation(feedback))
            val status = textViews(rail).single()
            assertEquals(keyText, status.currentTextColor)
            assertContrast(status.currentTextColor, surface)
        }

        rail.render(TopRailState.Translation(TranslationFeedback.Error))
        assertEquals(keyText, rail.retryButton().currentTextColor)
        assertContrast(rail.retryButton().currentTextColor, surface)
        assertContrast(rail.retryButton().currentTextColor, pressed)

        rail.render(
            TopRailState.ClipboardPrompt(
                ClipboardPromptUi("secret", "mot de passe", "Texte", "Coller mot de passe", true),
            ),
        )
        val promptTexts = textViews(rail).associateBy { it.text.toString() }
        assertEquals(actionText, requireNotNull(promptTexts["Coller"]).currentTextColor)
        assertEquals(secondaryText, requireNotNull(promptTexts["Contenu sensible ••••••"]).currentTextColor)
        assertEquals(actionText, requireNotNull(promptTexts["Texte"]).currentTextColor)
        promptTexts.values.forEach { assertContrast(it.currentTextColor, surface) }
        assertTrue(
            promptTexts.values.all {
                it.gravity and Gravity.VERTICAL_GRAVITY_MASK == Gravity.CENTER_VERTICAL
            },
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

    private fun textViews(view: View): List<TextView> = buildList {
        fun collect(candidate: View) {
            if (candidate is TextView) add(candidate)
            if (candidate is ViewGroup) {
                (0 until candidate.childCount).forEach { collect(candidate.getChildAt(it)) }
            }
        }
        collect(view)
    }

    private fun assertContrast(foreground: Int, background: Int) {
        assertTrue(ColorUtils.calculateContrast(foreground, background) >= 4.5)
    }
}
