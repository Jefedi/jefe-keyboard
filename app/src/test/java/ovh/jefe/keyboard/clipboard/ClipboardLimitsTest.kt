package ovh.jefe.keyboard.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardLimitsTest {
    @Test
    fun `limits use binary mebibytes and approved counts`() {
        assertEquals(32, ClipboardLimits.MAX_GROUP_ITEMS)
        assertEquals(32, ClipboardLimits.INGEST_QUEUE_CAPACITY)
        assertEquals(64, ClipboardLimits.MAX_MIME_TYPES)
        assertEquals(8_192, ClipboardLimits.MAX_URI_CHARS)
        assertEquals(25L * 1_048_576L, ClipboardLimits.MAX_ENTRY_BYTES)
        assertEquals(25 * 1_048_576, ClipboardLimits.MAX_SNAPSHOT_TEXT_CHARS)
        assertEquals(128 * 1_024, ClipboardLimits.MAX_DIRECT_COMMIT_TEXT_UTF8_BYTES)
        assertEquals(500, ClipboardLimits.MAX_UNPINNED_ENTRIES)
        assertEquals(250L * 1_048_576L, ClipboardLimits.MAX_UNPINNED_BYTES)
        assertEquals(30_000L, ClipboardLimits.INGEST_TIMEOUT_MILLIS)
        assertEquals(60_000L, ClipboardLimits.GRANT_WINDOW_MILLIS)
        assertEquals(3, ClipboardLimits.MAX_GRANT_OPENS)
    }
}
