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

        val result = SystemClipboardGateway(context).capturePrimaryClip()

        assertTrue(result is ClipboardGatewayResult.Empty)
        assertEquals(ClipboardSourceObservation.NoPrimaryClip, result.source)
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
        assertEquals(
            ClipboardSourceObservation.Observed(ClipboardSourceMarker.TimestampUnavailable),
            result.source,
        )
    }

    @Test
    fun `capture turns a throwing source marker reader into explicit unavailable evidence`() {
        val access = RecordingClipboardAccess(ClipData.newPlainText("label", "safe"))
        val gateway = SystemClipboardGateway(
            access,
            sourceMarkerReader = ClipboardSourceMarkerReader { throw SecurityException("source timestamp denied") },
        )

        val result = gateway.capturePrimaryClip()

        assertTrue(result is ClipboardGatewayResult.Captured)
        assertEquals(
            ClipboardSourceObservation.Observed(ClipboardSourceMarker.TimestampUnavailable),
            result.source,
        )
    }

    @Test
    fun `capture turns a runtime failing source marker reader into explicit unavailable evidence`() {
        val access = RecordingClipboardAccess(ClipData.newPlainText("label", "safe"))
        val gateway = SystemClipboardGateway(
            access,
            sourceMarkerReader = ClipboardSourceMarkerReader { throw IllegalStateException("source timestamp failed") },
        )

        val result = gateway.capturePrimaryClip()

        assertTrue(result is ClipboardGatewayResult.Captured)
        assertEquals(
            ClipboardSourceObservation.Observed(ClipboardSourceMarker.TimestampUnavailable),
            result.source,
        )
    }

    @Test
    fun `source evidence survives a security failure while reading sensitive extras`() {
        val access = RecordingClipboardAccess(ClipData.newPlainText("label", "safe"))
        val gateway = SystemClipboardGateway(
            access,
            sourceMarkerReader = ClipboardSourceMarkerReader { ClipboardSourceMarker.PlatformTimestamp(100L) },
            sensitiveFlagReader = ClipboardSensitiveFlagReader { throw SecurityException("extras denied") },
        )

        val result = gateway.capturePrimaryClip()

        assertEquals(ClipboardFailure.ACCESS_DENIED, (result as ClipboardGatewayResult.Failure).failure)
        assertEquals(
            ClipboardSourceObservation.Observed(ClipboardSourceMarker.PlatformTimestamp(100L)),
            result.source,
        )
        assertFalse(result.toString().contains("extras denied"))
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
    fun `capture freezes text before later item metadata can mutate it`() {
        val text = SwitchableCharSequence("before", "after!")
        val html = SwitchableCharSequence("<b>before</b>", "<b>after!</b>")
        val trigger = TriggeringCharSequence("later") {
            text.useLaterValue()
            html.useLaterValue()
        }
        val access = RecordingClipboardAccess(
            ClipData(ClipDescription("label", arrayOf("text/html")), ClipData.Item(text, html.toString(), null, null)).apply {
                addItem(ClipData.Item(trigger))
            },
        )

        val snapshot = (SystemClipboardGateway(access).capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertEquals("before", snapshot.items.first().text)
        assertEquals("<b>before</b>", snapshot.items.first().htmlText)
    }

    @Test
    fun `capture freezes label UTF-16 units before appending them`() {
        val label = SingleReadCharSequence("\uD83D\uDC4D")
        manager.setPrimaryClip(ClipData(ClipDescription(label, arrayOf("text/plain")), ClipData.Item("safe")))

        val snapshot = (SystemClipboardGateway(context).capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertEquals("\uD83D\uDC4D", snapshot.label)
    }

    @Test
    fun `capture keeps an immediately frozen label when later reads mutate its source`() {
        val label = GrowingCharSequence("ok", "overflow")
        manager.setPrimaryClip(ClipData(ClipDescription(label, arrayOf("text/plain")), ClipData.Item("safe")))

        val snapshot = (SystemClipboardGateway(context).capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertEquals("ok", snapshot.label)
    }

    @Test
    fun `capture snapshots sensitive metadata before hostile clip label access`() {
        lateinit var clip: ClipData
        val label = TriggeringCharSequence("label") {
            clip.description.extras?.putBoolean("android.content.extra.IS_SENSITIVE", false)
        }
        clip = ClipData(
            ClipDescription(label, arrayOf("text/plain")).apply {
                extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            },
            ClipData.Item("safe"),
        )
        val access = RecordingClipboardAccess(
            clip,
        )

        val snapshot = (SystemClipboardGateway(access).capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertTrue(snapshot.isSensitive)
    }

    @Test
    fun `capture reads hostile growing text length once before freezing it`() {
        val text = SingleLengthGrowingCharSequence("ok", "a value that must never be sized")
        val access = RecordingClipboardAccess(ClipData.newPlainText("label", text))

        val snapshot = (SystemClipboardGateway(access).capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertEquals("ok", snapshot.items.single().text)
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
    fun `rejected clip preserves its marker so a valid later clip proves change`() {
        val access = RecordingClipboardAccess(tooManyItemsClip())
        val timestamps = ArrayDeque(listOf(100L, 101L))
        val gateway = SystemClipboardGateway(
            access,
            sourceMarkerReader = ClipboardSourceMarkerReader {
                ClipboardSourceMarker.PlatformTimestamp(timestamps.removeFirst())
            },
        )

        val rejected = gateway.capturePrimaryClip()
        access.setPrimaryClip(ClipData.newPlainText("label", "valid"))
        val accepted = gateway.capturePrimaryClip()

        assertEquals(ClipboardFailure.TOO_MANY_ITEMS, (rejected as ClipboardGatewayResult.Failure).failure)
        assertEquals(
            ClipboardSourceObservation.Observed(ClipboardSourceMarker.PlatformTimestamp(100L)),
            rejected.source,
        )
        assertTrue(accepted is ClipboardGatewayResult.Captured)
        assertEquals(
            ClipboardSourceChange.DEFINITELY_CHANGED,
            compareClipboardSource(
                (rejected.source as ClipboardSourceObservation.Observed).marker,
                (accepted.source as ClipboardSourceObservation.Observed).marker,
            ),
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
    fun `api 31 equal timestamp classification callbacks are not proven changed`() {
        val description = ClipDescription("label", arrayOf("text/plain"))
        val access = RecordingClipboardAccess(ClipData(description, ClipData.Item("one")))
        val gateway = SystemClipboardGateway(
            access,
            sourceMarkerReader = ClipboardSourceMarkerReader { ClipboardSourceMarker.PlatformTimestamp(101L) },
        )
        var callbacks = 0
        gateway.startListening { callbacks += 1 }

        access.dispatchChange()
        val first = (gateway.capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot
        access.dispatchChange()
        val second = (gateway.capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertEquals(2, callbacks)
        assertEquals(ClipboardSourceMarker.PlatformTimestamp(101L), first.sourceMarker)
        assertEquals(ClipboardSourceChange.SAME_OR_COLLIDING, compareClipboardSource(first.sourceMarker, second.sourceMarker))
    }

    @Test
    fun `equal timestamp different clips remain same or colliding`() {
        val access = RecordingClipboardAccess(ClipData.newPlainText("label", "first"))
        val gateway = SystemClipboardGateway(
            access,
            sourceMarkerReader = ClipboardSourceMarkerReader { ClipboardSourceMarker.PlatformTimestamp(77L) },
        )

        val first = (gateway.capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot
        access.setPrimaryClip(ClipData.newPlainText("label", "second"))
        val second = (gateway.capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertEquals("first", first.items.single().text)
        assertEquals("second", second.items.single().text)
        assertEquals(ClipboardSourceChange.SAME_OR_COLLIDING, compareClipboardSource(first.sourceMarker, second.sourceMarker))
    }

    @Test
    fun `different timestamp including wall clock rollback proves a later copy`() {
        assertEquals(
            ClipboardSourceChange.DEFINITELY_CHANGED,
            compareClipboardSource(
                ClipboardSourceMarker.PlatformTimestamp(101L),
                ClipboardSourceMarker.PlatformTimestamp(99L),
            ),
        )
    }

    @Test
    fun `api 24 through 30 listener callback is explicit legacy proof`() {
        val description = ClipDescription("label", arrayOf("text/plain"))
        val access = RecordingClipboardAccess(ClipData(description, ClipData.Item("one")))

        val snapshot = (
            SystemClipboardGateway(
                access,
                sourceMarkerReader = PlatformClipboardSourceMarkerReader(sdkInt = 30),
            ).capturePrimaryClip() as ClipboardGatewayResult.Captured
        ).snapshot

        assertEquals(ClipboardSourceMarker.LegacyListenerEvent, snapshot.sourceMarker)
        assertEquals(
            ClipboardSourceChange.DEFINITELY_CHANGED,
            compareClipboardSource(ClipboardSourceMarker.LegacyListenerEvent, snapshot.sourceMarker),
        )
    }

    @Test
    fun `unavailable timestamp and mixed platform evidence are unknown`() {
        assertEquals(
            ClipboardSourceChange.UNKNOWN,
            compareClipboardSource(ClipboardSourceMarker.TimestampUnavailable, ClipboardSourceMarker.PlatformTimestamp(1L)),
        )
        assertEquals(
            ClipboardSourceChange.UNKNOWN,
            compareClipboardSource(ClipboardSourceMarker.PlatformTimestamp(1L), ClipboardSourceMarker.TimestampUnavailable),
        )
        assertEquals(
            ClipboardSourceChange.UNKNOWN,
            compareClipboardSource(ClipboardSourceMarker.PlatformTimestamp(1L), ClipboardSourceMarker.LegacyListenerEvent),
        )
    }

    @Test
    fun `snapshot collections cannot be mutated through list casts`() {
        manager.setPrimaryClip(ClipData.newPlainText("label", "safe"))

        val snapshot = (SystemClipboardGateway(context).capturePrimaryClip() as ClipboardGatewayResult.Captured).snapshot

        assertUnmodifiable(snapshot.mimeTypes)
        assertUnmodifiable(snapshot.items)
    }

    @Test
    fun `clipboard failures do not expose an obsolete encryption error`() {
        assertFalse(ClipboardFailure.entries.any { it.name == "KEY_UNAVAILABLE" })
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

        override fun get(index: Int): Char = initiallyVisible[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = initiallyVisible.subSequence(startIndex, endIndex)
    }

    private class SwitchableCharSequence(
        private val firstValue: String,
        private val laterValue: String,
    ) : CharSequence {
        private var later = false

        override val length: Int get() = value.length
        override fun get(index: Int): Char = value[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = value.subSequence(startIndex, endIndex)

        fun useLaterValue() {
            later = true
        }

        override fun toString(): String = value

        private val value: String get() = if (later) laterValue else firstValue
    }

    private class TriggeringCharSequence(
        private val value: String,
        private val onFirstRead: () -> Unit,
    ) : CharSequence {
        private var triggered = false

        override val length: Int
            get() {
                trigger()
                return value.length
            }

        override fun get(index: Int): Char {
            trigger()
            return value[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            trigger()
            return value.subSequence(startIndex, endIndex)
        }

        private fun trigger() {
            if (!triggered) {
                triggered = true
                onFirstRead()
            }
        }
    }

    private class SingleReadCharSequence(
        private val value: String,
    ) : CharSequence {
        private val readIndices = mutableSetOf<Int>()

        override val length: Int get() = value.length
        override fun get(index: Int): Char {
            check(readIndices.add(index)) { "UTF-16 unit $index read twice" }
            return value[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = value.subSequence(startIndex, endIndex)
    }

    private class SingleLengthGrowingCharSequence(
        private val initialValue: String,
        private val laterValue: String,
    ) : CharSequence {
        private var lengthReads = 0

        override val length: Int
            get() = when (lengthReads++) {
                0 -> initialValue.length
                else -> throw IllegalStateException("hostile length changed to ${laterValue.length}")
            }

        override fun get(index: Int): Char = initialValue[index]
        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = initialValue.subSequence(startIndex, endIndex)
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
        fun setPrimaryClip(value: ClipData?) {
            clip = value
        }
        fun dispatchChange() = listeners.forEach { it.onPrimaryClipChanged() }
    }

    private fun tooManyItemsClip(): ClipData = ClipData.newPlainText("label", "first").apply {
        repeat(ClipboardLimits.MAX_GROUP_ITEMS) { addItem(ClipData.Item("extra")) }
    }
}
