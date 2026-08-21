package ovh.jefe.keyboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class KeyboardRootViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `rail precedes keyboard in the native hierarchy`() {
        val root = KeyboardRootView(context)

        assertSame(root.railView, root.getChildAt(0))
        assertSame(root.keyboardView, root.getChildAt(1))
    }

    @Test
    fun `clipboard mode replaces keyboard and back restores it`() {
        val root = KeyboardRootView(context)
        measureAndLayout(root, 1080)
        val keyboardHeight = root.height

        root.showClipboard()
        measureAndLayout(root, 1080)

        assertEquals(KeyboardRootMode.CLIPBOARD, root.mode)
        assertEquals(keyboardHeight, root.height)
        assertEquals(View.VISIBLE, root.clipboardPanelView.visibility)
        assertEquals(View.GONE, root.keyboardView.visibility)

        root.showKeyboard()

        assertEquals(KeyboardRootMode.KEYBOARD, root.mode)
        assertEquals(View.GONE, root.clipboardPanelView.visibility)
        assertEquals(View.VISIBLE, root.keyboardView.visibility)
    }

    @Test
    fun `rail stays forty eight dp and root height stays constant in every state`() {
        val root = KeyboardRootView(context)
        val states = listOf(
            TopRailState.Empty,
            TopRailState.Suggestions(listOf("un", "deux", "trois")),
            TopRailState.ClipboardPrompt(
                ClipboardPromptUi("1", "rapport", "PDF", "Coller le PDF", false),
            ),
            TopRailState.Translation(TranslationFeedback.Loading),
        )

        states.forEach { state ->
            root.renderRail(state)
            measureAndLayout(root, 1080)

            assertEquals(48.dp(context), root.railView.height)
            assertEquals(330.dp(context), root.height)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "notnight")
    fun `render light keyboard rail states`() {
        renderStates(
            listOf(
                TopRailState.Empty to "keyboard-empty-light.png",
                TopRailState.Suggestions(listOf("bonjour", "clavier", "privé")) to
                    "keyboard-suggestions-light.png",
                TopRailState.Translation(TranslationFeedback.Loading) to
                    "keyboard-translation-light.png",
            ),
        )
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "night")
    fun `render dark keyboard rail states`() {
        renderStates(
            listOf(
                TopRailState.Empty to "keyboard-empty-dark.png",
                TopRailState.Suggestions(listOf("bonjour", "clavier", "privé")) to
                    "keyboard-suggestions-dark.png",
                TopRailState.Translation(TranslationFeedback.Loading) to
                    "keyboard-translation-dark.png",
            ),
        )
    }

    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).roundToInt()

    private fun measureAndLayout(view: View, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, width, view.measuredHeight)
    }

    private fun renderStates(states: List<Pair<TopRailState, String>>) {
        val output = System.getenv("VISUAL_OUTPUT_DIR")?.let(::File)
        output?.mkdirs()
        val root = KeyboardRootView(context)
        states.forEach { (state, fileName) ->
            root.renderRail(state)
            render(root, output?.resolve(fileName))
        }
    }

    private fun render(target: View, file: File?) {
        measureAndLayout(target, 1080)
        val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        target.draw(Canvas(bitmap))
        val sampledColors = buildSet {
            for (y in 0 until bitmap.height step 12) {
                for (x in 0 until bitmap.width step 12) add(bitmap.getPixel(x, y))
            }
        }
        assertTrue("Rendered keyboard must contain more than a blank canvas", sampledColors.size > 4)
        file?.let {
            FileOutputStream(it).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            assertTrue(it.isFile && it.length() > 0)
        }
    }
}
