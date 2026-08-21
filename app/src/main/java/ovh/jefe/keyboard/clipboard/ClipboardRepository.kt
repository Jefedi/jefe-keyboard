package ovh.jefe.keyboard.clipboard

import java.io.Closeable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LoadedClipboardItem(
    val itemIndex: Int,
    val mimeType: String,
    val textPayload: String?,
    val htmlPayload: String?,
    val blobId: String?,
    val safeDisplayName: String?,
    val plainByteSize: Long,
) {
    override fun toString(): String = "LoadedClipboardItem(index=$itemIndex, redacted=true)"
}

internal class LoadedClipboardEntry(
    val summary: ClipboardEntrySummary,
    items: List<LoadedClipboardItem>,
) : Closeable {
    val items = immutableClipboardList(items.sortedBy { it.itemIndex })
    override fun close() = Unit
    override fun toString(): String = "LoadedClipboardEntry(kind=${summary.kind}, redacted=true)"
}

internal sealed interface StoreResult {
    class Stored(val id: ClipboardEntryId, val duplicate: Boolean) : StoreResult
    class Failure(val failure: ClipboardFailure) : StoreResult
}

internal sealed interface PinResult {
    data object Updated : PinResult
    data object NotFound : PinResult
    data object RequiresConfirmation : PinResult
}

internal class SearchResult(val generation: Long, entries: List<ClipboardEntrySummary>) {
    val entries = immutableClipboardList(entries)
}

internal interface ClipboardRepository {
    fun observe(): Flow<ClipboardHistoryState>
    suspend fun store(prepared: PreparedClipboardEntry): StoreResult
    suspend fun load(id: ClipboardEntryId): LoadedClipboardEntry?
    suspend fun setPinned(id: ClipboardEntryId, pinned: Boolean, confirmImpact: Boolean = false): PinResult
    suspend fun markSensitive(id: ClipboardEntryId): Boolean
    suspend fun delete(id: ClipboardEntryId): Boolean
    suspend fun clearAll()
    suspend fun search(query: String, generation: Long): SearchResult
    suspend fun reconcile()
}

