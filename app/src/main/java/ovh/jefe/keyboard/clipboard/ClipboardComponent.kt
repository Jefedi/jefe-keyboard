package ovh.jefe.keyboard.clipboard

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.Room
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

internal class ClipboardComponent private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val database = Room.databaseBuilder(
        applicationContext,
        ClipboardDatabase::class.java,
        "clipboard-history.db",
    ).build()
    val repository: ClipboardRepository
    val controller: ClipboardHistoryController

    init {
        val files = ClipboardPrivateFileStore(applicationContext)
        repository = RoomClipboardRepository(database.clipboardDao(), files)
        val ingestor = ClipboardIngestor(ContentResolverStreamSource(applicationContext.contentResolver), files)
        controller = ClipboardHistoryController(
            gateway = SystemClipboardGateway(applicationContext),
            pipeline = DefaultClipboardCapturePipeline(ingestor, repository),
            repository = repository,
            activationStore = SharedPreferencesClipboardActivationStore(applicationContext),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        controller.start()
    }

    companion object {
        @Volatile private var instance: ClipboardComponent? = null
        fun get(context: Context): ClipboardComponent = instance ?: synchronized(this) {
            instance ?: ClipboardComponent(context).also { instance = it }
        }
    }
}

private class SharedPreferencesClipboardActivationStore(context: Context) : ClipboardActivationStore {
    private val preferences = context.getSharedPreferences("clipboard-history-state", Context.MODE_PRIVATE)
    override fun activation(): ClipboardActivation = runCatching {
        ClipboardActivation.valueOf(preferences.getString("activation", null) ?: ClipboardActivation.DISABLED.name)
    }.getOrDefault(ClipboardActivation.DISABLED)

    override fun writeActivation(value: ClipboardActivation): Boolean =
        preferences.edit().putString("activation", value.name).commit()

    override fun suppression(): ClipboardSuppressionState {
        if (!preferences.getBoolean("suppressed", false)) return ClipboardSuppressionState.NotSuppressed
        return when (preferences.getString("marker_kind", null)) {
            "legacy" -> ClipboardSuppressionState.Suppressed(ClipboardSourceMarker.LegacyListenerEvent)
            "timestamp" -> ClipboardSuppressionState.Suppressed(
                ClipboardSourceMarker.PlatformTimestamp(preferences.getLong("marker_value", 0L)),
            )
            else -> ClipboardSuppressionState.Suppressed(ClipboardSourceMarker.TimestampUnavailable)
        }
    }

    override fun writeSuppression(value: ClipboardSuppressionState): Boolean {
        val editor = preferences.edit().clear().putString("activation", activation().name)
        when (value) {
            ClipboardSuppressionState.NotSuppressed -> editor.putBoolean("suppressed", false)
            is ClipboardSuppressionState.Suppressed -> {
                editor.putBoolean("suppressed", true)
                when (val marker = value.marker) {
                    ClipboardSourceMarker.LegacyListenerEvent -> editor.putString("marker_kind", "legacy")
                    ClipboardSourceMarker.TimestampUnavailable -> editor.putString("marker_kind", "unknown")
                    is ClipboardSourceMarker.PlatformTimestamp -> editor
                        .putString("marker_kind", "timestamp")
                        .putLong("marker_value", marker.valueMillis)
                }
            }
        }
        return editor.commit()
    }
}

private class ContentResolverStreamSource(
    private val resolver: ContentResolver,
) : ContentStreamSource {
    override suspend fun metadata(uri: Uri): SourceMetadata = withContext(Dispatchers.IO) {
        var name: String? = null
        var size: Long? = null
        val cursor: Cursor? = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0 && !it.isNull(nameIndex)) name = it.getString(nameIndex)
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        SourceMetadata(
            mimeType = resolver.getType(uri) ?: "application/octet-stream",
            displayName = name,
            byteSize = size,
        )
    }

    override suspend fun open(uri: Uri): InputStream = withContext(Dispatchers.IO) {
        resolver.openInputStream(uri) ?: throw FileNotFoundException("Clipboard content unavailable")
    }
}
