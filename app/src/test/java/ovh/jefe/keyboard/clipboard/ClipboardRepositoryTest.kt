package ovh.jefe.keyboard.clipboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: ClipboardDatabase
    private lateinit var files: ClipboardPrivateFileStore
    private lateinit var repository: RoomClipboardRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.noBackupFilesDir.resolve("clipboard").deleteRecursively()
        database = Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        files = ClipboardPrivateFileStore(context)
        repository = RoomClipboardRepository(database.clipboardDao(), files)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `stored entry becomes ready and loads exact text`() = runTest {
        val result = repository.store(prepared("first", fingerprint = "one", copiedAt = 1L))

        assertTrue(result is StoreResult.Stored)
        val ready = repository.observe().first() as ClipboardHistoryState.Ready
        val loaded = repository.load(ready.entries.single().id)!!
        assertEquals("first", loaded.items.single().textPayload)
        assertFalse(loaded.toString().contains("first"))
    }

    @Test
    fun `duplicate keeps pin promotes sensitivity and moves to top`() = runTest {
        val first = repository.store(prepared("same", fingerprint = "same", copiedAt = 1L)) as StoreResult.Stored
        repository.setPinned(first.id, true)

        repository.store(prepared("same", fingerprint = "same", copiedAt = 9L, sensitive = true))

        val ready = repository.observe().first() as ClipboardHistoryState.Ready
        assertEquals(1, ready.entries.size)
        assertTrue(ready.entries.single().isPinned)
        assertTrue(ready.entries.single().isSensitive)
        assertEquals(9L, ready.entries.single().lastCopiedAt)
    }

    @Test
    fun `delete removes row and clear returns empty`() = runTest {
        val one = repository.store(prepared("one", "one", 1L)) as StoreResult.Stored
        repository.store(prepared("two", "two", 2L))

        assertTrue(repository.delete(one.id))
        assertNull(repository.load(one.id))
        repository.clearAll()

        assertEquals(ClipboardHistoryState.Empty, repository.observe().first())
    }

    private fun prepared(
        text: String,
        fingerprint: String,
        copiedAt: Long,
        sensitive: Boolean = false,
    ) = PreparedClipboardEntry(
        id = ClipboardEntryId("entry-$fingerprint-$copiedAt"),
        createdAt = copiedAt,
        lastCopiedAt = copiedAt,
        kind = ClipboardKind.TEXT,
        isSensitive = sensitive,
        fingerprintSha256 = fingerprint,
        items = listOf(
            PreparedClipboardItem(0, "text/plain", text, null, null, null, text.toByteArray().size.toLong()),
        ),
    )
}
