package ovh.jefe.keyboard.clipboard

import android.net.Uri
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

internal class SourceMetadata(
    val mimeType: String,
    val displayName: String?,
    val byteSize: Long?,
) {
    override fun toString(): String = "SourceMetadata(redacted)"
}

internal interface ContentStreamSource {
    suspend fun metadata(uri: Uri): SourceMetadata
    suspend fun open(uri: Uri): InputStream
}

internal class PreparedClipboardItem(
    val itemIndex: Int,
    val mimeType: String,
    val textPayload: String?,
    val htmlPayload: String?,
    val stagedFile: StagedClipboardFile?,
    val safeDisplayName: String?,
    val plainByteSize: Long,
) {
    override fun toString(): String = "PreparedClipboardItem(index=$itemIndex, redacted=true)"
}

internal class PreparedClipboardEntry(
    val id: ClipboardEntryId,
    val createdAt: Long,
    val lastCopiedAt: Long,
    val kind: ClipboardKind,
    val isSensitive: Boolean,
    val fingerprintSha256: String,
    items: List<PreparedClipboardItem>,
    private val discardStaged: ((StagedClipboardFile) -> Unit)? = null,
) : Closeable {
    val items: List<PreparedClipboardItem> = immutableClipboardList(items.sortedBy { it.itemIndex })
    val storedByteSize: Long = items.sumOf { it.plainByteSize }
    private var consumed = false

    internal fun consume() {
        consumed = true
    }

    override fun close() {
        if (!consumed) items.mapNotNull { it.stagedFile }.forEach { discardStaged?.invoke(it) ?: it.file.delete() }
        consumed = true
    }

    override fun toString(): String = "PreparedClipboardEntry(kind=$kind, items=${items.size}, redacted=true)"
}

internal sealed interface PrepareResult {
    class Success(val entry: PreparedClipboardEntry) : PrepareResult
    class Failure(val failure: ClipboardFailure) : PrepareResult
}

