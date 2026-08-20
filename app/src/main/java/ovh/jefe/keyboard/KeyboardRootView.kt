package ovh.jefe.keyboard

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout
import kotlin.math.roundToInt

internal class KeyboardRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    val railView = KeyboardRailView(context)
    val keyboardView = KeyboardView(context)

    init {
        orientation = VERTICAL
        addView(railView, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(
            keyboardView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
    }

    fun renderRail(state: TopRailState) = railView.render(state)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
