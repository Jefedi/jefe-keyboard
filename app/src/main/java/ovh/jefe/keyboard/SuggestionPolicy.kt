package ovh.jefe.keyboard

internal data class EditorSelectionRange(val start: Int, val end: Int) {
    val isCollapsed: Boolean get() = start == end
}

internal enum class SuggestionMutation {
    CHARACTER,
    SPACE,
    DELETE,
    SUGGESTION,
    NON_SENSITIVE_PASTE,
}

internal class SuggestionSessionGate {
    private var eligible = false
    private var sensitivePasteTaint = false
    private val expectedSelections = ArrayDeque<EditorSelectionRange>()

    fun startSession() {
        sensitivePasteTaint = false
        invalidate()
    }

    fun invalidate() {
        eligible = false
        expectedSelections.clear()
    }

    fun taintForSession() {
        sensitivePasteTaint = true
        invalidate()
    }

    fun recordSuccessfulMutation(
        mutation: SuggestionMutation,
        selection: EditorSelectionRange?,
    ) {
        val supported = when (mutation) {
            SuggestionMutation.CHARACTER,
            SuggestionMutation.SPACE,
            SuggestionMutation.DELETE,
            SuggestionMutation.SUGGESTION,
            SuggestionMutation.NON_SENSITIVE_PASTE,
            -> true
        }
        eligible = !sensitivePasteTaint && supported && selection?.isCollapsed == true
        if (selection == null || !enqueueExpectedSelection(selection)) {
            invalidate()
            return
        }
    }

    fun recordExpectedSelection(selection: EditorSelectionRange): Boolean =
        enqueueExpectedSelection(selection)

    private fun enqueueExpectedSelection(selection: EditorSelectionRange): Boolean {
        if (expectedSelections.size == MAX_PENDING_SELECTIONS) {
            invalidate()
            return false
        }
        expectedSelections.addLast(selection)
        return true
    }

    fun recordSelectionUpdate(selection: EditorSelectionRange): Boolean {
        val matchIndex = expectedSelections.lastIndexOf(selection)
        if (matchIndex < 0) {
            invalidate()
            return false
        }
        repeat(matchIndex + 1) { expectedSelections.removeFirst() }
        return true
    }

    fun allowsSuggestionsAt(selection: EditorSelectionRange): Boolean =
        eligible && selection.isCollapsed

    private companion object {
        const val MAX_PENDING_SELECTIONS = 64
    }
}

internal data class SuggestionPolicyInput(
    val textBeforeCursor: String?,
    val selectionCollapsed: Boolean,
    val localMutationEligible: Boolean,
    val allowSuggestions: Boolean,
)

internal object SuggestionPolicy {
    fun contextOrNull(input: SuggestionPolicyInput): TextContext? {
        val text = input.textBeforeCursor ?: return null
        if (
            !input.selectionCollapsed ||
            !input.localMutationEligible ||
            !input.allowSuggestions
        ) {
            return null
        }
        val lastNonWhitespace = text.indexOfLast { !it.isWhitespace() }
        if (lastNonWhitespace < 0) return null
        val terminal = text[lastNonWhitespace]
        if (!terminal.isLetter() && terminal != '\'' && terminal != '’') return null
        val context = TextContextParser.parse(text)
        val hasPrefix = context.currentWord.any(Char::isLetter)
        val trailing = text.substring(lastNonWhitespace + 1)
        val hasContext = trailing.isNotEmpty() && trailing.all { it == ' ' } &&
            !context.lastWord.isNullOrBlank()
        return context.takeIf { hasPrefix || hasContext }
    }
}
