package ovh.jefe.keyboard.clipboard

import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import androidx.room.RoomDatabase

@Entity(
    tableName = "clipboard_entries",
    indices = [
        Index(value = ["storageState", "lastCopiedAt"]),
        Index(value = ["fingerprintSha256"]),
    ],
)
internal class ClipboardEntryEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val lastCopiedAt: Long,
    val kind: String,
    val itemCount: Int,
    val isPinned: Boolean,
    val isSensitive: Boolean,
    val storedByteSize: Long,
    val revision: Long,
    val fingerprintSha256: String,
    val storageState: String,
) {
    override fun toString(): String =
        "ClipboardEntryEntity(id=<redacted>, kind=$kind, items=$itemCount, sensitive=$isSensitive)"
}

@Entity(
    tableName = "clipboard_items",
    primaryKeys = ["entryId", "itemIndex"],
    foreignKeys = [
        ForeignKey(
            entity = ClipboardEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["entryId"])],
)
internal class ClipboardItemEntity(
    val entryId: String,
    val itemIndex: Int,
    val mimeType: String,
    val textPayload: String?,
    val htmlPayload: String?,
    val blobId: String?,
    val safeDisplayName: String?,
    val plainByteSize: Long,
) {
    override fun toString(): String = "ClipboardItemEntity(redacted)"
}

internal class ClipboardEntryWithItems {
    @Embedded lateinit var entry: ClipboardEntryEntity

    @Relation(parentColumn = "id", entityColumn = "entryId")
    lateinit var unorderedItems: List<ClipboardItemEntity>

    val items: List<ClipboardItemEntity>
        get() = unorderedItems.sortedBy(ClipboardItemEntity::itemIndex)

    override fun toString(): String = "ClipboardEntryWithItems(redacted)"
}

@Database(
    entities = [ClipboardEntryEntity::class, ClipboardItemEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class ClipboardDatabase : RoomDatabase() {
    abstract fun clipboardDao(): ClipboardDao
}
