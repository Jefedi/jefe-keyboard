package ovh.jefe.keyboard.clipboard

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

internal class ClipboardTileUi(
    val id: String,
    val title: String,
    val detail: String,
    val isPinned: Boolean,
    val isSensitive: Boolean,
) {
    override fun toString(): String = "ClipboardTileUi(sensitive=$isSensitive, redacted=true)"
}

internal sealed interface ClipboardPanelUiState {
    data object Disabled : ClipboardPanelUiState
    data object Loading : ClipboardPanelUiState
    data object Empty : ClipboardPanelUiState
    class Ready(val tiles: List<ClipboardTileUi>) : ClipboardPanelUiState
    data object Error : ClipboardPanelUiState
}

internal class ClipboardPanelController(
    private val repository: ClipboardRepository,
    private val historyController: ClipboardHistoryController,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<ClipboardPanelUiState>(ClipboardPanelUiState.Loading)
    val state: StateFlow<ClipboardPanelUiState> = mutableState
    private var collectionJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (historyController.activation() != ClipboardActivation.ENABLED) {
            mutableState.value = ClipboardPanelUiState.Disabled
            return
        }
        collectionJob?.cancel()
        collectionJob = scope.launch {
            repository.observe().collectLatest { history ->
                mutableState.value = when (history) {
                    ClipboardHistoryState.Disabled -> ClipboardPanelUiState.Disabled
                    ClipboardHistoryState.Loading -> ClipboardPanelUiState.Loading
                    ClipboardHistoryState.Empty -> ClipboardPanelUiState.Empty
                    is ClipboardHistoryState.Error -> ClipboardPanelUiState.Error
                    is ClipboardHistoryState.Ready -> ClipboardPanelUiState.Ready(
                        history.entries.map { summary -> tile(summary) },
                    )
                }
            }
        }
    }

    fun enable() = scope.launch {
        historyController.enable(privateEditor = true)
        refresh()
    }

    fun clear() = scope.launch { historyController.clearAndResume() }

    fun setPinned(id: String, pinned: Boolean) = scope.launch {
        repository.setPinned(ClipboardEntryId(id), pinned, confirmImpact = true)
    }

    fun delete(id: String) = scope.launch { repository.delete(ClipboardEntryId(id)) }

    private suspend fun tile(summary: ClipboardEntrySummary): ClipboardTileUi {
        val type = typeLabel(summary.kind)
        if (summary.isSensitive) {
            return ClipboardTileUi(
                summary.id.value,
                "Contenu sensible",
                "$type · ${formatBytes(summary.storedByteSize)}",
                summary.isPinned,
                true,
            )
        }
        val loaded = repository.load(summary.id)
        val preview = loaded?.use { entry ->
            entry.items.firstOrNull()?.let { item ->
                sanitize(item.textPayload ?: item.safeDisplayName.orEmpty()).ifBlank { type }
            }
        } ?: type
        return ClipboardTileUi(
            summary.id.value,
            preview,
            "$type · ${formatBytes(summary.storedByteSize)}",
            summary.isPinned,
            false,
        )
    }

    private fun sanitize(value: String): String = value
        .asSequence()
        .filterNot { it.isISOControl() || it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 }
        .take(72)
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

    private fun formatBytes(value: Long): String = when {
        value >= ClipboardLimits.MEBIBYTE -> "${value / ClipboardLimits.MEBIBYTE} Mo"
        value >= 1024 -> "${value / 1024} Ko"
        else -> "$value o"
    }
}
