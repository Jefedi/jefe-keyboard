package ovh.jefe.keyboard.clipboard

internal object ClipboardLimits {
    const val MAX_GROUP_ITEMS = 32
    const val INGEST_QUEUE_CAPACITY = 32
    const val MEBIBYTE = 1_048_576L
    const val MAX_ENTRY_BYTES = 25L * MEBIBYTE
    const val MAX_UNPINNED_ENTRIES = 500
    const val MAX_UNPINNED_BYTES = 250L * MEBIBYTE
    const val INGEST_TIMEOUT_MILLIS = 30_000L
    const val GRANT_WINDOW_MILLIS = 60_000L
    const val MAX_GRANT_OPENS = 3
    const val MAX_MIME_CHARS = 255
    const val MAX_MIME_TYPES = 64
    const val MAX_URI_CHARS = 8_192
    const val MAX_LABEL_CHARS = 4_096
    const val MAX_SNAPSHOT_TEXT_CHARS = 25 * 1_048_576
    const val MAX_DIRECT_COMMIT_TEXT_UTF8_BYTES = 128 * 1_024
    const val MAX_PREVIEW_CHARS = 256
    const val INLINE_TEXT_BYTES = 64 * 1_024
}
