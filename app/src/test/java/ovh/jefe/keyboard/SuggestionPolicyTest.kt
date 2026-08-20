package ovh.jefe.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggestionPolicyTest {
    @Test
    fun `policy accepts prefix and context only after a local mutation`() {
        val prefix = SuggestionPolicy.contextOrNull(
            SuggestionPolicyInput("bo", true, true, true),
        )
        val context = SuggestionPolicy.contextOrNull(
            SuggestionPolicyInput("je ", true, true, true),
        )

        assertEquals("bo", prefix?.currentWord)
        assertEquals("je", context?.lastWord)
    }

    @Test
    fun `policy rejects startup blank punctuation selection and private session`() {
        listOf(
            SuggestionPolicyInput("bo", true, false, true),
            SuggestionPolicyInput("   ", true, true, true),
            SuggestionPolicyInput("bonjour.", true, true, true),
            SuggestionPolicyInput("je. ", true, true, true),
            SuggestionPolicyInput("je\n", true, true, true),
            SuggestionPolicyInput("je\t", true, true, true),
            SuggestionPolicyInput("bo", false, true, true),
            SuggestionPolicyInput("bo", true, true, false),
        ).forEach { assertNull(SuggestionPolicy.contextOrNull(it)) }
    }

    @Test
    fun `external selection invalidates a previously accepted edit`() {
        val gate = SuggestionSessionGate()
        gate.recordSuccessfulMutation(SuggestionMutation.CHARACTER, EditorSelectionRange(2, 2))
        assertTrue(gate.recordSelectionUpdate(EditorSelectionRange(2, 2)))
        assertFalse(gate.recordSelectionUpdate(EditorSelectionRange(0, 0)))
        assertFalse(gate.allowsSuggestionsAt(EditorSelectionRange(0, 0)))
    }

    @Test
    fun `two fast edits survive delayed ordered selection callbacks`() {
        val gate = SuggestionSessionGate()
        gate.recordSuccessfulMutation(SuggestionMutation.CHARACTER, EditorSelectionRange(1, 1))
        gate.recordSuccessfulMutation(SuggestionMutation.CHARACTER, EditorSelectionRange(2, 2))

        assertTrue(gate.recordSelectionUpdate(EditorSelectionRange(1, 1)))
        assertTrue(gate.recordSelectionUpdate(EditorSelectionRange(2, 2)))
        assertTrue(gate.allowsSuggestionsAt(EditorSelectionRange(2, 2)))
    }

    @Test
    fun `coalesced duplicate callback leaves no stale provenance for an external move`() {
        val gate = SuggestionSessionGate()
        gate.recordSuccessfulMutation(SuggestionMutation.CHARACTER, EditorSelectionRange(1, 1))
        gate.recordSuccessfulMutation(SuggestionMutation.DELETE, EditorSelectionRange(0, 0))
        gate.recordSuccessfulMutation(SuggestionMutation.CHARACTER, EditorSelectionRange(1, 1))

        assertTrue(gate.recordSelectionUpdate(EditorSelectionRange(1, 1)))
        assertFalse(gate.recordSelectionUpdate(EditorSelectionRange(0, 0)))
        assertFalse(gate.allowsSuggestionsAt(EditorSelectionRange(0, 0)))
    }

    @Test
    fun `sensitive taint survives local edits and resets only for the next session`() {
        val gate = SuggestionSessionGate()
        gate.startSession()
        gate.taintForSession()

        gate.recordSuccessfulMutation(
            SuggestionMutation.NON_SENSITIVE_PASTE,
            EditorSelectionRange(4, 4),
        )
        assertFalse(gate.allowsSuggestionsAt(EditorSelectionRange(4, 4)))

        gate.startSession()
        gate.recordSuccessfulMutation(
            SuggestionMutation.NON_SENSITIVE_PASTE,
            EditorSelectionRange(5, 5),
        )
        assertTrue(gate.allowsSuggestionsAt(EditorSelectionRange(5, 5)))
    }

    @Test
    fun `excess pending selection callbacks fail closed`() {
        val gate = SuggestionSessionGate()

        repeat(65) { cursor ->
            gate.recordSuccessfulMutation(
                SuggestionMutation.CHARACTER,
                EditorSelectionRange(cursor + 1, cursor + 1),
            )
        }

        assertFalse(gate.allowsSuggestionsAt(EditorSelectionRange(65, 65)))
        assertFalse(gate.recordSelectionUpdate(EditorSelectionRange(1, 1)))
    }
}
