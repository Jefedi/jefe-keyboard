package ovh.jefe.keyboard.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardHistoryControllerTest {
    @Test
    fun `disabled history registers no listener and explicit enable imports current clip once`() = runTest {
        val gateway = FakeGateway(captured("current", 1L))
        val store = MemoryClipboardActivationStore()
        val pipeline = RecordingPipeline()
        val controller = ClipboardHistoryController(gateway, pipeline, EmptyRepository, store, backgroundScope)

        controller.start()
        assertFalse(gateway.listening)
        assertTrue(pipeline.values.isEmpty())

        controller.enable(privateEditor = false)
        runCurrent()

        assertEquals(ClipboardActivation.ENABLED, store.activation())
        assertTrue(gateway.listening)
        assertEquals(listOf("current"), pipeline.values)
    }

    @Test
    fun `listener snapshots preserve FIFO when first ingestion is slow`() = runTest {
        val gateway = FakeGateway(ClipboardGatewayResult.Empty(ClipboardSourceObservation.NoPrimaryClip))
        val releaseFirst = CompletableDeferred<Unit>()
        val pipeline = RecordingPipeline(releaseFirst)
        val controller = ClipboardHistoryController(
            gateway,
            pipeline,
            EmptyRepository,
            MemoryClipboardActivationStore(ClipboardActivation.ENABLED),
            backgroundScope,
        )
        controller.start()
        runCurrent()

        gateway.result = captured("A", 2L)
        gateway.dispatch()
        gateway.result = captured("B", 3L)
        gateway.dispatch()
        runCurrent()
        assertTrue(pipeline.values.isEmpty())

        releaseFirst.complete(Unit)
        runCurrent()

        assertEquals(listOf("A", "B"), pipeline.values)
    }

    @Test
    fun `clear persists observed marker and never reimports the cleared clip`() = runTest {
        val marker = ClipboardSourceMarker.PlatformTimestamp(40L)
        val gateway = FakeGateway(captured("old", 40L, marker))
        val store = MemoryClipboardActivationStore(ClipboardActivation.ENABLED)
        val pipeline = RecordingPipeline()
        val repository = RecordingRepository()
        val controller = ClipboardHistoryController(gateway, pipeline, repository, store, backgroundScope)
        controller.start()
        runCurrent()
        pipeline.values.clear()

        controller.clearAndResume()
        runCurrent()

        assertTrue(repository.cleared)
        assertEquals(ClipboardSuppressionState.Suppressed(marker), store.suppression())
        assertEquals(ClipboardActivation.ENABLED, store.activation())
        assertTrue(gateway.listening)
        assertTrue(pipeline.values.isEmpty())
    }

    private fun captured(
        text: String,
        time: Long,
        marker: ClipboardSourceMarker = ClipboardSourceMarker.PlatformTimestamp(time),
    ) = ClipboardGatewayResult.Captured(
        SystemClipSnapshot(
            time,
            null,
            listOf("text/plain"),
            false,
            listOf(SystemClipItemSnapshot(text, null, null, false)),
            marker,
        ),
    )

    private class FakeGateway(var result: ClipboardGatewayResult) : ClipboardGateway {
        var listening = false
        private var callback: (() -> Unit)? = null
        override fun capturePrimaryClip() = result
        override fun startListening(callback: () -> Unit) {
            listening = true
            this.callback = callback
        }
        override fun stopListening() {
            listening = false
            callback = null
        }
        fun dispatch() = requireNotNull(callback).invoke()
    }

    private class RecordingPipeline(private val firstBarrier: CompletableDeferred<Unit>? = null) : ClipboardCapturePipeline {
        val values = mutableListOf<String>()
        private var calls = 0
        override suspend fun process(result: ClipboardGatewayResult, privateEditor: Boolean): ClipboardFailure? {
            if (result !is ClipboardGatewayResult.Captured) return null
            if (calls++ == 0) firstBarrier?.await()
            val text = result.snapshot.items.single().text!!
            values += text
            return null
        }
    }

    private object EmptyRepository : ClipboardRepository by NoOpRepository()

    private class RecordingRepository : NoOpRepository() {
        var cleared = false
        override suspend fun clearAll() { cleared = true }
    }

    private open class NoOpRepository : ClipboardRepository {
        override fun observe() = kotlinx.coroutines.flow.flowOf(ClipboardHistoryState.Empty)
        override suspend fun store(prepared: PreparedClipboardEntry) = StoreResult.Failure(ClipboardFailure.DATABASE_UNAVAILABLE)
        override suspend fun load(id: ClipboardEntryId): LoadedClipboardEntry? = null
        override suspend fun setPinned(id: ClipboardEntryId, pinned: Boolean, confirmImpact: Boolean) = PinResult.NotFound
        override suspend fun markSensitive(id: ClipboardEntryId) = false
        override suspend fun delete(id: ClipboardEntryId) = false
        override suspend fun clearAll() = Unit
        override suspend fun search(query: String, generation: Long) = SearchResult(generation, emptyList())
        override suspend fun reconcile() = Unit
    }
}
