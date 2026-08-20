package ovh.jefe.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

internal data class EditorPrivacyState(
    val isPrivate: Boolean,
    val allowSuggestions: Boolean,
    val allowTranslation: Boolean,
    val allowDictation: Boolean,
    val forceSensitiveClipboard: Boolean,
)

internal object EditorPrivacyPolicy {
    fun evaluate(info: EditorInfo?): EditorPrivacyState =
        if (info == null) privateState() else evaluate(info.inputType, info.imeOptions)

    fun evaluate(inputType: Int, imeOptions: Int): EditorPrivacyState {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val password = when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val noLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        val privateField = inputClass == InputType.TYPE_NULL || password || noLearning
        return if (privateField) privateState() else EditorPrivacyState(
            isPrivate = false,
            allowSuggestions = true,
            allowTranslation = true,
            allowDictation = true,
            forceSensitiveClipboard = false,
        )
    }

    private fun privateState() = EditorPrivacyState(
        isPrivate = true,
        allowSuggestions = false,
        allowTranslation = false,
        allowDictation = false,
        forceSensitiveClipboard = true,
    )
}
