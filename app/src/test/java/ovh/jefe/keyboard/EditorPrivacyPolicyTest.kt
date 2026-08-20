package ovh.jefe.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPrivacyPolicyTest {
    @Test
    fun `password pin and no learning are private`() {
        val inputTypes = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        inputTypes.forEach { inputType ->
            assertPrivate(EditorPrivacyPolicy.evaluate(inputType, 0))
        }
        assertPrivate(
            EditorPrivacyPolicy.evaluate(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
    }

    @Test
    fun `ordinary text allows local and explicit remote actions`() {
        val state = EditorPrivacyPolicy.evaluate(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            EditorInfo.IME_ACTION_SEND,
        )

        assertFalse(state.isPrivate)
        assertTrue(state.allowSuggestions)
        assertTrue(state.allowTranslation)
        assertTrue(state.allowDictation)
        assertFalse(state.forceSensitiveClipboard)
    }

    @Test
    fun `missing editor info and type null fail closed`() {
        assertPrivate(EditorPrivacyPolicy.evaluate(null))
        assertPrivate(EditorPrivacyPolicy.evaluate(InputType.TYPE_NULL, 0))
    }

    private fun assertPrivate(state: EditorPrivacyState) {
        assertTrue(state.isPrivate)
        assertFalse(state.allowSuggestions)
        assertFalse(state.allowTranslation)
        assertFalse(state.allowDictation)
        assertTrue(state.forceSensitiveClipboard)
    }
}
