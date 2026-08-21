# Private Clipboard History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an opt-in, persistent Android clipboard history with text, HTML, links, media, files and ordered groups; sensitive entries stay masked but paste exactly; storage uses Android's private app sandbox without application-layer encryption.

**Architecture:** Room owns structured data and state transitions. Media and documents live under `noBackupFilesDir/clipboard` with atomic private-file writes. One application-scoped controller owns the system listener and bounded FIFO; a non-exported provider grants short-lived read access to the current editor. The approved smart rail renders translation, copy prompt, suggestions or empty state in that order.

**Tech Stack:** Kotlin 2.0.21, Android API 24–34, Room 2.8.4/KSP2, coroutines 1.9.0, RecyclerView, Robolectric, AndroidX instrumentation.

**Spec:** `docs/superpowers/specs/2026-08-20-keyboard-feedback-clipboard-design.md`

## Global Constraints

- No AES, Android Keystore, SQLCipher, HMAC key or application-layer encryption.
- Room database stays in internal app storage; payload files stay only in `noBackupFilesDir/clipboard`.
- `android:allowBackup="false"`; legacy and modern backup rules exclude `database`, `file`, `sharedpref`, cloud backup and device transfer.
- History is disabled by default and registers no clipboard listener before explicit consent.
- Limits: 32 items/group, 32 active+queued ingestions, 25 MiB/entry, 500 non-pinned entries, 250 MiB non-pinned total, 30 s ingestion timeout, 128 KiB direct UTF-8 commit, 60 s grant window, 3 opens.
- Sensitive content is stored, masked in every UI, excluded from search/suggestions, and pasted exactly only by explicit action.
- No payload in logs, exceptions, toasts, TalkBack descriptions, saved state, network, external storage or caches.
- Payload-bearing classes override `toString()` with redacted output; generated `data class.toString()` is allowed only for metadata-only types.
- API 31+ anti-reimport uses a persisted source timestamp and fails closed on equal/unavailable evidence; API 24–30 uses the next listener callback as legacy change proof.
- Tests precede production changes. Every task ends with focused tests, `git diff --check`, a commit and a clean tracked worktree.

---

### Task 1: Finalize the defensive gateway contract

**Files:**
- Modify: `app/src/main/java/ovh/jefe/keyboard/clipboard/SystemClipboardGateway.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardModels.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/clipboard/SystemClipboardGatewayTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardIngestPolicyTest.kt`

**Interfaces:**
- Consumes: existing bounded snapshots and `ClipboardSourceMarker`.
- Produces: `ClipboardSourceObservation`, total gateway results and the final non-crypto failure set.

- [ ] **Step 1: Add RED tests for source presence and simplified failures**

```kotlin
@Test fun `no primary clip is not a suppressed source`() {
    val result = gateway(primary = null).capturePrimaryClip()
    assertEquals(ClipboardSourceObservation.NoPrimaryClip, result.source)
}

@Test fun `rejected observed clip retains its source marker`() {
    val result = gateway(overSizedClip(timestamp = 100L)).capturePrimaryClip()
    assertEquals(
        ClipboardSourceObservation.Observed(ClipboardSourceMarker.PlatformTimestamp(100L)),
        result.source,
    )
}

@Test fun `unknown observed source stays distinct from no clip`() {
    val result = gateway(validClip(), markerReaderThrows = true).capturePrimaryClip()
    assertEquals(
        ClipboardSourceObservation.Observed(ClipboardSourceMarker.TimestampUnavailable),
        result.source,
    )
}
```

Also assert `ClipboardFailure` no longer contains `KEY_UNAVAILABLE`; all gateway/snapshot `toString()` values omit label, text, HTML and URI.

- [ ] **Step 2: Run the focused RED**

Run: `./gradlew testDebugUnitTest --tests '*SystemClipboardGatewayTest' --tests '*ClipboardIngestPolicyTest' --no-daemon`

Expected: FAIL on missing `ClipboardSourceObservation` and obsolete failure contract.

- [ ] **Step 3: Implement the total observation contract**

