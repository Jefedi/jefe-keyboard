package ovh.jefe.keyboard

data class TextContext(
    val currentWord: String,
    val lastWord: String?,
)

object TextContextParser {
    private val token = Regex("[\\p{L}'’]+")

    fun parse(textBeforeCursor: CharSequence): TextContext {
        val text = textBeforeCursor.toString()
        val matches = token.findAll(text).toList()
        val active = matches.lastOrNull()?.takeIf { it.range.last == text.lastIndex }
        val previous = when {
            active != null -> matches.dropLast(1).lastOrNull()
            else -> matches.lastOrNull()
        }

        return TextContext(
            currentWord = active?.value.orEmpty(),
            lastWord = previous?.value,
        )
    }
}
