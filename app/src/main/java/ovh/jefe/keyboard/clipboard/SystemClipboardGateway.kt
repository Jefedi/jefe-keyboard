package ovh.jefe.keyboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.annotation.SuppressLint
import android.os.Build
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

internal class SystemClipSnapshot(
    val capturedAtMillis: Long,
    val label: String?,
    mimeTypes: List<String>,
    val isSensitive: Boolean,
    items: List<SystemClipItemSnapshot>,
    /**
     * Evidence a controller may persist while suppressing the current primary clip. It is never a
     * Boolean identity: equal timestamps can collide, and absent evidence must fail closed.
     */
    val sourceMarker: ClipboardSourceMarker = ClipboardSourceMarker.TimestampUnavailable,
) {
    val mimeTypes: List<String> = immutableClipboardList(mimeTypes)
    val items: List<SystemClipItemSnapshot> = immutableClipboardList(items)

    override fun toString(): String =
        "SystemClipSnapshot(items=${items.size}, sensitive=$isSensitive)"
}

internal class SystemClipItemSnapshot(
    val text: String?,
    val htmlText: String?,
    val uri: Uri?,
    val hasIntent: Boolean,
    val isSensitive: Boolean = false,
) {
    override fun toString(): String = "SystemClipItemSnapshot(redacted)"
}

internal sealed interface ClipboardGatewayResult {
    data object Empty : ClipboardGatewayResult

    class Captured(val snapshot: SystemClipSnapshot) : ClipboardGatewayResult {
        override fun toString(): String = "ClipboardGatewayResult.Captured(redacted)"
    }

    class Failure(val failure: ClipboardFailure) : ClipboardGatewayResult {
        override fun toString(): String = "ClipboardGatewayResult.Failure($failure)"
    }
}

internal interface ClipboardManagerAccess {
    fun primaryClip(): ClipData?
    fun addListener(listener: ClipboardManager.OnPrimaryClipChangedListener)
    fun removeListener(listener: ClipboardManager.OnPrimaryClipChangedListener)
}

internal sealed interface ClipboardSourceMarker {
    /** API 24–30 listeners are documented to signal a new primary clip. */
    data object LegacyListenerEvent : ClipboardSourceMarker

    /** API 31+ carries the copied source timestamp, which can still collide. */
    data class PlatformTimestamp(val valueMillis: Long) : ClipboardSourceMarker

    /** API 31+ did not provide a usable timestamp; suppression must remain fail-closed. */
    data object TimestampUnavailable : ClipboardSourceMarker
}

internal enum class ClipboardSourceChange {
    DEFINITELY_CHANGED,
    SAME_OR_COLLIDING,
    UNKNOWN,
}

/**
 * Returns only evidence a Task 7 listener may use after it has captured the current clip. A
 * timestamp comparison is deliberately not an equality identity: matching values can represent
 * either the same copied source or distinct copies in one millisecond.
 */
internal fun compareClipboardSource(
    previous: ClipboardSourceMarker?,
    current: ClipboardSourceMarker?,
): ClipboardSourceChange = when {
    previous is ClipboardSourceMarker.LegacyListenerEvent && current is ClipboardSourceMarker.LegacyListenerEvent ->
        ClipboardSourceChange.DEFINITELY_CHANGED
    previous is ClipboardSourceMarker.PlatformTimestamp && current is ClipboardSourceMarker.PlatformTimestamp ->
        if (previous.valueMillis == current.valueMillis) {
            ClipboardSourceChange.SAME_OR_COLLIDING
        } else {
            ClipboardSourceChange.DEFINITELY_CHANGED
        }
    else -> ClipboardSourceChange.UNKNOWN
}

internal fun interface ClipboardSourceMarkerReader {
    fun sourceMarker(description: ClipDescription): ClipboardSourceMarker
}

