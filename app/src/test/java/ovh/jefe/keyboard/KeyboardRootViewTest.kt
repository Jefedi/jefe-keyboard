package ovh.jefe.keyboard

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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

    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).roundToInt()

    private fun measureAndLayout(view: View, width: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, width, view.measuredHeight)
    }
}