```kotlin
internal sealed interface ClipboardSourceObservation {
    data object NoPrimaryClip : ClipboardSourceObservation
    class Observed(val marker: ClipboardSourceMarker) : ClipboardSourceObservation {
        override fun equals(other: Any?) = other is Observed && marker == other.marker
        override fun hashCode() = marker.hashCode()
        override fun toString() = "ClipboardSourceObservation.Observed(redacted)"
    }
}

internal sealed interface ClipboardGatewayResult {
    val source: ClipboardSourceObservation
    class Empty(override val source: ClipboardSourceObservation) : ClipboardGatewayResult
    class Captured(val snapshot: SystemClipSnapshot) : ClipboardGatewayResult {
        override val source = ClipboardSourceObservation.Observed(snapshot.sourceMarker)
    }
    class Failure(
        val failure: ClipboardFailure,
        override val source: ClipboardSourceObservation,
    ) : ClipboardGatewayResult
}
```

`primaryClip == null` returns `Empty(NoPrimaryClip)`. Once a description is observed, acquire the marker first and every later result uses `Observed(marker)`. Remove `KEY_UNAVAILABLE`; preserve all single-read, immutable-list, group-policy, sensitive-flag and listener regressions.

- [ ] **Step 4: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*SystemClipboardGatewayTest' --tests '*ClipboardIngestPolicyTest' --no-daemon`

Expected: PASS.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/SystemClipboardGateway.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardModels.kt app/src/test/java/ovh/jefe/keyboard/clipboard/SystemClipboardGatewayTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardIngestPolicyTest.kt
git commit -m "fix: finalize clipboard source observations"
```

---

### Task 2: Add the Room schema and private payload store

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardDatabase.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardDao.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPrivateFileStore.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardDatabaseTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPrivateFileStoreTest.kt`
- Generate: `app/schemas/ovh.jefe.keyboard.clipboard.ClipboardDatabase/1.json`

**Interfaces:**
- Produces: Room entities/DAO/database and `ClipboardPrivateFileStore`.
- Consumes: Task 1 IDs, kinds, storage states and limits.

- [ ] **Step 1: Write RED schema and file-store tests**

The Android test inserts an entry with two ordered items, reopens the database and asserts order, metadata, redacted `toString()`, state filtering and schema version. The JVM/Robolectric test asserts staging/final paths are descendants of `context.noBackupFilesDir/clipboard`, rejects `..`/absolute/provider names, renames `.part` to `.blob`, removes partials on failure, and lists orphan `.blob` IDs.

```kotlin
@Test fun ready_entries_hide_internal_states() = runTest {
    dao.insertEntry(entry(state = ClipboardStorageState.STAGING))
    assertTrue(dao.observeReady().first().isEmpty())
    dao.updateState("entry-1", ClipboardStorageState.READY)
    assertEquals(listOf("entry-1"), dao.observeReady().first().map { it.id })
}

@Test fun file_store_never_uses_provider_name() {
    val staged = store.createStaged("../../secret.pdf")
    assertTrue(staged.path.startsWith(context.noBackupFilesDir.resolve("clipboard").path))
    assertFalse(staged.name.contains("secret"))
}
```

- [ ] **Step 2: Run RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardPrivateFileStoreTest' assembleDebugAndroidTest --no-daemon`

Expected: FAIL on missing store/database symbols.

- [ ] **Step 3: Implement schema and DAO**

Use non-data Room entity classes with redacted `toString()`:

```kotlin
@Entity(tableName = "clipboard_entries")
internal class ClipboardEntryEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val lastCopiedAt: Long,
    val kind: String,
    val itemCount: Int,
    val isPinned: Boolean,
    val isSensitive: Boolean,
    val storedByteSize: Long,
    val revision: Long,
    val fingerprintSha256: String,
    val storageState: String,
) { override fun toString() = "ClipboardEntryEntity(id=<redacted>, kind=$kind)" }

@Entity(
    tableName = "clipboard_items",
    primaryKeys = ["entryId", "itemIndex"],
    foreignKeys = [ForeignKey(
        entity = ClipboardEntryEntity::class,
        parentColumns = ["id"], childColumns = ["entryId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal class ClipboardItemEntity(
    val entryId: String,
    val itemIndex: Int,
    val mimeType: String,
    val textPayload: String?,
    val htmlPayload: String?,
    val blobId: String?,
    val safeDisplayName: String?,
    val plainByteSize: Long,
) { override fun toString() = "ClipboardItemEntity(redacted)" }
```

DAO methods include `observeReady`, `loadReady`, `findReadyByFingerprint`, `insertEntryAndItems`, `updateDuplicate`, `setPinned`, `markSensitive`, `markRevoking`, `markDeleting`, `deleteById`, quota totals, oldest unpinned IDs, managed blob IDs and non-ready rows.

