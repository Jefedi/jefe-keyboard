package ovh.jefe.keyboard.clipboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ovh.jefe.keyboard.ClipboardPromptUi

internal class ClipboardPromptController(
    private val repository: ClipboardRepository,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val mutablePrompt = MutableStateFlow<ClipboardPromptUi?>(null)
    val prompt: StateFlow<ClipboardPromptUi?> = mutablePrompt
    private var remainingMillis = PROMPT_VISIBLE_MILLIS
    private var visibleSince: Long? = null
    private var expiryJob: Job? = null
    private var observedHistory = false
    private var latestSignature: Pair<String, Long>? = null

    init {
        scope.launch {
            repository.observe().collect { state ->
                if (state is ClipboardHistoryState.Ready && state.entries.isNotEmpty()) {
                    val latest = state.entries.first()
                    val signature = latest.id.value to latest.revision
                    if (observedHistory && signature != latestSignature) present(latest)
                    latestSignature = signature
                }
                observedHistory = true
            }
        }
    }

    suspend fun present(summary: ClipboardEntrySummary) {
        val preview = if (summary.isSensitive) {
            "Contenu sensible"
        } else {
            val loaded = repository.load(summary.id)
            loaded?.use { entry ->
                val item = entry.items.firstOrNull()
                sanitize(item?.textPayload ?: item?.safeDisplayName.orEmpty())
            }.orEmpty().ifBlank { typeLabel(summary.kind) }
        }
        mutablePrompt.value = ClipboardPromptUi(
            entryId = summary.id.value,
            preview = preview,
            typeLabel = typeLabel(summary.kind),
            contentDescription = if (summary.isSensitive) {
                "Coller le contenu sensible · ${typeLabel(summary.kind)}"
            } else {
                "Coller $preview · ${typeLabel(summary.kind)}"
            },
            isSensitive = summary.isSensitive,
        )
        remainingMillis = PROMPT_VISIBLE_MILLIS
        visibleSince = null
        expiryJob?.cancel()
    }

    fun setVisible(visible: Boolean) {
        val prompt = mutablePrompt.value ?: return
        @Suppress("UNUSED_VARIABLE") val retained = prompt
        if (visible) {
            if (visibleSince != null) return
            visibleSince = nowMillis()
            scheduleExpiry()
        } else {
            pauseTimer()
        }
    }

    fun dismiss() {
        expiryJob?.cancel()
        expiryJob = null
        visibleSince = null
        remainingMillis = PROMPT_VISIBLE_MILLIS
        mutablePrompt.value = null
    }

    private fun scheduleExpiry() {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(remainingMillis.coerceAtLeast(1L))
            val started = visibleSince ?: return@launch
            remainingMillis -= (nowMillis() - started).coerceAtLeast(0L)
            if (remainingMillis <= 0L) dismiss()
        }
    }

    private fun pauseTimer() {
        val started = visibleSince ?: return
        remainingMillis = (remainingMillis - (nowMillis() - started).coerceAtLeast(0L)).coerceAtLeast(0L)
        visibleSince = null
        expiryJob?.cancel()
        expiryJob = null
        if (remainingMillis == 0L) dismiss()
    }

    private fun sanitize(value: String): String = value
        .asSequence()
        .filterNot { it.isISOControl() || it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 }
        .take(MAX_PROMPT_CHARS)
        .joinToString("")

    private fun typeLabel(kind: ClipboardKind): String = when (kind) {
        ClipboardKind.TEXT -> "Texte"
        ClipboardKind.LINK -> "Lien"
        ClipboardKind.HTML -> "HTML"
        ClipboardKind.IMAGE -> "Image"
        ClipboardKind.VIDEO -> "Vidéo"
        ClipboardKind.AUDIO -> "Audio"
        ClipboardKind.FILE -> "Fichier"
        ClipboardKind.GROUP -> "Groupe"
    }

    private companion object {
        const val PROMPT_VISIBLE_MILLIS = 20_000L
        const val MAX_PROMPT_CHARS = 80
    }
}
