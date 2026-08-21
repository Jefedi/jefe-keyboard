package ovh.jefe.keyboard.clipboard

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ClipboardDao {
    @Query("SELECT * FROM clipboard_entries WHERE storageState = 'READY' ORDER BY lastCopiedAt DESC")
    fun observeReady(): Flow<List<ClipboardEntryEntity>>

    @Transaction
    @Query("SELECT * FROM clipboard_entries WHERE id = :id AND storageState = 'READY' LIMIT 1")
    suspend fun loadReady(id: String): ClipboardEntryWithItems?

    @Transaction
    @Query("SELECT * FROM clipboard_entries WHERE id = :id LIMIT 1")
    suspend fun loadAny(id: String): ClipboardEntryWithItems?

    @Query("SELECT * FROM clipboard_entries WHERE fingerprintSha256 = :fingerprint AND storageState = 'READY' LIMIT 1")
    suspend fun findReadyByFingerprint(fingerprint: String): ClipboardEntryEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: ClipboardEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<ClipboardItemEntity>)

    @Transaction
    suspend fun insertEntryAndItems(entry: ClipboardEntryEntity, items: List<ClipboardItemEntity>) {
        insertEntry(entry)
        insertItems(items)
    }

    @Query("UPDATE clipboard_entries SET storageState = :state WHERE id = :id")
    suspend fun updateState(id: String, state: String)

    @Query("UPDATE clipboard_entries SET isPinned = :pinned, revision = revision + 1 WHERE id = :id AND storageState = 'READY'")
    suspend fun setPinned(id: String, pinned: Boolean): Int

    @Query("UPDATE clipboard_entries SET isSensitive = 1, revision = revision + 1 WHERE id = :id AND storageState = 'READY'")
    suspend fun markSensitive(id: String): Int

    @Query("DELETE FROM clipboard_entries WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("DELETE FROM clipboard_entries")
    suspend fun deleteAll()

    @Query("SELECT * FROM clipboard_entries WHERE storageState != 'READY'")
    suspend fun nonReadyEntries(): List<ClipboardEntryEntity>

    @Query("SELECT blobId FROM clipboard_items WHERE blobId IS NOT NULL")
    suspend fun managedBlobIds(): List<String>

    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE storageState = 'READY' AND isPinned = 0")
    suspend fun unpinnedCount(): Int

    @Query("SELECT COALESCE(SUM(storedByteSize), 0) FROM clipboard_entries WHERE storageState = 'READY' AND isPinned = 0")
    suspend fun unpinnedBytes(): Long

    @Query("SELECT id FROM clipboard_entries WHERE storageState = 'READY' AND isPinned = 0 ORDER BY lastCopiedAt ASC")
    suspend fun oldestUnpinnedIds(): List<String>
}