internal class SystemClipboardGateway(
    private val clipboard: ClipboardManagerAccess,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sourceMarkerReader: ClipboardSourceMarkerReader = PlatformClipboardSourceMarkerReader(),
) {
    constructor(context: Context) : this(
        AndroidClipboardManagerAccess(requireNotNull(context.getSystemService(ClipboardManager::class.java))),
    )

    private val isListening = AtomicBoolean(false)
    private var onPrimaryClipChanged: (() -> Unit)? = null
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        onPrimaryClipChanged?.invoke()
    }

    fun capturePrimaryClip(): ClipboardGatewayResult = try {
        val clip = clipboard.primaryClip() ?: return ClipboardGatewayResult.Empty
        capture(clip)
    } catch (_: SecurityException) {
        ClipboardGatewayResult.Failure(ClipboardFailure.ACCESS_DENIED)
    } catch (_: RuntimeException) {
        ClipboardGatewayResult.Failure(ClipboardFailure.ACCESS_DENIED)
    }

    fun startListening(callback: () -> Unit) {
        onPrimaryClipChanged = callback
        if (isListening.compareAndSet(false, true)) {
            try {
                clipboard.addListener(listener)
            } catch (_: SecurityException) {
                isListening.set(false)
            } catch (_: RuntimeException) {
                isListening.set(false)
            }
        }
    }

    fun stopListening() {
        onPrimaryClipChanged = null
        if (isListening.compareAndSet(true, false)) {
            try {
                clipboard.removeListener(listener)
            } catch (_: SecurityException) {
                // No clipboard data or provider metadata is available here.
            } catch (_: RuntimeException) {
                // The caller can safely retry stopping this idempotent listener.
            }
        }
    }

    private fun capture(clip: ClipData): ClipboardGatewayResult {
        val description = clip.description
        val sensitive = description.extras?.getBoolean(SENSITIVE_EXTRA, false) == true
        val sourceMarker = sourceMarkerReader.sourceMarker(description)
        val itemCount = clip.itemCount
        val mimeTypeCount = description.mimeTypeCount
        val labelSource = description.label
        if (itemCount == 0) return ClipboardGatewayResult.Empty
        if (itemCount > ClipboardLimits.MAX_GROUP_ITEMS) {
            return ClipboardGatewayResult.Failure(ClipboardFailure.TOO_MANY_ITEMS)
        }
        if (mimeTypeCount > ClipboardLimits.MAX_MIME_TYPES) {
            return ClipboardGatewayResult.Failure(ClipboardFailure.INVALID_METADATA)
        }

        val label = if (labelSource == null) {
            null
        } else {
            copyLabel(labelSource) ?: return ClipboardGatewayResult.Failure(ClipboardFailure.INVALID_METADATA)
        }
        val mimeTypes = ArrayList<String>(mimeTypeCount)
        for (index in 0 until mimeTypeCount) {
            val mimeType = description.getMimeType(index)
                ?: return ClipboardGatewayResult.Failure(ClipboardFailure.INVALID_METADATA)
            if (mimeType.length > ClipboardLimits.MAX_MIME_CHARS) {
                return ClipboardGatewayResult.Failure(ClipboardFailure.INVALID_METADATA)
            }
            mimeTypes += String(mimeType.toCharArray())
        }

        var totalTextChars = 0L
        val items = ArrayList<SystemClipItemSnapshot>(itemCount)
        for (index in 0 until itemCount) {
            val item = clip.getItemAt(index)
            val textSource = item.text
            val text = textSource?.let { copyBoundedText(it, totalTextChars) }
                ?: if (textSource != null) return ClipboardGatewayResult.Failure(ClipboardFailure.ENTRY_TOO_LARGE) else null
            totalTextChars += text?.length ?: 0
            val htmlSource = item.htmlText
            val htmlText = htmlSource?.let { copyBoundedText(it, totalTextChars) }
                ?: if (htmlSource != null) return ClipboardGatewayResult.Failure(ClipboardFailure.ENTRY_TOO_LARGE) else null
            totalTextChars += htmlText?.length ?: 0
            val uriSource = item.uri
            val uri = uriSource?.let(::copyUri)
                ?: if (uriSource != null) return ClipboardGatewayResult.Failure(ClipboardFailure.INVALID_METADATA) else null
            items += SystemClipItemSnapshot(
                text = text,
                htmlText = htmlText,
                uri = uri,
                hasIntent = item.intent != null,
            )
        }

        return ClipboardGatewayResult.Captured(
            SystemClipSnapshot(
                capturedAtMillis = nowMillis(),
                label = label,
                mimeTypes = mimeTypes,
                isSensitive = sensitive,
                items = items,
                sourceMarker = sourceMarker,
            ),
        )
    }

    private fun copyBoundedText(value: CharSequence, total: Long): String? {
        val length = value.length
        if (length < 0 || total > ClipboardLimits.MAX_SNAPSHOT_TEXT_CHARS - length) return null
        return String(copyUtf16Units(value, length))
    }

    private fun copyUri(uri: Uri): Uri? {
        val serialized = uri.toString()
        if (serialized.length > ClipboardLimits.MAX_URI_CHARS) return null
        return Uri.parse(serialized)
    }

    private fun copyLabel(value: CharSequence): String? {
        val length = value.length
        if (length < 0 || length > ClipboardLimits.MAX_LABEL_CHARS * 2) return null
        val units = copyUtf16Units(value, length)
        var index = 0
        var codePoints = 0
        while (index < units.size) {
            if (codePoints == ClipboardLimits.MAX_LABEL_CHARS) return null
            val first = units[index]
            if (first.isHighSurrogate() && index + 1 < units.size && units[index + 1].isLowSurrogate()) {
                index += 2
            } else {
                index += 1
            }
            codePoints += 1
        }
        return String(units)
    }

    private fun copyUtf16Units(value: CharSequence, length: Int): CharArray = CharArray(length) { index -> value[index] }

    private companion object {
        const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
    }

}

internal fun <T> immutableClipboardList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal class PlatformClipboardSourceMarkerReader(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ClipboardSourceMarkerReader {
    @SuppressLint("NewApi") // sdkInt is injected for tests and explicitly guarded below.
    override fun sourceMarker(description: ClipDescription): ClipboardSourceMarker = when {
        sdkInt in Build.VERSION_CODES.N..Build.VERSION_CODES.R -> ClipboardSourceMarker.LegacyListenerEvent
        sdkInt >= Build.VERSION_CODES.S -> description.timestamp
            .takeIf { it > 0L }
            ?.let(ClipboardSourceMarker::PlatformTimestamp)
            ?: ClipboardSourceMarker.TimestampUnavailable
        else -> ClipboardSourceMarker.TimestampUnavailable
    }
}

private class AndroidClipboardManagerAccess(
    private val clipboardManager: ClipboardManager,
) : ClipboardManagerAccess {
    override fun primaryClip(): ClipData? = clipboardManager.primaryClip

    override fun addListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        clipboardManager.addPrimaryClipChangedListener(listener)
    }

    override fun removeListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
        clipboardManager.removePrimaryClipChangedListener(listener)
    }
}
