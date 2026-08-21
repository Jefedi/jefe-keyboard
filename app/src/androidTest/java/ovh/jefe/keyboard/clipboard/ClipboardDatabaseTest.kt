package ovh.jefe.keyboard.clipboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClipboardDatabaseTest {
    @Test
    fun readyEntriesKeepItemOrderAndHideInternalStates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, ClipboardDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = database.clipboardDao()
        val entry = ClipboardEntryEntity(
            id = "entry-1",
            createdAt = 1L,
            lastCopiedAt = 2L,
            kind = ClipboardKind.GROUP.name,
            itemCount = 2,
            isPinned = false,
            isSensitive = false,
            storedByteSize = 6L,
            revision = 1L,
            fingerprintSha256 = "fingerprint",
            storageState = ClipboardStorageState.STAGING.name,
        )
        dao.insertEntryAndItems(
            entry,
            listOf(
                ClipboardItemEntity("entry-1", 1, "text/plain", "two", null, null, null, 3L),
                ClipboardItemEntity("entry-1", 0, "text/plain", "one", null, null, null, 3L),
            ),
        )

        assertTrueReadyListIsEmpty(dao)
        dao.updateState("entry-1", ClipboardStorageState.READY.name)
        val loaded = dao.loadReady("entry-1")!!

        assertEquals(listOf(0, 1), loaded.items.map { it.itemIndex })
        assertFalse(loaded.toString().contains("one"))
        assertFalse(loaded.items.first().toString().contains("one"))
        database.close()
    }

    private suspend fun assertTrueReadyListIsEmpty(dao: ClipboardDao) {
        assertEquals(emptyList<ClipboardEntryEntity>(), dao.observeReady().first())
    }
}
