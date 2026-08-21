package ovh.jefe.keyboard.clipboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

internal enum class ClipboardActivation { DISABLED, ENABLED, CLEARING_ENABLED, DISABLING }

internal sealed interface ClipboardSuppressionState {
    data object NotSuppressed : ClipboardSuppressionState
    data class Suppressed(val marker: ClipboardSourceMarker) : ClipboardSuppressionState
}

internal interface ClipboardActivationStore {
    fun activation(): ClipboardActivation
    fun writeActivation(value: ClipboardActivation): Boolean
    fun suppression(): ClipboardSuppressionState
    fun writeSuppression(value: ClipboardSuppressionState): Boolean
}

internal class MemoryClipboardActivationStore(
    initialActivation: ClipboardActivation = ClipboardActivation.DISABLED,
) : ClipboardActivationStore {
    private var activation = initialActivation
    private var suppression: ClipboardSuppressionState = ClipboardSuppressionState.NotSuppressed
    override fun activation() = activation
    override fun writeActivation(value: ClipboardActivation): Boolean {
        activation = value
        return true
    }
    override fun suppression() = suppression
    override fun writeSuppression(value: ClipboardSuppressionState): Boolean {
        suppression = value
        return true
    }
}

internal interface ClipboardCapturePipeline {
    suspend fun process(result: ClipboardGatewayResult, privateEditor: Boolean): ClipboardFailure?
}

internal class DefaultClipboardCapturePipeline(
    private val ingestor: ClipboardIngestor,
    private val repository: ClipboardRepository,
) : ClipboardCapturePipeline {
    override suspend fun process(result: ClipboardGatewayResult, privateEditor: Boolean): ClipboardFailure? {
        val snapshot = when (result) {
            is ClipboardGatewayResult.Captured -> result.snapshot
            is ClipboardGatewayResult.Empty -> return null
            is ClipboardGatewayResult.Failure -> return result.failure
        }
        return when (val policy = ClipboardIngestPolicy.evaluate(snapshot, privateEditor)) {
            is ClipboardPolicyDecision.Reject -> policy.failure
            is ClipboardPolicyDecision.Accept -> when (val prepared = ingestor.prepare(snapshot, policy)) {
                is PrepareResult.Failure -> prepared.failure
                is PrepareResult.Success -> when (val stored = repository.store(prepared.entry)) {
                    is StoreResult.Failure -> stored.failure
                    is StoreResult.Stored -> null
                }
            }
        }
    }
}

internal class ClipboardHistoryController(
    private val gateway: ClipboardGateway,
    private val pipeline: ClipboardCapturePipeline,
    private val repository: ClipboardRepository,
    private val activationStore: ClipboardActivationStore,
    private val scope: CoroutineScope,
) {
    private data class Work(val result: ClipboardGatewayResult, val privateEditor: Boolean)

    private var queue = Channel<Work>(Channel.UNLIMITED)
    private var permits = Semaphore(ClipboardLimits.INGEST_QUEUE_CAPACITY)
    private var consumer: Job? = null
    @Volatile private var privateEditor = true

    fun activation(): ClipboardActivation = activationStore.activation()

    fun start() {
        when (activationStore.activation()) {
            ClipboardActivation.DISABLED -> Unit
            ClipboardActivation.DISABLING -> scope.launch { finishDisable() }
            ClipboardActivation.CLEARING_ENABLED -> scope.launch { clearAndResume() }
            ClipboardActivation.ENABLED -> {
                startQueue()
                attachListener()
                if (activationStore.suppression() is ClipboardSuppressionState.NotSuppressed) {
                    enqueue(gateway.capturePrimaryClip(), privateEditor)
                }
            }
        }
    }

    fun onEditorPrivacyChanged(value: Boolean) {
        privateEditor = value
    }

    suspend fun enable(privateEditor: Boolean) {
        this.privateEditor = privateEditor
        if (!activationStore.writeActivation(ClipboardActivation.ENABLED)) return
        activationStore.writeSuppression(ClipboardSuppressionState.NotSuppressed)
        startQueue()
        attachListener()
        enqueue(gateway.capturePrimaryClip(), privateEditor)
    }

    suspend fun clearAndResume() {
        if (!activationStore.writeActivation(ClipboardActivation.CLEARING_ENABLED)) return
        gateway.stopListening()
        stopQueue()
        val result = gateway.capturePrimaryClip()
        val suppression = when (val source = result.source) {
            ClipboardSourceObservation.NoPrimaryClip -> ClipboardSuppressionState.NotSuppressed
            is ClipboardSourceObservation.Observed -> ClipboardSuppressionState.Suppressed(source.marker)
        }
        activationStore.writeSuppression(suppression)
        repository.clearAll()
        activationStore.writeActivation(ClipboardActivation.ENABLED)
        resetQueue()
        attachListener()
    }

    suspend fun disableAndPurge() {
        if (!activationStore.writeActivation(ClipboardActivation.DISABLING)) return
        finishDisable()
    }

    private suspend fun finishDisable() {
        gateway.stopListening()
        stopQueue()
        repository.clearAll()
        activationStore.writeSuppression(ClipboardSuppressionState.NotSuppressed)
        activationStore.writeActivation(ClipboardActivation.DISABLED)
        resetQueue(start = false)
    }

    private fun attachListener() {
        gateway.startListening {
            val result = gateway.capturePrimaryClip()
            val suppressed = activationStore.suppression() as? ClipboardSuppressionState.Suppressed
            if (suppressed != null) {
                val current = (result.source as? ClipboardSourceObservation.Observed)?.marker
                if (current == null || compareClipboardSource(suppressed.marker, current) != ClipboardSourceChange.DEFINITELY_CHANGED) {
                    return@startListening
                }
                activationStore.writeSuppression(ClipboardSuppressionState.NotSuppressed)
            }
            enqueue(result, privateEditor)
        }
    }

    private fun enqueue(result: ClipboardGatewayResult, privateEditor: Boolean): Boolean {
        if (!permits.tryAcquire()) return false
        val sent = queue.trySend(Work(result, privateEditor)).isSuccess
        if (!sent) permits.release()
        return sent
    }

    private fun startQueue() {
        if (consumer?.isActive == true) return
        consumer = scope.launch {
            for (work in queue) {
                try {
                    pipeline.process(work.result, work.privateEditor)
                } finally {
                    permits.release()
                }
            }
        }
    }

    private suspend fun stopQueue() {
        queue.close()
        consumer?.cancel()
        consumer?.join()
        while (true) {
            val work = queue.tryReceive().getOrNull() ?: break
            @Suppress("UNUSED_VARIABLE") val ignored = work
            permits.release()
        }
        consumer = null
    }

    private fun resetQueue(start: Boolean = true) {
        queue = Channel(Channel.UNLIMITED)
        permits = Semaphore(ClipboardLimits.INGEST_QUEUE_CAPACITY)
        if (start) startQueue()
    }
}