internal class RoomClipboardRepository(
    private val dao: ClipboardDao,
    private val files: ClipboardPrivateFileStore,
) : ClipboardRepository {
    private val mutations = Mutex()

    override fun observe(): Flow<ClipboardHistoryState> = dao.observeReady().map { entries ->
        if (entries.isEmpty()) ClipboardHistoryState.Empty
        else ClipboardHistoryState.Ready(entries.map(::summary))
    }

    override suspend fun store(prepared: PreparedClipboardEntry): StoreResult = mutations.withLock {
        if (prepared.storedByteSize > ClipboardLimits.MAX_ENTRY_BYTES) {
            prepared.close()
            return@withLock StoreResult.Failure(ClipboardFailure.ENTRY_TOO_LARGE)
        }
        val duplicate = dao.findReadyByFingerprint(prepared.fingerprintSha256)
        if (duplicate != null) {
            dao.updateDuplicate(duplicate.id, prepared.lastCopiedAt, prepared.isSensitive)
            prepared.close()
            return@withLock StoreResult.Stored(ClipboardEntryId(duplicate.id), duplicate = true)
        }

        val entry = ClipboardEntryEntity(
            id = prepared.id.value,
            createdAt = prepared.createdAt,
            lastCopiedAt = prepared.lastCopiedAt,
            kind = prepared.kind.name,
            itemCount = prepared.items.size,
            isPinned = false,
            isSensitive = prepared.isSensitive,
            storedByteSize = prepared.storedByteSize,
            revision = 1L,
            fingerprintSha256 = prepared.fingerprintSha256,
            storageState = ClipboardStorageState.STAGING.name,
        )
        val items = prepared.items.map { item ->
            ClipboardItemEntity(
                entryId = prepared.id.value,
                itemIndex = item.itemIndex,
                mimeType = item.mimeType,
                textPayload = item.textPayload,
                htmlPayload = item.htmlPayload,
                blobId = item.stagedFile?.id,
                safeDisplayName = item.safeDisplayName,
                plainByteSize = item.plainByteSize,
            )
        }
        val finalized = ArrayList<String>()
        try {
            dao.insertEntryAndItems(entry, items)
            prepared.items.mapNotNull { it.stagedFile }.forEach { staged ->
                finalized += files.finalize(staged)
            }
            dao.updateState(prepared.id.value, ClipboardStorageState.READY.name)
            prepared.consume()
            enforceQuotas()
            StoreResult.Stored(prepared.id, duplicate = false)
        } catch (_: Exception) {
            finalized.forEach(files::delete)
            dao.deleteById(prepared.id.value)
            prepared.close()
            StoreResult.Failure(ClipboardFailure.DATABASE_UNAVAILABLE)
        }
    }

    override suspend fun load(id: ClipboardEntryId): LoadedClipboardEntry? {
        val loaded = dao.loadReady(id.value) ?: return null
        return LoadedClipboardEntry(
            summary(loaded.entry),
            loaded.items.map { item ->
                LoadedClipboardItem(
                    item.itemIndex,
                    item.mimeType,
                    item.textPayload,
                    item.htmlPayload,
                    item.blobId,
                    item.safeDisplayName,
                    item.plainByteSize,
                )
            },
        )
    }

    override suspend fun setPinned(id: ClipboardEntryId, pinned: Boolean, confirmImpact: Boolean): PinResult =
        mutations.withLock {
            val entry = dao.entryById(id.value) ?: return@withLock PinResult.NotFound
            if (entry.storageState != ClipboardStorageState.READY.name) return@withLock PinResult.NotFound
            if (!pinned && entry.isPinned && !confirmImpact) {
                val wouldExceedCount = dao.unpinnedCount() + 1 > ClipboardLimits.MAX_UNPINNED_ENTRIES
                val wouldExceedBytes = dao.unpinnedBytes() + entry.storedByteSize > ClipboardLimits.MAX_UNPINNED_BYTES
                if (wouldExceedCount || wouldExceedBytes) return@withLock PinResult.RequiresConfirmation
            }
            dao.setPinned(id.value, pinned)
            enforceQuotas()
            PinResult.Updated
        }

    override suspend fun markSensitive(id: ClipboardEntryId): Boolean = mutations.withLock {
        dao.markSensitive(id.value) > 0
    }

    override suspend fun delete(id: ClipboardEntryId): Boolean = mutations.withLock {
        deleteEntry(id.value)
    }

    override suspend fun clearAll() = mutations.withLock {
        dao.allReady().forEach { deleteEntry(it.id) }
        dao.nonReadyEntries().forEach { deleteEntry(it.id) }
        files.deletePartials()
    }

    override suspend fun search(query: String, generation: Long): SearchResult {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return SearchResult(generation, emptyList())
        val matches = ArrayList<ClipboardEntrySummary>()
        for (entry in dao.allReady().filterNot { it.isSensitive }) {
            val loaded = dao.loadReady(entry.id) ?: continue
            val hit = loaded.items.any { item ->
                item.textPayload?.lowercase()?.contains(needle) == true ||
                    item.safeDisplayName?.lowercase()?.contains(needle) == true
            }
            if (hit) matches += summary(entry)
        }
        return SearchResult(generation, matches)
    }

    override suspend fun reconcile() = mutations.withLock {
        files.deletePartials()
        dao.nonReadyEntries().forEach { deleteEntry(it.id) }
        val managed = dao.managedBlobIds().toSet()
        files.listFinalIds().filterNot(managed::contains).forEach(files::delete)
        dao.allReady().forEach { entry ->
            val loaded = dao.loadReady(entry.id)
            val missing = loaded == null || loaded.items.mapNotNull { it.blobId }.any { !files.finalFile(it).isFile }
            if (missing) deleteEntry(entry.id)
        }
        enforceQuotas()
    }

    private suspend fun enforceQuotas() {
        while (
            dao.unpinnedCount() > ClipboardLimits.MAX_UNPINNED_ENTRIES ||
            dao.unpinnedBytes() > ClipboardLimits.MAX_UNPINNED_BYTES
        ) {
            val victim = dao.oldestUnpinnedIds().firstOrNull() ?: break
            deleteEntry(victim)
        }
    }

    private suspend fun deleteEntry(id: String): Boolean {
        val loaded = dao.loadAny(id)
        if (loaded == null) return false
        dao.updateState(id, ClipboardStorageState.DELETING.name)
        loaded.items.mapNotNull { it.blobId }.forEach(files::delete)
        dao.deleteById(id)
        return true
    }

    private fun summary(entry: ClipboardEntryEntity) = ClipboardEntrySummary(
        id = ClipboardEntryId(entry.id),
        kind = ClipboardKind.valueOf(entry.kind),
        itemCount = entry.itemCount,
        isPinned = entry.isPinned,
        isSensitive = entry.isSensitive,
        storedByteSize = entry.storedByteSize,
        lastCopiedAt = entry.lastCopiedAt,
        revision = entry.revision,
    )
}
