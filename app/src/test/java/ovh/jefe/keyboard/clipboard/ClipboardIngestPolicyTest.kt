package ovh.jefe.keyboard.clipboard

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardIngestPolicyTest {
    @Test
    fun `policy classifies supported single items and groups in representation order`() {
        assertAccepted(snapshot(item = item(text = "bonjour")), ClipboardKind.TEXT)
        assertAccepted(snapshot(item = item(text = "https://example.com/path")), ClipboardKind.LINK)
        assertAccepted(snapshot(item = item(text = "fallback", html = "<b>fallback</b>")), ClipboardKind.HTML)
        assertAccepted(snapshot(mimeTypes = listOf("image/png"), item = item(uri = "content://source/image")), ClipboardKind.IMAGE)
        assertAccepted(snapshot(mimeTypes = listOf("video/mp4"), item = item(uri = "content://source/video")), ClipboardKind.VIDEO)
        assertAccepted(snapshot(mimeTypes = listOf("audio/mp4"), item = item(uri = "content://source/audio")), ClipboardKind.AUDIO)
        assertAccepted(snapshot(mimeTypes = listOf("application/pdf"), item = item(uri = "content://source/file")), ClipboardKind.FILE)
        assertAccepted(snapshot(items = listOf(item(text = "one"), item(text = "two"))), ClipboardKind.GROUP)
    }

    @Test
    fun `policy rejects unsupported hostile and overbounded snapshots`() {
        assertRejected(snapshot(item = item(hasIntent = true)), ClipboardFailure.UNSUPPORTED)
        assertRejected(snapshot(item = item(uri = "file:///secret")), ClipboardFailure.UNSUPPORTED)
        assertRejected(snapshot(items = List(33) { item(text = "$it") }), ClipboardFailure.TOO_MANY_ITEMS)
        assertRejected(snapshot(mimeTypes = listOf("x".repeat(256)), item = item(text = "x")), ClipboardFailure.INVALID_METADATA)
        assertRejected(snapshot(mimeTypes = List(65) { "text/plain" }, item = item(text = "x")), ClipboardFailure.INVALID_METADATA)
        assertRejected(snapshot(item = item(uri = "content://${"x".repeat(8_193)}")), ClipboardFailure.INVALID_METADATA)
        assertRejected(snapshot(mimeTypes = listOf("text/\u0001plain"), item = item(text = "x")), ClipboardFailure.INVALID_METADATA)
        assertRejected(snapshot(item = item(html = "<b>no fallback</b>")), ClipboardFailure.UNSUPPORTED)
        assertRejected(snapshot(items = listOf(item(text = "safe"), item(hasIntent = true))), ClipboardFailure.UNSUPPORTED)
        assertRejected(snapshot(items = listOf(item(text = "safe"), item(uri = "https://example.com"))), ClipboardFailure.UNSUPPORTED)
    }

    @Test
    fun `policy normalizes ASCII MIME and keeps URI candidates per item`() {
        val decision = accept(
            snapshot(
                mimeTypes = listOf("IMAGE/PNG", "application/PDF", "audio/mpeg"),
                items = listOf(
                    item(uri = "content://source/image"),
                    item(uri = "content://source/document"),
                    item(uri = "content://source/audio"),
                ),
            ),
        )

        assertEquals(ClipboardKind.GROUP, decision.kind)
        assertEquals(listOf("image/png", "application/pdf", "audio/mpeg"), decision.items[0].candidateMimeTypes)
        assertEquals(listOf("image/png", "application/pdf", "audio/mpeg"), decision.items[1].candidateMimeTypes)
        assertEquals(listOf("image/png", "application/pdf", "audio/mpeg"), decision.items[2].candidateMimeTypes)
        assertFalse(decision.items.any { it.candidateMimeTypes.size == 1 })
    }

    @Test
    fun `one sensitive item private editor or source sensitivity makes the complete entry sensitive`() {
        assertTrue(accept(snapshot(items = listOf(item(text = "one"), item(text = "two", sensitive = true)))).isSensitive)
        assertTrue(accept(snapshot(item = item(text = "1234")), privateEditor = true).isSensitive)
        assertTrue(accept(snapshot(isSensitive = true, item = item(text = "1234"))).isSensitive)
    }

    @Test
    fun `intent beside a safe representation is ignored rather than serialized`() {
        val accepted = accept(snapshot(item = item(text = "safe", hasIntent = true)))

        assertEquals(ClipboardKind.TEXT, accepted.kind)
        assertEquals(0, accepted.items.single().itemIndex)
    }

    @Test
    fun `accepted item collections cannot be mutated through list casts`() {
        val accepted = accept(snapshot(items = listOf(item(text = "one"), item(text = "two"))))

        assertUnmodifiable(accepted.items)
        assertUnmodifiable(accepted.items.first().candidateMimeTypes)
    }

    private fun assertAccepted(value: SystemClipSnapshot, kind: ClipboardKind) {
        assertEquals(kind, accept(value).kind)
    }

    private fun assertRejected(value: SystemClipSnapshot, failure: ClipboardFailure) {
        assertEquals(failure, (ClipboardIngestPolicy.evaluate(value, privateEditor = false) as ClipboardPolicyDecision.Reject).failure)
    }

    private fun accept(value: SystemClipSnapshot, privateEditor: Boolean = false): ClipboardPolicyDecision.Accept =
        ClipboardIngestPolicy.evaluate(value, privateEditor) as ClipboardPolicyDecision.Accept

    private fun snapshot(
        mimeTypes: List<String> = listOf("text/plain"),
        isSensitive: Boolean = false,
        item: SystemClipItemSnapshot = this.item(text = "text"),
        items: List<SystemClipItemSnapshot> = listOf(item),
    ) = SystemClipSnapshot(
        capturedAtMillis = 1L,
        label = null,
        mimeTypes = mimeTypes,
        isSensitive = isSensitive,
        items = items,
    )

    private fun item(
        text: String? = null,
        html: String? = null,
        uri: String? = null,
        hasIntent: Boolean = false,
        sensitive: Boolean = false,
    ) = SystemClipItemSnapshot(
        text = text,
        htmlText = html,
        uri = uri?.let(Uri::parse),
        hasIntent = hasIntent,
        isSensitive = sensitive,
    )

    private fun assertUnmodifiable(value: List<*>) {
        @Suppress("UNCHECKED_CAST")
        val mutable = value as MutableList<Any?>
        assertThrows(UnsupportedOperationException::class.java) { mutable.add(Any()) }
    }
}
