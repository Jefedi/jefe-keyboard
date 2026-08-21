package ovh.jefe.keyboard.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemClipboardGatewayTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(ClipboardManager::class.java)

    @After
    fun clearClipboard() {
        manager.clearPrimaryClip()
    }

    @Test
    fun `capture copies text html uri and sensitive metadata without coercing`() {
        val description = ClipDescription("secret label", arrayOf("text/html")).apply {
            extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        manager.setPrimaryClip(
            ClipData(
                description,
                CoercionForbiddenItem(
                    text = StringBuilder("fallback"),
                    html = "<b>fallback</b>",
                    uri = Uri.parse("content://source/1"),
                ),
            ),
        )

        val result = SystemClipboardGateway(context).capturePrimaryClip()

        val snapshot = (result as ClipboardGatewayResult.Captured).snapshot
        assertTrue(snapshot.isSensitive)
        assertEquals("secret label", snapshot.label)
        assertEquals(listOf("text/html"), snapshot.mimeTypes)
        assertEquals("fallback", snapshot.items.single().text)
        assertEquals("<b>fallback</b>", snapshot.items.single().htmlText)
        assertEquals(Uri.parse("content://source/1"), snapshot.items.single().uri)
    }

    @Test
    fun `capture returns empty when the system has no primary clip`() {
        manager.clearPrimaryClip()

        assertSame(ClipboardGatewayResult.Empty, SystemClipboardGateway(context).capturePrimaryClip())
    }

    @Test
    fun `capture converts security failures to a safe result without metadata`() {
        val gateway = SystemClipboardGateway(object : ClipboardManagerAccess {
            override fun primaryClip(): ClipData? = throw SecurityException("SENTINEL-clipboard-secret")
            override fun addListener(listener: ClipboardManager.OnPrimaryClipChangedListener) = Unit
            override fun removeListener(listener: ClipboardManager.OnPrimaryClipChangedListener) = Unit
        })

        val result = gateway.capturePrimaryClip()

        assertEquals(ClipboardFailure.ACCESS_DENIED, (result as ClipboardGatewayResult.Failure).failure)
        assertFalse(result.toString().contains("SENTINEL-clipboard-secret"))
    }

    @Test
    fun `capture makes an independent copy of mutable clip text`() {
        val source = StringBuilder("before")
        manager.setPrimaryClip(ClipData.newPlainText("label", source))

        val captured = (SystemClipboardGateway(context).capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot
        source.replace(0, source.length, "after")

        assertEquals("before", captured.items.single().text)
    }

    @Test
    fun `capture copies only the initially validated text length when a source grows`() {
        manager.setPrimaryClip(ClipData.newPlainText("label", GrowingCharSequence("ab", "abc")))

        val snapshot = (SystemClipboardGateway(context).capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertEquals("ab", snapshot.items.single().text)
    }

    @Test
    fun `capture copies only the initially validated label length when a label grows`() {
        val label = GrowingCharSequence("ok", "overflow")
        manager.setPrimaryClip(ClipData(ClipDescription(label, arrayOf("text/plain")), ClipData.Item("safe")))

        val snapshot = (SystemClipboardGateway(context).capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertEquals("ov", snapshot.label)
    }

    @Test
    fun `capture turns hostile character indexing into access denied`() {
        manager.setPrimaryClip(ClipData.newPlainText("label", ThrowingCharSequence()))

        val result = SystemClipboardGateway(context).capturePrimaryClip()

        assertEquals(ClipboardFailure.ACCESS_DENIED, (result as ClipboardGatewayResult.Failure).failure)
    }

    @Test
    fun `capture rejects metadata before copying hostile values`() {
        manager.setPrimaryClip(ClipData(ClipDescription("x".repeat(4_097), arrayOf("text/plain")), ClipData.Item("safe")))
        assertEquals(
            ClipboardFailure.INVALID_METADATA,
            (SystemClipboardGateway(context).capturePrimaryClip() as ClipboardGatewayResult.Failure).failure,
        )

        manager.setPrimaryClip(ClipData(ClipDescription("safe", arrayOf("x".repeat(256))), ClipData.Item("safe")))
        assertEquals(
            ClipboardFailure.INVALID_METADATA,
            (SystemClipboardGateway(context).capturePrimaryClip() as ClipboardGatewayResult.Failure).failure,
        )
    }

    @Test
    fun `snapshot and failures never reveal clipboard plaintext in string output`() {
        val secret = "SENTINEL-clipboard-secret"
        manager.setPrimaryClip(
            ClipData(
                ClipDescription(secret, arrayOf("text/plain")),
                ClipData.Item(secret, "<b>$secret</b>", null, Uri.parse("content://$secret")),
            ),
        )

        val result = SystemClipboardGateway(context).capturePrimaryClip()

        assertFalse(result.toString().contains(secret))
        assertFalse((result as ClipboardGatewayResult.Captured).snapshot.toString().contains(secret))
        assertFalse(result.snapshot.items.single().toString().contains(secret))
    }

    @Test
    fun `listener is registered once and removed idempotently`() {
        val gateway = SystemClipboardGateway(context)
        var callbacks = 0

        gateway.startListening { callbacks += 1 }
        gateway.startListening { callbacks += 1 }
        manager.setPrimaryClip(ClipData.newPlainText("label", "first"))
        gateway.stopListening()
        gateway.stopListening()
        manager.setPrimaryClip(ClipData.newPlainText("label", "second"))

        assertEquals(1, callbacks)
    }

    @Test
    fun `api 26 timestamp identifies repeated classification callbacks for one copied clip`() {
        val description = ClipDescription("label", arrayOf("text/plain"))
        val access = RecordingClipboardAccess(ClipData(description, ClipData.Item("one")))
        val gateway = SystemClipboardGateway(
            access,
            timestampReader = ClipboardSourceTimestampReader { 101L },
        )
        var callbacks = 0
        gateway.startListening { callbacks += 1 }

        access.dispatchChange()
        val first = (gateway.capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot
        access.dispatchChange()
        val second = (gateway.capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertEquals(2, callbacks)
        assertEquals(101L, first.sourceTimestampMillis)
        assertTrue(first.hasSameKnownSource(second))
    }

    @Test
    fun `api 24 fallback explicitly reports source identity unavailable`() {
        val description = ClipDescription("label", arrayOf("text/plain"))
        val access = RecordingClipboardAccess(ClipData(description, ClipData.Item("one")))

        val snapshot = (
            SystemClipboardGateway(
                access,
                timestampReader = PlatformClipboardSourceTimestampReader(sdkInt = 25),
            ).capturePrimaryClip() as ClipboardGatewayResult.Captured
        ).snapshot

        assertEquals(null, snapshot.sourceTimestampMillis)
        assertFalse(snapshot.hasSameKnownSource(snapshot))
    }

    @Test
    fun `snapshot collections cannot be mutated through list casts`() {
        manager.setPrimaryClip(ClipData.newPlainText("label", "safe"))

        val snapshot = (SystemClipboardGateway(context).capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertUnmodifiable(snapshot.mimeTypes)
        assertUnmodifiable(snapshot.items)
    }

    private fun assertUnmodifiable(value: List<*>) {
        @Suppress("UNCHECKED_CAST")
        val mutable = value as MutableList<Any?>
        assertThrows(UnsupportedOperationException::class.java) { mutable.add(Any()) }
    }

    private class CoercionForbiddenItem(
        text: CharSequence,
        html: String,
        uri: Uri,
    ) : ClipData.Item(text, html, null, uri) {
        override fun coerceToText(context: Context): CharSequence =
            throw AssertionError("capture must not coerce clipboard items")
    }

    private class GrowingCharSequence(
        private val initiallyVisible: String,
        private val laterVisible: String,
    ) : CharSequence {
        private var lengthReads = 0

        override val length: Int
            get() = if (lengthReads++ == 0) initiallyVisible.length else laterVisible.length

        override fun get(index: Int): Char = laterVisible[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = laterVisible.subSequence(startIndex, endIndex)
    }

    private class ThrowingCharSequence : CharSequence {
        override val length: Int get() = 1
        override fun get(index: Int): Char = throw IllegalStateException("hostile source")
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw IllegalStateException("hostile source")
    }

    private class RecordingClipboardAccess(
        private var clip: ClipData?,
    ) : ClipboardManagerAccess {
        private val listeners = linkedSetOf<ClipboardManager.OnPrimaryClipChangedListener>()

        override fun primaryClip(): ClipData? = clip
        override fun addListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
            listeners += listener
        }
        override fun removeListener(listener: ClipboardManager.OnPrimaryClipChangedListener) {
            listeners -= listener
        }
        fun dispatchChange() = listeners.forEach { it.onPrimaryClipChanged() }
    }
}
