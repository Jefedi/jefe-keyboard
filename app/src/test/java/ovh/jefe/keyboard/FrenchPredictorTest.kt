package ovh.jefe.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class FrenchPredictorTest {
    @Test fun `context suggestions follow a completed French pronoun`() {
        assertEquals(listOf("suis", "vais", "veux"), FrenchPredictor().suggest("", "je"))
    }

    @Test fun `prefix bo suggestions are the first three distinct completions`() {
        assertEquals(
            listOf("bon", "bout", "boire"),
            FrenchPredictor().suggest("bo"),
        )
    }
}
