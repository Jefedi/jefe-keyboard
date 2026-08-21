package ovh.jefe.keyboard.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean

internal class SystemClipSnapshot(
    val capturedAtMillis: Long,
    val label: String?,
    val mimeTypes: List<String>,
    val isSensitive: Boolean,
    val items: List<SystemClipItemSnapshot>,
) {
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

internal class SystemClipboardGateway(
    private val clipboard: ClipboardManagerAccess,
    private val nowMillis: () -> Long = System::currentTimeMillis,
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
            totalTextChars = checkedTextLength(totalTextChars, text) ?: return ClipboardGatewayResult.Failure(ClipboardFailure.ENTRY_TOO_LARGE)
            totalTextChars = checkedTextLength(totalTextChars, htmlText) ?: return ClipboardGatewayResult.Failure(ClipboardFailure.ENTRY_TOO_LARGE)
            fields += CapturedItemFields(
                text = text,
                htmlText = htmlText,
                uri = item.uri,
                hasIntent = item.intent != null,
            )
        }

        val items = ArrayList<SystemClipItemSnapshot>(itemCount)
        for (field in fields) {
            val uri = field.uri?.let(::copyUri)
                ?: if (field.uri != null) return ClipboardGatewayResult.Failure(ClipboardFailure.INVALID_METADATA) else null
            items += SystemClipItemSnapshot(
                text = field.text?.let(::copyText),
                htmlText = field.htmlText?.let(::copyText),
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
            ),
        )
    }

    private fun checkedTextLength(total: Long, value: CharSequence?): Long? {
        if (value == null) return total
        val length = value.length.toLong()
        val next = total + length
        return next.takeIf { it >= total && it <= ClipboardLimits.MAX_SNAPSHOT_TEXT_CHARS.toLong() }
    }

    private fun copyText(value: CharSequence): String = StringBuilder(value.length).append(value).toString()

    private fun copyUri(uri: Uri): Uri? {
        val serialized = uri.toString()
        if (serialized.length > ClipboardLimits.MAX_URI_CHARS) return null
        return Uri.parse(serialized)
    }

    private fun copyLabel(value: CharSequence): String? {
        val copy = StringBuilder(minOf(value.length, ClipboardLimits.MAX_LABEL_CHARS))
        var index = 0
        var codePoints = 0
        while (index < value.length) {
            if (codePoints == ClipboardLimits.MAX_LABEL_CHARS) return null
            val first = value[index]
            if (first.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate()) {
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
        val text: CharSequence?,
        val htmlText: String?,
        val uri: Uri?,
        val hasIntent: Boolean,
    )
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
