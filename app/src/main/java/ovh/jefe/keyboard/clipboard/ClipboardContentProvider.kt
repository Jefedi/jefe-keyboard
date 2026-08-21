package ovh.jefe.keyboard.clipboard

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.FileNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

internal class ClipboardContentProvider : ContentProvider() {
    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = withLease(uri) { it.payload.mimeType }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = withLease(uri) { lease ->
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        MatrixCursor(columns).apply {
            val row = newRow()
            columns.forEach { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> row.add(if (lease.payload.sensitive) "Contenu sensible" else "Presse-papiers")
                    OpenableColumns.SIZE -> row.add(lease.payload.plainByteSize)
                    else -> row.add(null)
                }
            }
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        val lease = acquireLease(uri) ?: throw FileNotFoundException("Clipboard grant unavailable")
        val pipe = ParcelFileDescriptor.createPipe()
        writerScope.launch {
            lease.use {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    if (lease.isRevoked) return@use
                    val component = ClipboardComponent.get(requireNotNull(context))
                    val loaded = component.repository.load(ClipboardEntryId(lease.payload.entryId)) ?: return@use
                    loaded.use { entry ->
                        val item = entry.items.firstOrNull { it.itemIndex == lease.payload.itemIndex } ?: return@use
                        when {
                            item.blobId != null -> component.payloadFiles.openFinal(item.blobId).use { it.copyTo(output) }
                            item.textPayload != null -> output.write(item.textPayload.toByteArray(Charsets.UTF_8))
                            else -> return@use
                        }
                    }
                }
            }
        }
        return pipe[0]
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private fun <T> withLease(uri: Uri, block: (ClipboardGrantLease) -> T): T? {
        val lease = acquireLease(uri) ?: return null
        return lease.use(block)
    }

    private fun acquireLease(uri: Uri): ClipboardGrantLease? {
        val token = uri.pathSegments.singleOrNull() ?: return null
        val component = ClipboardComponent.get(requireNotNull(context))
        val uid = Binder.getCallingUid()
        val packages = requireNotNull(context).packageManager.getPackagesForUid(uid).orEmpty()
        val sessionId = component.editorSessions.currentSessionId() ?: return null
        for (packageName in packages) {
            component.grants.acquire(token, uid, packageName, sessionId)?.let { return it }
        }
        return null
    }
}

internal class EditorSessionRegistry {
    @Volatile private var sessionId: Long? = null
    fun update(value: Long) { sessionId = value }
    fun clear(value: Long) { if (sessionId == value) sessionId = null }
    fun currentSessionId(): Long? = sessionId
}