- [ ] **Step 4: Implement private files**

`ClipboardPrivateFileStore` owns one validated root, creates random UUID `.part`, returns a bounded output stream, calls `FileDescriptor.sync()` before same-directory rename to `.blob`, and exposes only `openFinal(blobId)`, `delete(blobId)`, `deletePartials()` and `listFinalIds()`. No provider string enters a path.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardPrivateFileStoreTest' assembleDebugAndroidTest kspDebugKotlin --no-daemon`

Expected: PASS and schema JSON generated.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardDatabase.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardDao.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPrivateFileStore.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardDatabaseTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPrivateFileStoreTest.kt app/schemas
git commit -m "feat: add private clipboard storage"
```

---

### Task 3: Implement bounded ingestion and repository behavior

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardIngestor.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardRepository.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardIngestorTest.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardRepositoryTest.kt`

**Interfaces:**
- Produces: `ClipboardIngestor.prepare`, `ClipboardRepository` and `RoomClipboardRepository`.
- Consumes: gateway/policy, DAO and private store.

- [ ] **Step 1: Write RED ingestion tests**

Cover exact text/HTML/link, mixed content URIs with per-item MIME, ordered groups, one-read streams, 25 MiB boundary, 33-item rejection, 30 s timeout, cancellation cleanup, SecurityException, provider stall, invalid MIME/name, sampled thumbnail bounds and no partials. Use injected `ContentStreamSource` and virtual time.

```kotlin
internal interface ContentStreamSource {
    suspend fun metadata(uri: Uri): SourceMetadata
    suspend fun open(uri: Uri): InputStream
}

internal class ClipboardIngestor(
    private val source: ContentStreamSource,
    private val files: ClipboardPrivateFileStore,
    private val clock: () -> Long,
) {
    suspend fun prepare(
        snapshot: SystemClipSnapshot,
        decision: ClipboardPolicyDecision.Accept,
    ): PrepareResult
}
```

- [ ] **Step 2: Write RED repository tests**

With a temporary real Room database/store, cover STAGING→READY, duplicate moves to top, pin retained, sensitivity OR/preview removal, 501st purge, 250 MiB purge, pinned never auto-purged, individual 25 MiB pinned accepted, unpin impact confirmation, manual delete, clear, crash reconciliation for STAGING/REVOKING/DELETING, missing/orphan files and exact `storedByteSize`.

- [ ] **Step 3: Run RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardIngestorTest' assembleDebugAndroidTest --no-daemon`

Expected: FAIL on missing ingestion/repository contracts.

- [ ] **Step 4: Implement canonical preparation**

`PreparedClipboardEntry` is `Closeable`, redacts `toString()`, owns staged files until `consume()`, stores ordered prepared items, canonical manifest UTF-8 bytes and a streaming SHA-256 fingerprint over role, MIME, order, length and payload bytes. It closes streams and deletes `.part` on failure/cancel/timeout.

- [ ] **Step 5: Implement repository transactions**

```kotlin
internal interface ClipboardRepository {
    fun observe(): Flow<ClipboardHistoryState>
    suspend fun store(prepared: PreparedClipboardEntry): StoreResult
    suspend fun load(id: ClipboardEntryId): LoadedClipboardEntry?
    suspend fun setPinned(id: ClipboardEntryId, pinned: Boolean): PinResult
    suspend fun markSensitive(id: ClipboardEntryId): Boolean
    suspend fun delete(id: ClipboardEntryId): Boolean
    suspend fun clearAll()
    suspend fun search(query: String, generation: Long): SearchResult
    suspend fun reconcile()
}
```

Serialize mutations with a `Mutex`. Publish only READY. On duplicate, OR sensitivity, keep pin, update time/revision and delete unused staged files. For a new entry, insert STAGING, finalize files, then atomically READY plus mark quota victims DELETING; delete victim files/rows. Search filters `isSensitive=0` before payload loading and returns redacted models.

- [ ] **Step 6: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardIngestorTest' assembleDebugAndroidTest --no-daemon`

Expected: PASS.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardIngestor.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardRepository.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardIngestorTest.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardRepositoryTest.kt
git commit -m "feat: ingest and retain clipboard history"
```

---

### Task 4: Add opt-in lifecycle, FIFO and destructive barriers

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryController.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardComponent.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryControllerTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/src/test/java/ovh/jefe/keyboard/SettingsPrivacyTest.kt`

