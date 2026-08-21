package ovh.jefe.keyboard.clipboard

import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.View
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardPasteCoordinatorTest {
    @Test
    fun `direct text paste preserves spaces and reports sensitivity`() = runTest {
        val connection = RecordingInputConnection()
        val target = target(connection)
        val coordinator = ClipboardPasteCoordinator(
            repository = SingleEntryRepository(entry(" traduction ", sensitive = true)),
            currentTarget = { target },
            grants = ClipboardGrantRegistry(),
        )

        val result = coordinator.pasteEntry(ClipboardEntryId("entry"), target)

        assertEquals(" traduction ", connection.committed)
        assertEquals(ClipboardPasteResult.Success(sensitive = true), result)
    }

    @Test
    fun `changed session fails before mutating the editor`() = runTest {
        val connection = RecordingInputConnection()
        val requested = target(connection, session = 1L)
        val current = target(connection, session = 2L)
        val coordinator = ClipboardPasteCoordinator(
            repository = SingleEntryRepository(entry("secret", sensitive = false)),
            currentTarget = { current },
            grants = ClipboardGrantRegistry(),
        )

        val result = coordinator.pasteEntry(ClipboardEntryId("entry"), requested)

        assertTrue(result is ClipboardPasteResult.Failure)
        assertEquals(null, connection.committed)
    }

    @Test
    fun `editor rejection is returned without a second commit`() = runTest {
        val connection = RecordingInputConnection(accept = false)
        val target = target(connection)
        val coordinator = ClipboardPasteCoordinator(
            repository = SingleEntryRepository(entry("one", sensitive = false)),
            currentTarget = { target },
            grants = ClipboardGrantRegistry(),
        )

        val result = coordinator.pasteEntry(ClipboardEntryId("entry"), target)

        assertTrue(result is ClipboardPasteResult.Failure)
        assertEquals(1, connection.commitCalls)
    }

    @Test
    fun `textual group pastes all items in order with one editor mutation`() = runTest {
        val connection = RecordingInputConnection()
        val target = target(connection)
        val group = LoadedClipboardEntry(
            ClipboardEntrySummary(ClipboardEntryId("entry"), ClipboardKind.GROUP, 2, false, false, 6L, 1L, 1L),
            listOf(
                LoadedClipboardItem(0, "text/plain", "one", null, null, null, 3L),
                LoadedClipboardItem(1, "text/plain", "two", null, null, null, 3L),
            ),
        )
        val coordinator = ClipboardPasteCoordinator(
            repository = SingleEntryRepository(group),
            currentTarget = { target },
            grants = ClipboardGrantRegistry(),
        )

        val result = coordinator.pasteEntry(ClipboardEntryId("entry"), target)

        assertTrue(result is ClipboardPasteResult.Success)
        assertEquals("one\ntwo", connection.committed)
        assertEquals(1, connection.commitCalls)
    }

    private fun target(connection: InputConnection, session: Long = 1L) = EditorTarget(
        sessionId = session,
        uid = 42,
        packageName = "editor",
        inputConnection = connection,
        editorInfo = EditorInfo().apply { packageName = "editor" },
    )

    private fun entry(text: String, sensitive: Boolean) = LoadedClipboardEntry(
        ClipboardEntrySummary(ClipboardEntryId("entry"), ClipboardKind.TEXT, 1, false, sensitive, text.length.toLong(), 1L, 1L),
        listOf(LoadedClipboardItem(0, "text/plain", text, null, null, null, text.length.toLong())),
    )

    private class RecordingInputConnection(private val accept: Boolean = true) :
        BaseInputConnection(View(ApplicationProvider.getApplicationContext()), false) {
        var committed: String? = null
        var commitCalls = 0
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            commitCalls += 1
            if (accept) committed = text?.toString()
            return accept
        }
    }

    private class SingleEntryRepository(private val entry: LoadedClipboardEntry) : ClipboardRepository {
        override fun observe() = flowOf(ClipboardHistoryState.Empty)
        override suspend fun store(prepared: PreparedClipboardEntry) = StoreResult.Failure(ClipboardFailure.DATABASE_UNAVAILABLE)
        override suspend fun load(id: ClipboardEntryId) = entry
        override suspend fun setPinned(id: ClipboardEntryId, pinned: Boolean, confirmImpact: Boolean) = PinResult.NotFound
        override suspend fun markSensitive(id: ClipboardEntryId) = false
        override suspend fun delete(id: ClipboardEntryId) = false
        override suspend fun clearAll() = Unit
        override suspend fun search(query: String, generation: Long) = SearchResult(generation, emptyList())
        override suspend fun reconcile() = Unit
    }
}
