package ovh.jefe.keyboard

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import java.io.File
import java.io.FileOutputStream
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardViewTest {
    private lateinit var view: TrackingKeyboardView

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        view = TrackingKeyboardView(activity)
        activity.setContentView(view)
        layout(view)
    }

    @Test
    fun `enter action exposes its full text label without a generic icon`() {
        mapOf(
            EditorInfo.IME_ACTION_PREVIOUS to "Préc.",
            EditorInfo.IME_ACTION_NEXT to "Suiv.",
            EditorInfo.IME_ACTION_SEND to "Envoyer",
            EditorInfo.IME_ACTION_DONE to "OK",
        ).forEach { (action, expectedLabel) ->
            view.enterAction = action

            val enter = view.renderedKeys().single { it.action == KeyboardView.KeyAction.ENTER }

            assertEquals(expectedLabel, enter.label)
            assertFalse(enter.hasIcon)
        }
    }

    @Test
    fun `shifted long press accent commits uppercase and consumes one shot shift`() {
        val committed = mutableListOf<String>()
        view.onKeyChar = committed::add
        view.isShifted = true
        val e = view.renderedKeys().single {
            it.action == KeyboardView.KeyAction.CHAR && it.label.equals("e", ignoreCase = true)
        }

        view.onTouchEvent(down(e.centerX, e.centerY))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        val acute = view.renderedAccentOptions().single { it.label == "É" }
        view.onTouchEvent(up(acute.centerX, acute.centerY))

        assertEquals(listOf("É"), committed)
        assertFalse(view.isShifted)
        assertEquals(1, view.clickCount)
        assertEquals(1, view.hapticCount)
    }

    @Test
    fun `moving outside the pressed key cancels before release`() {
        val committed = mutableListOf<String>()
        view.onKeyChar = committed::add
        val q = view.renderedKeys().single {
            it.action == KeyboardView.KeyAction.CHAR && it.label == "q"
        }

        view.onTouchEvent(down(q.centerX, q.centerY))
        view.onTouchEvent(move(-20f, -20f))
        view.onTouchEvent(up(q.centerX, q.centerY))

        assertTrue(committed.isEmpty())
        assertEquals(0, view.clickCount)
        assertEquals(0, view.hapticCount)
    }

    @Test
    fun `cancelled touch cannot commit on a later release`() {
        val committed = mutableListOf<String>()
        view.onKeyChar = committed::add
        val q = view.renderedKeys().single {
            it.action == KeyboardView.KeyAction.CHAR && it.label == "q"
        }

        view.onTouchEvent(down(q.centerX, q.centerY))
        view.onTouchEvent(cancel(q.centerX, q.centerY))
        view.onTouchEvent(up(q.centerX, q.centerY))

        assertTrue(committed.isEmpty())
        assertEquals(0, view.clickCount)
        assertEquals(0, view.hapticCount)
    }

    @Test
    fun `successful key and suggestion taps reach accessibility and haptic hooks`() {
        val committed = mutableListOf<String>()
        val chosen = mutableListOf<String>()
        view.onKeyChar = committed::add
        view.onSuggestionClick = chosen::add
        view.suggestions = listOf("bonjour", "bonsoir", "bonne")
        val q = view.renderedKeys().single {
            it.action == KeyboardView.KeyAction.CHAR && it.label == "q"
        }
        val suggestion = view.renderedSuggestions().first()

        tap(q.centerX, q.centerY)
        tap(suggestion.centerX, suggestion.centerY)

        assertEquals(listOf("q"), committed)
        assertEquals(listOf("bonjour"), chosen)
        assertEquals(2, view.clickCount)
        assertEquals(2, view.hapticCount)
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, view.lastHapticFeedback)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, view.importantForAccessibility)
        assertTrue(view.contentDescription.isNotBlank())
    }

    @Test
    fun `all rendered keys and suggestions meet minimum touch height`() {
        val minimum = 44f * view.resources.displayMetrics.density

        assertTrue(view.renderedKeys().all { it.height >= minimum })
        assertEquals(3, view.renderedSuggestions().size)
        assertTrue(view.renderedSuggestions().all { it.height >= minimum })
    }

    @Test
    fun `shift and delete glyphs are more prominent than compact action labels`() {
        val keys = view.renderedKeys()
        val compactAction = keys.single { it.action == KeyboardView.KeyAction.SYMBOLS_TOGGLE }
        val utilityGlyphs = keys.filter {
            it.action == KeyboardView.KeyAction.SHIFT || it.action == KeyboardView.KeyAction.DELETE
        }

        assertEquals(2, utilityGlyphs.size)
        assertTrue(utilityGlyphs.all { it.textSizePx > compactAction.textSizePx })
    }

    @Test
    fun `render light and recording keyboard screenshots`() {
        val output = System.getenv("VISUAL_OUTPUT_DIR")?.let(::File) ?: return
        output.mkdirs()

        view.suggestions = listOf("bonjour", "bonsoir", "bonne")
        view.enterAction = EditorInfo.IME_ACTION_SEND
        render(view, File(output, "keyboard-light.png"))

        view.isRecording = true
        render(view, File(output, "keyboard-recording.png"))
    }

    private fun tap(x: Float, y: Float) {
        view.onTouchEvent(down(x, y))
        view.onTouchEvent(up(x, y))
    }

    private fun layout(target: View) {
        target.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        target.layout(0, 0, 1080, target.measuredHeight)
    }

    private fun render(target: View, file: File) {
        layout(target)
        val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        target.draw(Canvas(bitmap))
        assertHasVisualContent(bitmap)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(file.length() > 0)
    }

    private fun assertHasVisualContent(bitmap: Bitmap) {
        val sampledColors = buildSet {
            for (y in 0 until bitmap.height step 12) {
                for (x in 0 until bitmap.width step 12) add(bitmap.getPixel(x, y))
            }
        }
        assertTrue("Rendered keyboard must contain more than a blank canvas", sampledColors.size > 4)
    }

    private fun down(x: Float, y: Float) = event(MotionEvent.ACTION_DOWN, x, y)
    private fun move(x: Float, y: Float) = event(MotionEvent.ACTION_MOVE, x, y)
    private fun up(x: Float, y: Float) = event(MotionEvent.ACTION_UP, x, y)
    private fun cancel(x: Float, y: Float) = event(MotionEvent.ACTION_CANCEL, x, y)

    private fun event(action: Int, x: Float, y: Float): MotionEvent =
        MotionEvent.obtain(0L, 16L, action, x, y, 0)

    private class TrackingKeyboardView(context: Context) : KeyboardView(context) {
        var clickCount = 0
        var hapticCount = 0
        var lastHapticFeedback = -1

        override fun performClick(): Boolean {
            clickCount += 1
            return super.performClick()
        }

        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            hapticCount += 1
            lastHapticFeedback = feedbackConstant
            return true
        }
    }
}
