package ovh.jefe.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class FrenchPredictorTest {
    @Test fun `context suggestions follow a completed French pronoun`() {
        assertEquals(listOf("suis", "vais", "veux"), FrenchPredictor().suggest("", "je"))
    }

    @Test fun `prefix suggestions are unique and limited to three`() {
        val result = FrenchPredictor().suggest("bo")
        assertEquals(result.distinct(), result)
        assertEquals(true, result.size <= 3)
        assertEquals(true, result.all { it.startsWith("bo") })
    }
}
