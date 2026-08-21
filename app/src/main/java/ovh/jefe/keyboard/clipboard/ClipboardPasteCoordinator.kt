package ovh.jefe.keyboard.clipboard

import android.content.ClipDescription
import android.os.Bundle
import android.text.Html
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat

internal data class EditorTarget(
    val sessionId: Long,
    val uid: Int,
    val packageName: String,
    val inputConnection: InputConnection,
    val editorInfo: EditorInfo,
)

internal sealed interface ClipboardPasteResult {
    data class Success(val sensitive: Boolean) : ClipboardPasteResult
    data class Failure(val failure: ClipboardFailure) : ClipboardPasteResult
}

internal class ClipboardPasteCoordinator(
    private val repository: ClipboardRepository,
    private val currentTarget: () -> EditorTarget?,
    private val grants: ClipboardGrantRegistry,
) {
    suspend fun pasteEntry(
        id: ClipboardEntryId,
        requestedTarget: EditorTarget,
        itemIndex: Int = 0,
    ): ClipboardPasteResult {
        val loaded = repository.load(id) ?: return ClipboardPasteResult.Failure(ClipboardFailure.CORRUPT_ENTRY)
        loaded.use { entry ->
            val item = entry.items.firstOrNull { it.itemIndex == itemIndex }
                ?: return ClipboardPasteResult.Failure(ClipboardFailure.CORRUPT_ENTRY)
            if (!isCurrent(requestedTarget)) return ClipboardPasteResult.Failure(ClipboardFailure.EDITOR_REJECTED)
            val accepted = when {
                item.htmlPayload != null -> commitHtml(requestedTarget, item)
                item.textPayload != null && item.textPayload.toByteArray(Charsets.UTF_8).size <=
                    ClipboardLimits.MAX_DIRECT_COMMIT_TEXT_UTF8_BYTES ->
                    requestedTarget.inputConnection.commitText(item.textPayload, 1)
                else -> commitContent(requestedTarget, entry, item)
            }
            return if (accepted) ClipboardPasteResult.Success(entry.summary.isSensitive)
            else ClipboardPasteResult.Failure(ClipboardFailure.EDITOR_REJECTED)
        }
    }

    private fun commitHtml(target: EditorTarget, item: LoadedClipboardItem): Boolean {
        if (!isCurrent(target)) return false
        val rich = Html.fromHtml(item.htmlPayload.orEmpty(), Html.FROM_HTML_MODE_LEGACY)
        return target.inputConnection.commitText(rich, 1)
    }

    private fun commitContent(
        target: EditorTarget,
        entry: LoadedClipboardEntry,
        item: LoadedClipboardItem,
    ): Boolean {
        if (!isCurrent(target)) return false
        val supported = EditorInfoCompat.getContentMimeTypes(target.editorInfo)
        if (supported.none { ClipDescription.compareMimeTypes(item.mimeType, it) }) return false
        val grant = grants.issue(
            ClipboardGrantPayload(
                entry.summary.id.value,
                item.itemIndex,
                item.mimeType,
                item.plainByteSize,
                entry.summary.isSensitive,
            ),
            target.uid,
            target.packageName,
            target.sessionId,
        )
        if (!isCurrent(target)) {
            grants.revokeToken(grant.token)
            return false
        }
        val info = InputContentInfoCompat(
            grant.uri,
            ClipDescription(if (entry.summary.isSensitive) "Contenu sensible" else "Presse-papiers", arrayOf(item.mimeType)),
            null,
        )
        val accepted = InputConnectionCompat.commitContent(
            target.inputConnection,
            target.editorInfo,
            info,
            InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
            Bundle(),
        )
        if (!accepted) grants.revokeToken(grant.token)
        return accepted
    }

    private fun isCurrent(requested: EditorTarget): Boolean {
        val current = currentTarget() ?: return false
        return current.sessionId == requested.sessionId &&
            current.uid == requested.uid &&
            current.packageName == requested.packageName &&
            current.inputConnection === requested.inputConnection
    }
}
