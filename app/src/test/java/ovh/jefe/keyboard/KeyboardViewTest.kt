package ovh.jefe.keyboard

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.ColorUtils
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
    fun `releasing outside without a move event cancels the key`() {
        val committed = mutableListOf<String>()
        view.onKeyChar = committed::add
        val q = view.renderedKeys().single {
            it.action == KeyboardView.KeyAction.CHAR && it.label == "q"
        }

        view.onTouchEvent(down(q.centerX, q.centerY))
        view.onTouchEvent(up(-20f, -20f))

        assertTrue(committed.isEmpty())
        assertEquals(0, view.clickCount)
        assertEquals(0, view.hapticCount)
    }

    @Test
    fun `leaving the accent corridor permanently cancels the long press`() {
        val committed = mutableListOf<String>()
        view.onKeyChar = committed::add
        val e = view.renderedKeys().single {
            it.action == KeyboardView.KeyAction.CHAR && it.label == "e"
        }

        view.onTouchEvent(down(e.centerX, e.centerY))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(450))
        val acute = view.renderedAccentOptions().single { it.label == "é" }
        view.onTouchEvent(move(-20f, -20f))
        view.onTouchEvent(move(acute.centerX, acute.centerY))
        view.onTouchEvent(up(acute.centerX, acute.centerY))

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
    fun `successful key taps reach accessibility and haptic hooks`() {
        val committed = mutableListOf<String>()
        view.onKeyChar = committed::add
        val q = view.renderedKeys().single {
            it.action == KeyboardView.KeyAction.CHAR && it.label == "q"
        }

        tap(q.centerX, q.centerY)

        assertEquals(listOf("q"), committed)
        assertEquals(1, view.clickCount)
        assertEquals(1, view.hapticCount)
        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_YES, view.importantForAccessibility)
        assertTrue(view.contentDescription.isNotBlank())
    }

    @Test
    fun `all rendered keys meet minimum touch height`() {
        val minimum = 44f * view.resources.displayMetrics.density

        assertTrue(view.renderedKeys().all { it.height >= minimum })
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
    fun `remote action flag invalidates without removing mic or translation keys`() {
        val invalidationsBefore = view.invalidateCount

        view.remoteActionsEnabled = false

        assertFalse(view.remoteActionsEnabled)
        assertTrue(view.invalidateCount > invalidationsBefore)
        assertEquals(
            setOf(KeyboardView.KeyAction.MIC, KeyboardView.KeyAction.TRANSLATE),
            view.renderedKeys().map { it.action }.filter {
                it == KeyboardView.KeyAction.MIC || it == KeyboardView.KeyAction.TRANSLATE
            }.toSet(),
        )
    }

    @Test
    fun `night pressed mic and enter keep accessible foreground contrast`() {
        val darkConfiguration = Configuration(view.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        }
        val darkView = TrackingKeyboardView(view.context.createConfigurationContext(darkConfiguration)).apply {
            enterAction = EditorInfo.IME_ACTION_SEND
        }
        layout(darkView)

        val mic = darkView.renderedKeys().single { it.action == KeyboardView.KeyAction.MIC }
        darkView.onTouchEvent(down(mic.centerX, mic.centerY))
        val pressedMic = darkView.renderedKeys().single { it.action == KeyboardView.KeyAction.MIC }
        assertTrue(ColorUtils.calculateContrast(pressedMic.foregroundColor, pressedMic.backgroundColor) >= 4.5)
        darkView.onTouchEvent(cancel(mic.centerX, mic.centerY))

        val enter = darkView.renderedKeys().single { it.action == KeyboardView.KeyAction.ENTER }
        darkView.onTouchEvent(down(enter.centerX, enter.centerY))
        val pressedEnter = darkView.renderedKeys().single { it.action == KeyboardView.KeyAction.ENTER }
        assertTrue(ColorUtils.calculateContrast(pressedEnter.foregroundColor, pressedEnter.backgroundColor) >= 4.5)
        darkView.onTouchEvent(cancel(enter.centerX, enter.centerY))
    }

    @Test
    fun `render light and recording keyboard screenshots`() {
        val output = System.getenv("VISUAL_OUTPUT_DIR")?.let(::File)
        output?.mkdirs()

        view.enterAction = EditorInfo.IME_ACTION_SEND
        render(view, output?.resolve("keyboard-light.png"))

        view.isRecording = true
        render(view, output?.resolve("keyboard-recording.png"))

        val darkConfiguration = Configuration(view.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_YES
        }
        val darkView = TrackingKeyboardView(view.context.createConfigurationContext(darkConfiguration)).apply {
            enterAction = EditorInfo.IME_ACTION_SEND
        }
        render(darkView, output?.resolve("keyboard-dark.png"))
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

    private fun render(target: View, file: File?) {
        layout(target)
        val bitmap = Bitmap.createBitmap(target.width, target.height, Bitmap.Config.ARGB_8888)
        target.draw(Canvas(bitmap))
        assertHasVisualContent(bitmap)
        file?.let {
            FileOutputStream(it).use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
            assertTrue(it.length() > 0)
        }
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
        var invalidateCount = 0

        override fun invalidate() {
            invalidateCount += 1
            super.invalidate()
        }

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