internal class ClipboardIngestor(
    private val source: ContentStreamSource,
    private val files: ClipboardPrivateFileStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun prepare(
        snapshot: SystemClipSnapshot,
        decision: ClipboardPolicyDecision.Accept,
    ): PrepareResult = try {
        withTimeout(ClipboardLimits.INGEST_TIMEOUT_MILLIS) { prepareChecked(snapshot, decision) }
    } catch (_: TimeoutCancellationException) {
        files.deletePartials()
        PrepareResult.Failure(ClipboardFailure.TIMED_OUT)
    } catch (cancelled: CancellationException) {
        files.deletePartials()
        throw cancelled
    } catch (_: ClipboardLimitExceededException) {
        files.deletePartials()
        PrepareResult.Failure(ClipboardFailure.ENTRY_TOO_LARGE)
    } catch (_: SecurityException) {
        files.deletePartials()
        PrepareResult.Failure(ClipboardFailure.ACCESS_DENIED)
    } catch (_: IOException) {
        files.deletePartials()
        PrepareResult.Failure(ClipboardFailure.ACCESS_DENIED)
    } catch (_: RuntimeException) {
        files.deletePartials()
        PrepareResult.Failure(ClipboardFailure.INVALID_METADATA)
    }

    private suspend fun prepareChecked(
        snapshot: SystemClipSnapshot,
        decision: ClipboardPolicyDecision.Accept,
    ): PrepareResult {
        val digest = MessageDigest.getInstance("SHA-256")
        val preparedItems = ArrayList<PreparedClipboardItem>(decision.items.size)
        var totalBytes = 0L
        try {
            for (accepted in decision.items.sortedBy { it.itemIndex }) {
                val sourceItem = snapshot.items[accepted.itemIndex]
                val prepared = if (sourceItem.uri != null) {
                    prepareUri(accepted.itemIndex, sourceItem.uri, accepted.candidateMimeTypes, digest, totalBytes)
                } else {
                    prepareText(accepted.itemIndex, sourceItem, digest)
                }
                if (prepared.plainByteSize > ClipboardLimits.MAX_ENTRY_BYTES - totalBytes) {
                    throw ClipboardLimitExceededException()
                }
                totalBytes += prepared.plainByteSize
                preparedItems += prepared
            }
        } catch (failure: Throwable) {
            preparedItems.mapNotNull { it.stagedFile }.forEach(files::discard)
            throw failure
        }
        val timestamp = clock()
        return PrepareResult.Success(
            PreparedClipboardEntry(
                id = ClipboardEntryId(UUID.randomUUID().toString()),
                createdAt = timestamp,
                lastCopiedAt = timestamp,
                kind = decision.kind,
                isSensitive = decision.isSensitive,
                fingerprintSha256 = digest.digest().joinToString("") { "%02x".format(it) },
                items = preparedItems,
                discardStaged = { files.discard(it) },
            ),
        )
    }

    private fun prepareText(
        index: Int,
        item: SystemClipItemSnapshot,
        digest: MessageDigest,
    ): PreparedClipboardItem {
        val text = item.text
        val html = item.htmlText
        val textBytes = text?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val htmlBytes = html?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        digestPart(digest, index, "text/plain", "text", textBytes)
        if (html != null) digestPart(digest, index, "text/html", "html", htmlBytes)
        return PreparedClipboardItem(
            itemIndex = index,
            mimeType = if (html != null) "text/html" else "text/plain",
            textPayload = text,
            htmlPayload = html,
            stagedFile = null,
            safeDisplayName = null,
            plainByteSize = textBytes.size.toLong() + htmlBytes.size,
        )
    }

    private suspend fun prepareUri(
        index: Int,
        uri: Uri,
        candidates: List<String>,
        digest: MessageDigest,
        alreadyStored: Long,
    ): PreparedClipboardItem {
        val metadata = source.metadata(uri)
        val mime = normalizeMime(metadata.mimeType)
            ?: throw IllegalArgumentException("Invalid MIME")
        if (!matchesAny(mime, candidates)) throw IllegalArgumentException("Unexpected MIME")
        val announced = metadata.byteSize
        if (announced != null && (announced < 0 || announced > ClipboardLimits.MAX_ENTRY_BYTES - alreadyStored)) {
            throw ClipboardLimitExceededException()
        }
        val staged = files.createStaged(metadata.displayName)
        var copied = 0L
        try {
            files.openForWrite(staged, ClipboardLimits.MAX_ENTRY_BYTES - alreadyStored).use { output ->
                source.open(uri).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                    }
                }
            }
        } catch (failure: Throwable) {
            files.discard(staged)
            throw failure
        }
        digest.update(ByteBuffer.allocate(8).putLong(index.toLong()).array())
        digest.update(mime.toByteArray(Charsets.US_ASCII))
        digest.update(ByteBuffer.allocate(8).putLong(copied).array())
        return PreparedClipboardItem(
            itemIndex = index,
            mimeType = mime,
            textPayload = null,
            htmlPayload = null,
            stagedFile = staged,
            safeDisplayName = sanitizeName(metadata.displayName),
            plainByteSize = copied,
        )
    }

    private fun digestPart(
        digest: MessageDigest,
        index: Int,
        mime: String,
        role: String,
        bytes: ByteArray,
    ) {
        digest.update(ByteBuffer.allocate(8).putLong(index.toLong()).array())
        digest.update(role.toByteArray(Charsets.US_ASCII))
        digest.update(mime.toByteArray(Charsets.US_ASCII))
        digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
        digest.update(bytes)
    }

    private fun normalizeMime(value: String): String? {
        if (value.isBlank() || value.length > ClipboardLimits.MAX_MIME_CHARS) return null
        if (value.any { it.code !in 0x20..0x7e }) return null
        return value.lowercase(Locale.ROOT)
    }

    private fun matchesAny(mime: String, candidates: List<String>): Boolean = candidates.any { candidate ->
        candidate == mime || candidate == "*/*" ||
            candidate.endsWith("/*") && mime.startsWith(candidate.substringBefore('/').plus('/'))
    }

    private fun sanitizeName(value: String?): String? = value
        ?.asSequence()
        ?.filterNot { it.isISOControl() }
        ?.take(ClipboardLimits.MAX_LABEL_CHARS)
        ?.joinToString("")
        ?.takeIf { it.isNotBlank() }
}
