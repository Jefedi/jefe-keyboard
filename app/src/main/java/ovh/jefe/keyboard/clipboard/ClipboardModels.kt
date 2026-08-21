package ovh.jefe.keyboard.clipboard

@JvmInline
internal value class ClipboardEntryId(val value: String)

internal enum class ClipboardKind { TEXT, LINK, HTML, IMAGE, VIDEO, AUDIO, FILE, GROUP }

internal enum class ClipboardStorageState { STAGING, READY, PROMOTING, REVOKING, DELETING }

internal sealed interface ClipboardHistoryState {
    data object Disabled : ClipboardHistoryState
    data object Loading : ClipboardHistoryState
    data object Empty : ClipboardHistoryState
    data class Ready(val entries: List<ClipboardEntrySummary>) : ClipboardHistoryState
    data class Error(val failure: ClipboardFailure, val canRetry: Boolean) : ClipboardHistoryState
}

internal data class ClipboardEntrySummary(
    val id: ClipboardEntryId,
    val kind: ClipboardKind,
    val itemCount: Int,
    val isPinned: Boolean,
    val isSensitive: Boolean,
    val storedByteSize: Long,
    val lastCopiedAt: Long,
    val revision: Long,
)

internal data class ClipboardHistoryStats(
    val readyCount: Int,
    val totalStoredBytes: Long,
    val unpinnedCount: Int,
    val unpinnedStoredBytes: Long,
)

internal enum class ClipboardFailure(val safeMessage: String) {
    EMPTY("Presse-papiers vide"),
    UNSUPPORTED("Format de presse-papiers non pris en charge"),
    ACCESS_DENIED("Contenu non enregistré : accès refusé"),
    TOO_MANY_ITEMS("Contenu non enregistré : presse-papiers saturé"),
    INVALID_METADATA("Métadonnées de presse-papiers invalides"),
    ENTRY_TOO_LARGE("Contenu non enregistré : limite de 25 Mo"),
    QUEUE_SATURATED("Contenu non enregistré : presse-papiers saturé"),
    TIMED_OUT("Contenu non enregistré : délai dépassé"),
    PINNED_STORAGE_FULL("Espace du presse-papiers insuffisant · Gérer les épinglés"),
    DATABASE_UNAVAILABLE("Historique momentanément indisponible"),
    CORRUPT_ENTRY("Contenu enregistré indisponible"),
    MIME_REJECTED("Cette application n’accepte pas ce contenu"),
    TEXT_TOO_LARGE_FOR_EDITOR("Cette application ne peut pas recevoir ce texte volumineux"),
    EDITOR_REJECTED("L’éditeur a refusé le contenu"),
}
