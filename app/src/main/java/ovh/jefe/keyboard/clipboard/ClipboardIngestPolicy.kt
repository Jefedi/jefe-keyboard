package ovh.jefe.keyboard.clipboard

import android.net.Uri
import java.util.Locale

internal sealed interface ClipboardPolicyDecision {
    class Accept(
        val kind: ClipboardKind,
        val isSensitive: Boolean,
        val items: List<AcceptedClipboardItem>,
    ) : ClipboardPolicyDecision {
        override fun toString(): String = "ClipboardPolicyDecision.Accept(kind=$kind, items=${items.size}, redacted=true)"
    }

    class Reject(val failure: ClipboardFailure) : ClipboardPolicyDecision {
        override fun toString(): String = "ClipboardPolicyDecision.Reject($failure)"
    }
}

internal data class AcceptedClipboardItem(
    val itemIndex: Int,
    val candidateMimeTypes: List<String>,
)

internal object ClipboardIngestPolicy {
    fun evaluate(snapshot: SystemClipSnapshot, privateEditor: Boolean): ClipboardPolicyDecision = try {
        evaluateChecked(snapshot, privateEditor)
    } catch (_: RuntimeException) {
        ClipboardPolicyDecision.Reject(ClipboardFailure.INVALID_METADATA)
    }

    private fun evaluateChecked(snapshot: SystemClipSnapshot, privateEditor: Boolean): ClipboardPolicyDecision {
        if (snapshot.items.isEmpty()) return ClipboardPolicyDecision.Reject(ClipboardFailure.EMPTY)
        if (snapshot.items.size > ClipboardLimits.MAX_GROUP_ITEMS) {
            return ClipboardPolicyDecision.Reject(ClipboardFailure.TOO_MANY_ITEMS)
        }
        if (snapshot.mimeTypes.size > ClipboardLimits.MAX_MIME_TYPES || !validLabel(snapshot.label)) {
            return ClipboardPolicyDecision.Reject(ClipboardFailure.INVALID_METADATA)
        }
        val mimeTypes = normalizeMimeTypes(snapshot.mimeTypes)
            ?: return ClipboardPolicyDecision.Reject(ClipboardFailure.INVALID_METADATA)
        if (snapshot.items.any { !validUri(it.uri) }) {
            return ClipboardPolicyDecision.Reject(ClipboardFailure.INVALID_METADATA)
        }
        if (snapshot.items.any { it.uri?.scheme.equals("file", ignoreCase = true) }) {
            return ClipboardPolicyDecision.Reject(ClipboardFailure.UNSUPPORTED)
        }
        if (snapshot.items.all { it.text == null && it.htmlText == null && it.uri == null }) {
            return ClipboardPolicyDecision.Reject(ClipboardFailure.UNSUPPORTED)
        }

        val kind = if (snapshot.items.size > 1) {
            ClipboardKind.GROUP
        } else {
            classifySingle(snapshot.items.single(), mimeTypes)
                ?: return ClipboardPolicyDecision.Reject(ClipboardFailure.UNSUPPORTED)
        }
        return ClipboardPolicyDecision.Accept(
            kind = kind,
            isSensitive = snapshot.isSensitive || privateEditor || snapshot.items.any { it.isSensitive },
            items = snapshot.items.mapIndexed { index, item ->
                AcceptedClipboardItem(index, candidateMimeTypesFor(item, mimeTypes))
            },
        )
    }

    private fun normalizeMimeTypes(source: List<String>): List<String>? {
        val normalized = LinkedHashSet<String>(source.size)
        for (mimeType in source) {
            if (mimeType.length > ClipboardLimits.MAX_MIME_CHARS || mimeType.any { it.code !in 0x20..0x7e }) {
                return null
            }
            normalized += mimeType.lowercase(Locale.ROOT)
        }
        return normalized.toList()
    }

    private fun validLabel(label: String?): Boolean =
        label == null || label.codePointCount(0, label.length) <= ClipboardLimits.MAX_LABEL_CHARS

    private fun validUri(uri: Uri?): Boolean =
        uri == null || uri.toString().length <= ClipboardLimits.MAX_URI_CHARS

    private fun classifySingle(item: SystemClipItemSnapshot, mimeTypes: List<String>): ClipboardKind? = when {
        item.htmlText != null -> ClipboardKind.HTML
        item.uri?.scheme.equals("content", ignoreCase = true) -> when {
            mimeTypes.any { it.startsWith("image/") } -> ClipboardKind.IMAGE
            mimeTypes.any { it.startsWith("video/") } -> ClipboardKind.VIDEO
            mimeTypes.any { it.startsWith("audio/") } -> ClipboardKind.AUDIO
            else -> ClipboardKind.FILE
        }
        item.text != null -> if (isLink(item.text)) ClipboardKind.LINK else ClipboardKind.TEXT
        else -> null
    }

    private fun candidateMimeTypesFor(item: SystemClipItemSnapshot, mimeTypes: List<String>): List<String> = when {
        item.htmlText != null -> mimeTypes.filter { it == "text/html" || it == "text/*" || it == "*/*" }
        item.uri?.scheme.equals("content", ignoreCase = true) -> mimeTypes
        item.text != null -> mimeTypes.filter { it == "text/plain" || it == "text/*" || it == "*/*" }
        else -> emptyList()
    }

    private fun isLink(text: String): Boolean = when (Uri.parse(text).scheme?.lowercase(Locale.ROOT)) {
        "http", "https", "mailto", "tel" -> true
        else -> false
    }
}
