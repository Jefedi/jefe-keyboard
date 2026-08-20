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
    private data class ExpectedSelectionUpdate(
        val previousSelection: EditorSelectionRange,
        val selection: EditorSelectionRange,
    )

    private var eligible = false
    private var sensitivePasteTaint = false
    private val expectedSelections = ArrayDeque<ExpectedSelectionUpdate>()

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
        previousSelection: EditorSelectionRange?,
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
        if (
            previousSelection == null ||
            selection == null ||
            !enqueueExpectedSelection(previousSelection, selection)
        ) {
            invalidate()
            return
        }
    }

    fun recordExpectedSelection(
        previousSelection: EditorSelectionRange,
        selection: EditorSelectionRange,
    ): Boolean = enqueueExpectedSelection(previousSelection, selection)

    private fun enqueueExpectedSelection(
        previousSelection: EditorSelectionRange,
        selection: EditorSelectionRange,
    ): Boolean {
        if (expectedSelections.size == MAX_PENDING_SELECTIONS) {
            invalidate()
            return false
        }
        expectedSelections.addLast(ExpectedSelectionUpdate(previousSelection, selection))
        return true
    }

    fun recordSelectionUpdate(
        previousSelection: EditorSelectionRange,
        selection: EditorSelectionRange,
    ): Boolean {
        val pending = expectedSelections.toList()
        var matchIndex = -1
        pending.indices.forEach { startIndex ->
            if (pending[startIndex].previousSelection != previousSelection) return@forEach
            var cursor = previousSelection
            for (endIndex in startIndex until pending.size) {
                val expected = pending[endIndex]
                if (expected.previousSelection != cursor) break
                cursor = expected.selection
                if (cursor == selection) matchIndex = maxOf(matchIndex, endIndex)
            }
        }
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
