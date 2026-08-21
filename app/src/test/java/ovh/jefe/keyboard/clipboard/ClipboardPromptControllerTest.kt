package ovh.jefe.keyboard.clipboard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardPromptControllerTest {
    @Test
    fun `text prompt shows a bounded beginning and a stable type`() = runTest {
        val repository = PromptRepository(entry("Bonjour depuis le presse-papiers", ClipboardKind.TEXT, false))
        val controller = ClipboardPromptController(repository, backgroundScope)

        controller.present(repository.loaded.summary)

        val prompt = controller.prompt.value!!
        assertEquals("Texte", prompt.typeLabel)
        assertTrue(prompt.preview.startsWith("Bonjour"))
        assertTrue(prompt.preview.length <= 80)
        assertFalse(prompt.toString().contains("Bonjour"))
    }

    @Test
    fun `sensitive prompt is masked but keeps the entry id for explicit paste`() = runTest {
        val repository = PromptRepository(entry("SENTINEL-password", ClipboardKind.TEXT, true))
        val controller = ClipboardPromptController(repository, backgroundScope)

        controller.present(repository.loaded.summary)

        val prompt = controller.prompt.value!!
        assertEquals("entry", prompt.entryId)
        assertTrue(prompt.isSensitive)
        assertFalse(prompt.preview.contains("SENTINEL"))
    }

    @Test
    fun `twenty second timer counts only while prompt is actually visible`() = runTest {
        val repository = PromptRepository(entry("texte", ClipboardKind.TEXT, false))
        val controller = ClipboardPromptController(repository, backgroundScope, nowMillis = { testScheduler.currentTime })
        controller.present(repository.loaded.summary)
        controller.setVisible(true)
        runCurrent()

        advanceTimeBy(10_000)
        controller.setVisible(false)
        advanceTimeBy(30_000)
        assertTrue(controller.prompt.value != null)

        controller.setVisible(true)
        advanceTimeBy(10_001)
        runCurrent()
        assertNull(controller.prompt.value)
    }

    private fun entry(text: String, kind: ClipboardKind, sensitive: Boolean) = LoadedClipboardEntry(
        ClipboardEntrySummary(ClipboardEntryId("entry"), kind, 1, false, sensitive, text.length.toLong(), 1L, 1L),
        listOf(LoadedClipboardItem(0, "text/plain", text, null, null, null, text.length.toLong())),
    )

    private class PromptRepository(val loaded: LoadedClipboardEntry) : ClipboardRepository {
        override fun observe() = flowOf(ClipboardHistoryState.Empty)
        override suspend fun store(prepared: PreparedClipboardEntry) = StoreResult.Failure(ClipboardFailure.DATABASE_UNAVAILABLE)
        override suspend fun load(id: ClipboardEntryId) = loaded
        override suspend fun setPinned(id: ClipboardEntryId, pinned: Boolean, confirmImpact: Boolean) = PinResult.NotFound
        override suspend fun markSensitive(id: ClipboardEntryId) = false
        override suspend fun delete(id: ClipboardEntryId) = false
        override suspend fun clearAll() = Unit
        override suspend fun search(query: String, generation: Long) = SearchResult(generation, emptyList())
        override suspend fun reconcile() = Unit
    }
}
