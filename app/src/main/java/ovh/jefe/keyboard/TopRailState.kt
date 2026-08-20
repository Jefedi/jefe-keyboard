package ovh.jefe.keyboard

internal sealed interface TranslationFeedback {
    data object Idle : TranslationFeedback
    data object Loading : TranslationFeedback
    data object Success : TranslationFeedback
    data object Error : TranslationFeedback
}

internal class ClipboardPromptUi(
    val entryId: String,
    val preview: String,
    val typeLabel: String,
    val contentDescription: String,
    val isSensitive: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is ClipboardPromptUi &&
        entryId == other.entryId && preview == other.preview && typeLabel == other.typeLabel &&
        contentDescription == other.contentDescription && isSensitive == other.isSensitive

    override fun hashCode(): Int = 31 * entryId.hashCode() + isSensitive.hashCode()

    override fun toString(): String =
        "ClipboardPromptUi(entryId=$entryId, sensitive=$isSensitive, redacted=true)"
}

internal class TopRailInputs(
    val translation: TranslationFeedback = TranslationFeedback.Idle,
    val clipboardPrompt: ClipboardPromptUi? = null,
    val suggestions: List<String> = emptyList(),
) {
    fun copy(
        translation: TranslationFeedback = this.translation,
        clipboardPrompt: ClipboardPromptUi? = this.clipboardPrompt,
        suggestions: List<String> = this.suggestions,
    ): TopRailInputs = TopRailInputs(translation, clipboardPrompt, suggestions)

    override fun toString(): String = "TopRailInputs(redacted=true)"
}

internal sealed interface TopRailState {
    data object Empty : TopRailState
    data class Translation(val feedback: TranslationFeedback) : TopRailState
    data class ClipboardPrompt(val prompt: ClipboardPromptUi) : TopRailState

    class Suggestions(val values: List<String>) : TopRailState {
        override fun equals(other: Any?): Boolean = other is Suggestions && values == other.values

        override fun hashCode(): Int = values.size

        override fun toString(): String = "Suggestions(count=${values.size}, redacted=true)"
    }
}

internal object TopRailResolver {
    fun resolve(inputs: TopRailInputs): TopRailState {
        if (inputs.translation != TranslationFeedback.Idle) {
            return TopRailState.Translation(inputs.translation)
        }
        inputs.clipboardPrompt?.let { return TopRailState.ClipboardPrompt(it) }
        val values = inputs.suggestions.filter(String::isNotBlank).take(3)
        return if (values.isEmpty()) TopRailState.Empty else TopRailState.Suggestions(values)
    }
}
