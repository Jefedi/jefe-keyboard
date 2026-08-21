package ovh.jefe.keyboard.clipboard

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

internal class StagedClipboardFile internal constructor(
    internal val id: String,
    internal val file: File,
) {
    override fun toString(): String = "StagedClipboardFile(redacted)"
}

internal class ClipboardPrivateFileStore(context: Context) {
    private val root = context.noBackupFilesDir.resolve("clipboard").canonicalFile.apply {
        if (!exists() && !mkdirs()) throw IOException("Private clipboard storage unavailable")
        if (!isDirectory) throw IOException("Private clipboard storage unavailable")
    }

    fun createStaged(@Suppress("UNUSED_PARAMETER") providerName: String? = null): StagedClipboardFile {
        val id = UUID.randomUUID().toString()
        val file = resolveOwned("$id.part")
        if (!file.createNewFile()) throw IOException("Unable to create clipboard staging file")
        return StagedClipboardFile(id, file)
    }

    fun openForWrite(
        staged: StagedClipboardFile,
        maxBytes: Long = ClipboardLimits.MAX_ENTRY_BYTES,
    ): OutputStream {
        require(maxBytes >= 0L)
        require(staged.file.canonicalFile == resolveOwned("${staged.id}.part"))
        val stream = FileOutputStream(staged.file, false)
        return BoundedSyncingOutputStream(stream, maxBytes)
    }

    fun finalize(staged: StagedClipboardFile): String {
        val source = resolveOwned("${staged.id}.part")
        require(staged.file.canonicalFile == source)
        val target = resolveOwned("${staged.id}.blob")
        if (!source.isFile || target.exists() || !source.renameTo(target)) {
            throw IOException("Unable to finalize clipboard payload")
        }
        return staged.id
    }

    fun openFinal(blobId: String): InputStream = FileInputStream(resolveFinal(blobId))

    fun finalFile(blobId: String): File = resolveFinal(blobId)

    fun delete(blobId: String): Boolean {
        val file = resolveFinal(blobId)
        return !file.exists() || file.delete()
    }

    fun deletePartials() {
        root.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".part") }
            .forEach(File::delete)
    }

    fun listFinalIds(): Set<String> = root.listFiles().orEmpty()
        .asSequence()
        .filter { it.isFile && it.name.endsWith(".blob") }
        .map { it.name.removeSuffix(".blob") }
        .filter(::isValidId)
        .toSet()

    private fun resolveFinal(blobId: String): File {
        require(isValidId(blobId)) { "Invalid clipboard payload identifier" }
        return resolveOwned("$blobId.blob")
    }

    private fun resolveOwned(name: String): File {
        require('/' !in name && '\\' !in name && name != "." && name != "..")
        val resolved = root.resolve(name).canonicalFile
        require(resolved.parentFile == root) { "Invalid clipboard payload path" }
        return resolved
    }

    private fun isValidId(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value.lowercase()
    }.getOrDefault(false)

    private class BoundedSyncingOutputStream(
        private val fileStream: FileOutputStream,
        private val maxBytes: Long,
    ) : FilterOutputStream(fileStream) {
        private var written = 0L

        override fun write(value: Int) {
            reserve(1)
            out.write(value)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            reserve(length)
            out.write(buffer, offset, length)
        }

        override fun flush() {
            out.flush()
        }

        override fun close() {
            try {
                out.flush()
                fileStream.fd.sync()
            } finally {
                out.close()
            }
        }

        private fun reserve(count: Int) {
            if (count > maxBytes - written) throw IOException("Clipboard entry exceeds private storage limit")
            written += count
        }
    }
}
