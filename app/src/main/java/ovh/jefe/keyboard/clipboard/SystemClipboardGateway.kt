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
     * The platform timestamp for the copied source clip, when Android exposes one. A null value
     * means that a controller cannot distinguish a classification callback from a new copy.
     */
    val sourceTimestampMillis: Long? = null,
) {
    val mimeTypes: List<String> = immutableClipboardList(mimeTypes)
    val items: List<SystemClipItemSnapshot> = immutableClipboardList(items)

    fun hasSameKnownSource(other: SystemClipSnapshot): Boolean =
        sourceTimestampMillis != null && sourceTimestampMillis == other.sourceTimestampMillis

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

internal fun interface ClipboardSourceTimestampReader {
    fun sourceTimestampMillis(description: ClipDescription): Long?
}

internal class SystemClipboardGateway(
    private val clipboard: ClipboardManagerAccess,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val timestampReader: ClipboardSourceTimestampReader = PlatformClipboardSourceTimestampReader(),
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
        val itemCount = clip.itemCount
        val mimeTypeCount = description.mimeTypeCount
        val labelSource = description.label
        val extras = description.extras
        val sourceTimestampMillis = timestampReader.sourceTimestampMillis(description)
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
        val fields = ArrayList<CapturedItemFields>(itemCount)
        for (index in 0 until itemCount) {
            val item = clip.getItemAt(index)
            val text = item.text
            val htmlText = item.htmlText
            val boundedText = boundText(text, totalTextChars)
                ?: if (text != null) return ClipboardGatewayResult.Failure(ClipboardFailure.ENTRY_TOO_LARGE) else null
            totalTextChars += boundedText?.length ?: 0
            val boundedHtml = boundText(htmlText, totalTextChars)
                ?: if (htmlText != null) return ClipboardGatewayResult.Failure(ClipboardFailure.ENTRY_TOO_LARGE) else null
            totalTextChars += boundedHtml?.length ?: 0
            fields += CapturedItemFields(
                text = boundedText,
                htmlText = boundedHtml,
                uri = item.uri,
                hasIntent = item.intent != null,
            )
        }

        val items = ArrayList<SystemClipItemSnapshot>(itemCount)
        for (field in fields) {
            val uri = field.uri?.let(::copyUri)
                ?: if (field.uri != null) return ClipboardGatewayResult.Failure(ClipboardFailure.INVALID_METADATA) else null
            items += SystemClipItemSnapshot(
                text = field.text?.copy(),
                htmlText = field.htmlText?.copy(),
                uri = uri,
                hasIntent = field.hasIntent,
            )
        }

        val sensitive = extras?.getBoolean(SENSITIVE_EXTRA, false) == true
        return ClipboardGatewayResult.Captured(
            SystemClipSnapshot(
                capturedAtMillis = nowMillis(),
                label = label,
                mimeTypes = mimeTypes,
                isSensitive = sensitive,
                items = items,
                sourceTimestampMillis = sourceTimestampMillis,
            ),
        )
    }

    private fun boundText(value: CharSequence?, total: Long): BoundedCharSequence? {
        if (value == null) return null
        val length = value.length
        if (length < 0 || total > ClipboardLimits.MAX_SNAPSHOT_TEXT_CHARS - length) return null
        return BoundedCharSequence(value, length)
    }

    private fun copyUri(uri: Uri): Uri? {
        val serialized = uri.toString()
        if (serialized.length > ClipboardLimits.MAX_URI_CHARS) return null
        return Uri.parse(serialized)
    }

    private fun copyLabel(value: CharSequence): String? {
        val length = value.length
        if (length < 0) return null
        val copy = StringBuilder(minOf(length, ClipboardLimits.MAX_LABEL_CHARS * 2))
        var index = 0
        var codePoints = 0
        while (index < length) {
            if (codePoints == ClipboardLimits.MAX_LABEL_CHARS) return null
            val first = value[index]
            if (first.isHighSurrogate() && index + 1 < length && value[index + 1].isLowSurrogate()) {
                copy.append(first).append(value[index + 1])
                index += 2
            } else {
                copy.append(first)
                index += 1
            }
            codePoints += 1
        }
        return copy.toString()
    }

    private companion object {
        const val SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
    }

    private class CapturedItemFields(
        val text: BoundedCharSequence?,
        val htmlText: BoundedCharSequence?,
        val uri: Uri?,
        val hasIntent: Boolean,
    )

    private class BoundedCharSequence(
        private val source: CharSequence,
        val length: Int,
    ) {
        fun copy(): String {
            val copy = StringBuilder(length)
            for (index in 0 until length) {
                copy.append(source[index])
            }
            return copy.toString()
        }
    }
}

internal fun <T> immutableClipboardList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

internal class PlatformClipboardSourceTimestampReader(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ClipboardSourceTimestampReader {
    @SuppressLint("NewApi") // sdkInt is injected for tests and explicitly guarded below.
    override fun sourceTimestampMillis(description: ClipDescription): Long? =
        if (sdkInt >= Build.VERSION_CODES.O) description.timestamp.takeIf { it > 0L } else null
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
