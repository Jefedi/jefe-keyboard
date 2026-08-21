package ovh.jefe.keyboard

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import kotlin.math.roundToInt
import ovh.jefe.keyboard.clipboard.ClipboardPanelView

internal enum class KeyboardRootMode { KEYBOARD, CLIPBOARD, CLIPBOARD_SEARCH }

internal class KeyboardRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    val railView = KeyboardRailView(context)
    val keyboardView = KeyboardView(context)
    val clipboardPanelView = ClipboardPanelView(context)
    var mode: KeyboardRootMode = KeyboardRootMode.KEYBOARD
        private set

    init {
        orientation = VERTICAL
        addView(railView, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(
            keyboardView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        addView(
            clipboardPanelView.apply { visibility = GONE },
            LayoutParams(LayoutParams.MATCH_PARENT, dp(282)),
        )
        clipboardPanelView.onBack = ::showKeyboard
    }

    fun renderRail(state: TopRailState) = railView.render(state)

    fun showClipboard() {
        mode = KeyboardRootMode.CLIPBOARD
        railView.visibility = GONE
        keyboardView.visibility = GONE
        clipboardPanelView.visibility = VISIBLE
    }

    fun showKeyboard() {
        mode = KeyboardRootMode.KEYBOARD
        clipboardPanelView.visibility = GONE
        railView.visibility = VISIBLE
        keyboardView.visibility = VISIBLE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