**Interfaces:**
- Produces: one application-scoped controller/component, activation store and admin operations.
- Consumes: Tasks 1–3.

- [ ] **Step 1: Write RED lifecycle/FIFO tests**

Cover Disabled initial, no listener before enable, durable enable before listener, one current-clip import, restart import, privacy snapshot per queued clip, strict FIFO A-slow/B-fast, 32 including active, visible overflow, one listener across two service instances, clear/disable cancel+join before purge, crash resume, and anti-reimport observations.

```kotlin
internal sealed interface ClipboardSuppressionState {
    data object NotSuppressed : ClipboardSuppressionState
    class Suppressed(val marker: ClipboardSourceMarker) : ClipboardSuppressionState
}

internal interface ClipboardActivationStore {
    fun activation(): ClipboardActivation
    fun writeActivation(value: ClipboardActivation): Boolean
    fun suppression(): ClipboardSuppressionState
    fun writeSuppression(value: ClipboardSuppressionState): Boolean
}
```

Exact clear order: persist `CLEARING_ENABLED`; stop listener; cancel/join active+queued; capture source; write `NotSuppressed` for `NoPrimaryClip` or `Suppressed(marker)` for `Observed`; revoke access/cache participants; clear repository/files; write `ENABLED`; restart empty FIFO/listener without importing old clip.

- [ ] **Step 2: Run RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardHistoryControllerTest' --no-daemon`

Expected: FAIL on missing controller/component.

- [ ] **Step 3: Implement one restartable application component**

Use `Channel.UNLIMITED` only behind a `Semaphore(32)`: acquire before enqueue and release after the active item finishes, so active+queued never exceeds 32. One consumer preserves FIFO. `ClipboardComponent.get(applicationContext)` is lazy under a lock and never recreated by Settings or the IME service.

Activation states: `DISABLED`, `ENABLED`, `CLEARING_ENABLED`, `DISABLING`. Startup resumes destructive states before listener attachment. `disableAndPurge()` finishes with `DISABLED` and `NotSuppressed`; later explicit enable imports the current clip.

- [ ] **Step 4: Lock backup behavior**

Keep `android:allowBackup="false"`. In both XML rule formats exclude all `database`, `file` and `sharedpref` content for cloud/device transfer. Tests parse merged manifest/resources and assert no clipboard path is eligible.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardHistoryControllerTest' --tests '*SettingsPrivacyTest' lintDebug assembleDebug --no-daemon`

Expected: PASS.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryController.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardComponent.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryControllerTest.kt app/src/main/AndroidManifest.xml app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml app/src/test/java/ovh/jefe/keyboard/SettingsPrivacyTest.kt
git commit -m "feat: control clipboard history lifecycle"
```

---

### Task 5: Add temporary grants and paste coordination

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardGrantRegistry.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardContentProvider.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPasteCoordinator.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardGrantRegistryTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPasteCoordinatorTest.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardProviderTest.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: editor session registry, temporary grants and exact paste results.
- Consumes: repository READY entries and `InputBinding.uid`/`EditorInfo.packageName`.

- [ ] **Step 1: Write RED grant tests**

Cover 128-bit token uniqueness, payload MIME/size/order, wrong UID/session/package/token, deletion/expiry, 3 attached opens finishing, 4th rejected, revoke-vs-attach, active pipe cancellation and generic sensitive metadata.

- [ ] **Step 2: Write RED paste tests**

Cover direct text/link ≤128 KiB, large text via `text/plain` provider only when supported, HTML rich then fallback, MIME rejection before mutation, rich item, ordered group item, textual `Coller tout`, session/connection change during load, editor false returns and sensitive success flag.

```kotlin
internal sealed interface ClipboardPasteResult {
    data class Success(val sensitive: Boolean) : ClipboardPasteResult
    data class Failure(val failure: ClipboardFailure) : ClipboardPasteResult
}

internal data class EditorTarget(
    val sessionId: Long,
    val uid: Int,
    val packageName: String,
    val inputConnection: InputConnection,
    val editorInfo: EditorInfo,
)
```

- [ ] **Step 3: Run RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardGrantRegistryTest' --tests '*ClipboardPasteCoordinatorTest' assembleDebugAndroidTest --no-daemon`

