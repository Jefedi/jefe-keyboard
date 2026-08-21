package ovh.jefe.keyboard.clipboard

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ClipboardIngestorTest {
    private lateinit var context: Context
    private lateinit var files: ClipboardPrivateFileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.noBackupFilesDir.resolve("clipboard").deleteRecursively()
        files = ClipboardPrivateFileStore(context)
    }

    @Test
    fun `text and html are preserved exactly in item order`() = runTest {
        val snapshot = snapshot(
            mimeTypes = listOf("text/html", "text/plain"),
            items = listOf(
                item(text = " traduction ", html = "<b> traduction </b>"),
                item(text = "deux"),
            ),
        )
        val accepted = ClipboardIngestPolicy.evaluate(snapshot, false) as ClipboardPolicyDecision.Accept

        val result = ClipboardIngestor(EmptyContentSource, files, clock = { 10L })
            .prepare(snapshot, accepted)

        val prepared = (result as PrepareResult.Success).entry
        assertEquals(listOf(0, 1), prepared.items.map { it.itemIndex })
        assertEquals(" traduction ", prepared.items[0].textPayload)
        assertEquals("<b> traduction </b>", prepared.items[0].htmlPayload)
        assertEquals(ClipboardKind.GROUP, prepared.kind)
        assertFalse(prepared.toString().contains("traduction"))
        prepared.close()
    }

    @Test
    fun `content uri is opened once bounded and staged under private storage`() = runTest {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val source = RecordingContentSource(bytes, "image/png", "photo.png")
        val snapshot = snapshot(
            mimeTypes = listOf("image/png"),
            items = listOf(item(uri = "content://provider/photo")),
        )
        val accepted = ClipboardIngestPolicy.evaluate(snapshot, false) as ClipboardPolicyDecision.Accept

        val result = ClipboardIngestor(source, files, clock = { 10L }).prepare(snapshot, accepted)

        val prepared = (result as PrepareResult.Success).entry
        assertEquals(1, source.openCount)
        assertEquals("image/png", prepared.items.single().mimeType)
        assertEquals(4L, prepared.storedByteSize)
        assertTrue(prepared.items.single().stagedFile!!.file.exists())
        prepared.close()
        assertTrue(context.noBackupFilesDir.resolve("clipboard").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `entry exceeding 25 MiB fails and removes every partial`() = runTest {
        val source = RecordingContentSource(
            ByteArray(1),
            "application/octet-stream",
            "large.bin",
            streamFactory = { RepeatingInputStream(ClipboardLimits.MAX_ENTRY_BYTES + 1) },
        )
        val snapshot = snapshot(
            mimeTypes = listOf("application/octet-stream"),
            items = listOf(item(uri = "content://provider/large")),
        )
        val accepted = ClipboardIngestPolicy.evaluate(snapshot, false) as ClipboardPolicyDecision.Accept

        val result = ClipboardIngestor(source, files, clock = { 10L }).prepare(snapshot, accepted)

        assertEquals(ClipboardFailure.ENTRY_TOO_LARGE, (result as PrepareResult.Failure).failure)
        assertTrue(context.noBackupFilesDir.resolve("clipboard").listFiles().orEmpty().isEmpty())
    }

    private fun snapshot(mimeTypes: List<String>, items: List<SystemClipItemSnapshot>) = SystemClipSnapshot(
        capturedAtMillis = 1L,
        label = "label",
        mimeTypes = mimeTypes,
        isSensitive = false,
        items = items,
    )

    private fun item(text: String? = null, html: String? = null, uri: String? = null) =
        SystemClipItemSnapshot(text, html, uri?.let(Uri::parse), hasIntent = false)

    private object EmptyContentSource : ContentStreamSource {
        override suspend fun metadata(uri: Uri) = SourceMetadata("application/octet-stream", null, null)
        override suspend fun open(uri: Uri): InputStream = error("No URI expected")
    }

    private class RecordingContentSource(
        private val bytes: ByteArray,
        private val mime: String,
        private val name: String?,
        private val streamFactory: () -> InputStream = { ByteArrayInputStream(bytes) },
    ) : ContentStreamSource {
        var openCount = 0
        override suspend fun metadata(uri: Uri) = SourceMetadata(mime, name, bytes.size.toLong())
        override suspend fun open(uri: Uri): InputStream {
            openCount += 1
            return streamFactory()
        }
    }

    private class RepeatingInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0) 0 else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            buffer.fill(0, offset, offset + count)
            remaining -= count
            return count
        }
    }
}
