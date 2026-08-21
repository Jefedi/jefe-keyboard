package ovh.jefe.keyboard.clipboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardPrivateFileStoreTest {
    private lateinit var context: Context
    private lateinit var store: ClipboardPrivateFileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.noBackupFilesDir.resolve("clipboard").deleteRecursively()
        store = ClipboardPrivateFileStore(context)
    }

    @Test
    fun `staging and final files stay in no-backup storage and ignore hostile names`() {
        val staged = store.createStaged("../../SENTINEL-secret.pdf")
        store.openForWrite(staged, maxBytes = 16).use { it.write("bonjour".toByteArray()) }
        val blobId = store.finalize(staged)

        val root = context.noBackupFilesDir.resolve("clipboard").canonicalFile
        val finalFile = root.resolve("$blobId.blob").canonicalFile
        assertTrue(staged.file.canonicalFile.parentFile == root)
        assertTrue(finalFile.parentFile == root)
        assertFalse(staged.file.name.contains("SENTINEL"))
        assertEquals("bonjour", store.openFinal(blobId).bufferedReader().use { it.readText() })
        assertEquals(setOf(blobId), store.listFinalIds())
    }

    @Test
    fun `bounded writer rejects overflow and partial cleanup removes abandoned data`() {
        val staged = store.createStaged("large")

        assertThrows(IOException::class.java) {
            store.openForWrite(staged, maxBytes = 3).use { it.write(byteArrayOf(1, 2, 3, 4)) }
        }
        assertTrue(staged.file.exists())

        store.deletePartials()

        assertFalse(staged.file.exists())
    }

    @Test
    fun `final access rejects path traversal and absolute identifiers`() {
        assertThrows(IllegalArgumentException::class.java) { store.openFinal("../secret") }
        assertThrows(IllegalArgumentException::class.java) { store.openFinal("/tmp/secret") }
        assertThrows(IllegalArgumentException::class.java) { store.delete("not-a-uuid") }
    }
}
