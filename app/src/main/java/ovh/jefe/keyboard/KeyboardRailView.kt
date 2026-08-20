package ovh.jefe.keyboard

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.children
import kotlin.math.roundToInt

internal class KeyboardRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onClipboardTabClick: (() -> Unit)? = null
    var onSuggestionClick: ((String) -> Unit)? = null
    var onTranslationRetryClick: (() -> Unit)? = null
    var onClipboardPromptClick: ((String) -> Unit)? = null
    var onClipboardPromptDismiss: (() -> Unit)? = null

    internal var state: TopRailState = TopRailState.Empty
        private set

    private val clipboard = ImageButton(context).apply {
        setImageResource(R.drawable.ic_clipboard)
        imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, R.color.suggestion_text),
        )
        contentDescription = context.getString(R.string.clipboard_open)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setOnClickListener { onClipboardTabClick?.invoke() }
    }
    private val content = LinearLayout(context).apply {
        orientation = HORIZONTAL
        showDividers = SHOW_DIVIDER_MIDDLE
        dividerDrawable = AppCompatResources.getDrawable(context, R.drawable.rail_divider)
    }

    init {
        orientation = HORIZONTAL
        minimumHeight = dp(48)
        addView(clipboard, LayoutParams(dp(48), dp(48)))
        addView(content, LayoutParams(0, dp(48), 1f))
        render(TopRailState.Empty)
    }

    fun render(state: TopRailState) {
        this.state = state
        content.removeAllViews()
        when (state) {
            TopRailState.Empty -> Unit
            is TopRailState.Suggestions -> state.values.forEach(::addSuggestion)
            is TopRailState.ClipboardPrompt -> addPrompt(state.prompt)
            is TopRailState.Translation -> addTranslation(state.feedback)
        }
    }

    private fun addSuggestion(value: String) {
        content.addView(
            TextView(context).apply {
                text = value
                tag = SUGGESTION_TAG
                gravity = Gravity.CENTER
                minHeight = dp(44)
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(R.string.suggestion_insert, value)
                setBackgroundResource(R.drawable.bg_rail_control)
                setOnClickListener { onSuggestionClick?.invoke(value) }
            },
            LayoutParams(0, dp(48), 1f),
        )
    }

    private fun addPrompt(prompt: ClipboardPromptUi) {
        val safePreview = if (prompt.isSensitive) {
            context.getString(R.string.clipboard_sensitive_preview)
        } else {
            prompt.preview
        }
        val safeType = prompt.typeLabel
        val promptButton = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = if (prompt.isSensitive) {
                context.getString(R.string.clipboard_sensitive_paste_description)
            } else {
                prompt.contentDescription
            }
            setBackgroundResource(R.drawable.bg_rail_control)
            setOnClickListener { onClipboardPromptClick?.invoke(prompt.entryId) }
            addView(
                TextView(context).apply {
                    setText(R.string.clipboard_paste_action)
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(12) },
            )
            addView(
                TextView(context).apply {
                    text = safePreview
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) },
            )
            if (safeType.isNotEmpty()) {
                addView(
                    TextView(context).apply {
                        text = safeType
                        maxLines = 1
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply {
                        marginStart = dp(8)
                        marginEnd = dp(8)
                    },
                )
            }
        }
        content.addView(promptButton, LayoutParams(0, dp(48), 1f))
        content.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(
                    ContextCompat.getColor(context, R.color.suggestion_text),
                )
                contentDescription = context.getString(R.string.clipboard_close_prompt)
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                setOnClickListener { onClipboardPromptDismiss?.invoke() }
            },
            LayoutParams(dp(48), dp(48)),
        )
    }

    private fun addTranslation(feedback: TranslationFeedback) {
        when (feedback) {
            TranslationFeedback.Loading -> content.addView(
                statusView(R.string.rail_translation_loading, includeProgress = true),
                LayoutParams(LayoutParams.MATCH_PARENT, dp(48)),
            )
            TranslationFeedback.Success -> content.addView(
                statusView(R.string.rail_translation_success),
                LayoutParams(LayoutParams.MATCH_PARENT, dp(48)),
            )
            TranslationFeedback.Error -> content.addView(
                Button(context).apply {
                    tag = RETRY_TAG
                    setText(R.string.rail_translation_error)
                    minHeight = dp(44)
                    setOnClickListener { onTranslationRetryClick?.invoke() }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(48)),
            )
            TranslationFeedback.Idle -> Unit
        }
    }

    private fun statusView(@StringRes label: Int, includeProgress: Boolean = false): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            if (includeProgress) {
                addView(
                    ProgressBar(context).apply { isIndeterminate = true },
                    LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) },
                )
            }
            addView(TextView(context).apply { setText(label) })
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    internal fun suggestionViews(): List<TextView> =
        content.children.filterIsInstance<TextView>().filter { it.tag == SUGGESTION_TAG }.toList()

    internal fun retryButton(): Button =
        content.children.filterIsInstance<Button>().single { it.tag == RETRY_TAG }

    internal fun touchControls(): List<View> = buildList {
        fun collect(view: View) {
            if (view.isClickable) add(view)
            if (view is ViewGroup) view.children.forEach(::collect)
        }
        collect(this@KeyboardRailView)
    }.distinct()

    internal fun visibleTexts(): List<String> = buildList {
        fun collect(view: View) {
            if (view is TextView && view.visibility == VISIBLE) add(view.text.toString())
            if (view is ViewGroup) view.children.forEach(::collect)
        }
        collect(this@KeyboardRailView)
    }

    private companion object {
        const val SUGGESTION_TAG = "keyboard-rail-suggestion"
        const val RETRY_TAG = "keyboard-rail-retry"
    }
}