Expected: FAIL on missing grant/provider/paste contracts.

- [ ] **Step 4: Implement provider and coordinator**

Provider: `exported=false`, `grantUriPermissions=true`, authority `${applicationId}.clipboard`, no intent filter. `query/getType/openFile/openTypedAssetFile` resolve only issued tokens. Pipe writers stream repository/private files and close on cancellation.

Coordinator revalidates exact session, connection identity, UID and package immediately before every commit/grant. A failed editor call stops; no fallback follows a mutation attempt.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardGrantRegistryTest' --tests '*ClipboardPasteCoordinatorTest' assembleDebugAndroidTest --no-daemon`

Expected: PASS.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardGrantRegistry.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardContentProvider.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPasteCoordinator.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardGrantRegistryTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPasteCoordinatorTest.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardProviderTest.kt app/src/main/AndroidManifest.xml
git commit -m "feat: paste private clipboard content"
```

---

### Task 6: Build the panel, consent and settings

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPanelController.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPanelView.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryActivity.kt`
- Create: `app/src/main/res/layout/clipboard_panel.xml`
- Create: `app/src/main/res/layout/clipboard_tile.xml`
- Create: `app/src/main/res/drawable/bg_clipboard_tile.xml`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPanelTest.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/KeyboardRootView.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/SettingsActivity.kt`
- Modify: `app/src/main/res/xml/preferences.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/test/java/ovh/jefe/keyboard/SettingsActivityTest.kt`

**Interfaces:**
- Produces: panel modes/actions and non-exported management activity.
- Consumes: repository/controller/paste coordinator and Ink theme.

- [ ] **Step 1: Write RED panel/UI tests**

Cover Disabled consent, enable, Loading/Empty/Error, Pinned/Recent/Older, 2-column tiles, 44 dp, text/file/media/group previews, sensitive generic tile with type/size/time and zero payload load, search generation ordering, pin/unpin/delete/clear, management activity paste disabled, root modes and light/dark contrast.

- [ ] **Step 2: Run RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardPanelTest' --tests '*SettingsActivityTest' --no-daemon`

Expected: FAIL on missing panel/activity.

- [ ] **Step 3: Implement native panel**

`ClipboardPanelController` owns a panel scope and redacted UI models. It checks `isSensitive` before loading payload. IME search is a plain string fed by keyboard callbacks, never an `EditText`; management activity may use `EditText` and never receives an `InputConnection`.

`KeyboardRootView` modes: `KEYBOARD`, `CLIPBOARD`, `CLIPBOARD_SEARCH`. Back returns one level. Reuse Ink semantic colors in `values`/`values-night`; no literal white panel surface.

- [ ] **Step 4: Implement settings/consent**

Show state, `Sans expiration · 500 éléments · 250 Mo`, `Ouvrir l’historique`, count/size, `Tout effacer`, `Désactiver et effacer`. Activation explains private local storage, no expiration, quotas, masked sensitive items and deletion. API 24–28 adds system clipboard warning. Activity is `exported=false`.

- [ ] **Step 5: Verify visuals and commit**

Run: `VISUAL_OUTPUT_DIR=/tmp/jefe-clipboard-visual ./gradlew testDebugUnitTest --tests '*ClipboardPanelTest' --tests '*SettingsActivityTest' --no-daemon`

Expected: PASS and inspected light/dark panel/settings PNGs.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPanelController.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPanelView.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryActivity.kt app/src/main/res/layout/clipboard_panel.xml app/src/main/res/layout/clipboard_tile.xml app/src/main/res/drawable/bg_clipboard_tile.xml app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPanelTest.kt app/src/main/java/ovh/jefe/keyboard/KeyboardRootView.kt app/src/main/java/ovh/jefe/keyboard/SettingsActivity.kt app/src/main/res/xml/preferences.xml app/src/main/res/values/strings.xml app/src/main/AndroidManifest.xml app/src/test/java/ovh/jefe/keyboard/SettingsActivityTest.kt
git commit -m "feat: add clipboard history interface"
```

---

### Task 7: Integrate copy prompts and sensitive session taint

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPromptController.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPromptControllerTest.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/SuggestionPolicy.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/TopRailState.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/TopRailStateTest.kt`

**Interfaces:**
- Produces: final service wiring, 20-second visible prompt and session taint.
- Consumes: all previous clipboard tasks and rail priority.

- [ ] **Step 1: Write RED prompt tests**

Cover preview/type matrix for text/link/HTML/image/audio/video/file/group/sensitive, bidi/control sanitization, 256-char bound, `Coller`+type always visible, 20 seconds actual KEYBOARD+prompt visibility, pause under Translation/panel/hidden, replacement, dismiss/type/session expiry and no full payload in accessibility text.

- [ ] **Step 2: Write RED service tests**

Cover shared component across service recreation, privacy start/finish updates, clipboard tab, prompt paste/dismiss, panel paste, sensitive exact paste followed by persistent suggestion+remote taint until next valid `onStartInput`, non-sensitive paste enabling suggestions, retired root callbacks, and zero clipboard dependency on network clients.

- [ ] **Step 3: Run RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardPromptControllerTest' --tests '*JefeKeyboardServiceTest' --tests '*TopRailStateTest' --no-daemon`

Expected: FAIL on missing prompt/service contracts.

- [ ] **Step 4: Implement prompt/service ownership**

Priority stays `Translation > ClipboardPrompt > Suggestions > Empty`. Countdown schedules remaining visible time and cancels/restarts on visibility transitions. Service supplies privacy policy and authoritative `EditorTarget`, and retires callbacks on root/session replacement.

Sensitive paste sets a session Boolean forcing zero suggestions and disabling translation/dictation until a new non-private `onStartInput`; exact payload is unchanged.

- [ ] **Step 5: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardPromptControllerTest' --tests '*JefeKeyboardServiceTest' --tests '*TopRailStateTest' --no-daemon`

Expected: PASS.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPromptController.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPromptControllerTest.kt app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt app/src/main/java/ovh/jefe/keyboard/SuggestionPolicy.kt app/src/main/java/ovh/jefe/keyboard/TopRailState.kt app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt app/src/test/java/ovh/jefe/keyboard/TopRailStateTest.kt
git commit -m "feat: integrate clipboard with the keyboard"
```

---

### Task 8: Run device matrix, inspect APK and deliver main

**Files:**
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardLifecycleInstrumentedTest.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardPrivacyInstrumentedTest.kt`
- Modify: `.github/workflows/build.yml`
- Create: `docs/testing/clipboard-release-checklist.md`

**Interfaces:**
- Produces: device evidence and installable debug APK.
- Consumes: completed application.

- [ ] **Step 1: Add architecture/device RED tests**

Instrumented tests activate, import text/HTML/image/group, restart, search, paste, change session, revoke grant, clear, restart, copy new C, disable and verify listener/database/files empty. Privacy test inspects paths/permissions/backup rules, masks sensitive UI while exact paste succeeds, checks zero network calls, and uses a distinct-UID helper for provider rejection.

Workflow test requires unit, lint, assemble, androidTest compilation, API 24 connected and API 34 connected before artifact upload.

- [ ] **Step 2: Run API 24 and API 34 gates**

Run on each emulator: `./gradlew connectedDebugAndroidTest --no-daemon`

Expected: all tests PASS; no skipped clipboard lifecycle/privacy class.

- [ ] **Step 3: Run clean final build**

Run: `./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --no-daemon`

Expected: unit tests 0 failures, lint 0 errors, APKs built.

- [ ] **Step 4: Inspect artifacts**

Verify `app-debug.apk`: package `ovh.jefe.keyboard`, minSdk 24, targetSdk 34, Settings launcher exported, IME exported with `BIND_INPUT_METHOD`, clipboard provider/activity not exported, `allowBackup=false`, both backup rules packaged, v2 signature valid, SHA-256 recorded. Regenerate and inspect keyboard/prompt/panel/settings PNGs in light/dark.

- [ ] **Step 5: Commit final gates**

```bash
git diff --check
git add app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardLifecycleInstrumentedTest.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardPrivacyInstrumentedTest.kt .github/workflows/build.yml docs/testing/clipboard-release-checklist.md
git commit -m "test: verify clipboard history delivery"
```

- [ ] **Step 6: Fast-forward main without force**

Fetch `origin/main`. If it advanced, merge it and rerun Steps 2–4. Confirm `origin/main` is an ancestor of verified HEAD, then run `git push origin HEAD:main`.

Expected: fast-forward accepted; remote `main` SHA equals verified HEAD. Never force-push and never clean/reset the user's main checkout.
