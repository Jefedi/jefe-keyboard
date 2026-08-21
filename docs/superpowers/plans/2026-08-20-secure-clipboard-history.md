# Plan d’implémentation de l’historique sécurisé du presse-papiers

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ajouter un historique opt-in, local, chiffré et sans expiration temporelle, capable de conserver texte, HTML, liens, médias, fichiers et groupes, de les rechercher sans fuite, puis de les coller de façon compatible avec l’éditeur.

**Architecture:** `SystemClipboardGateway` capture un instantané défensif, `ClipboardHistoryController` sérialise chaque événement dans une FIFO, `ClipboardIngestor` chiffre en flux, et `RoomClipboardHistoryRepository` publie seulement les entrées `READY`. Le panneau natif consomme des modèles UI bornés ; `ClipboardContentProvider` déchiffre les contenus riches dans un pipe temporaire lié à l’UID et à la session de l’éditeur.

**Tech Stack:** Kotlin 2.0.21, AGP 8.5.2, Gradle 8.7, Android SDK 24–34, Room 2.8.4, KSP 2.0.21-1.0.28, RecyclerView 1.3.2, coroutines 1.9.0, Android Keystore, AES-256-GCM, HMAC-SHA-256, Robolectric 4.16.1, AndroidX Test 1.7.0.

**Spec:** `docs/superpowers/specs/2026-08-20-keyboard-feedback-clipboard-design.md`

## Global Constraints

- Exécuter d’abord `docs/superpowers/plans/2026-08-20-keyboard-rail-feedback.md` ; ce plan consomme `EditorPrivacyPolicy`, `KeyboardRootView`, `TopRailInputs` et `ClipboardPromptUi`.
- Conserver `minSdk = 24`, `targetSdk = 34`, Java 17, le suivi automatique du thème système et le comportement HTTPS/cancellable existant.
- L’historique est désactivé par défaut. Aucun listener n’est enregistré avant consentement explicite.
- Aucun texte, HTML, nom, miniature ou payload utilisateur n’est stocké en clair, journalisé, sauvegardé ou transféré.
- Limites fixes : 32 items par groupe, 25 MiB par entrée, 32 travaux FIFO au total (actif compris), 500 entrées et 250 MiB non épinglés. `1 MiB = 1 048 576 octets`.
- Les épinglés sont exclus des deux quotas cumulés mais restent limités à 25 MiB chacun et ne sont jamais purgés automatiquement.
- La sensibilité est monotone : `ancien.isSensitive || nouveau.isSensitive`. Un item sensible rend tout son groupe sensible.
- Les entrées sensibles restent collables en clair après appui volontaire, mais restent masquées et exclues de la recherche, des suggestions et du réseau.
- Aucun `Intent` exécutable ni URI `file://` n’est persisté. Une URI `content://` est copiée une seule fois dans le stockage privé.
- Une entrée n’est observable qu’en état `READY`. `STAGING`, `PROMOTING`, `REVOKING` et `DELETING` sont réparés au prochain démarrage.
- Le collage multi-fichier n’est jamais présenté comme atomique : un groupe riche ouvre ses items ; un groupe textuel utilise un seul `commitText` sous la borne Binder sûre ou un seul payload provider `text/plain` au-delà.
- Chaque tâche suit RED → GREEN → REFACTOR, exécute sa commande ciblée et termine par un commit propre.

---

## Carte des responsabilités

| Zone | Fichiers propriétaires | Ne doit jamais dépendre de |
|---|---|---|
| Capture/policy | `SystemClipboardGateway.kt`, `ClipboardIngestPolicy.kt` | Room, vues, réseau |
| Protection | `ClipboardKeyStore.kt`, `ClipboardCrypto.kt`, `ClipboardManifestCodec.kt` | UI, `SharedPreferences` utilisateur |
| Stockage | `ClipboardEntities.kt`, `ClipboardDatabase.kt`, `EncryptedClipboardBlobStore.kt`, `RoomClipboardHistoryRepository.kt` | `InputConnection`, réseau |
| Orchestration | `ClipboardHistoryController.kt`, `ClipboardComponent.kt` | `sessionScope` pour la FIFO |
| Collage | `ClipboardGrantRegistry.kt`, `ClipboardContentProvider.kt`, `ClipboardPasteCoordinator.kt` | fichiers clairs, URI fournisseur source |
| Présentation | `clipboard/ui/*`, `ClipboardPromptFormatter.kt`, `ClipboardPromptController.kt` | clés, ciphertext, clients distants |
| Session sensible | `SensitiveClipboardGuard.kt` | heuristiques non fiables, payload dans logs |

---

### Task 1: Socle Room/KSP et contrats de domaine bornés

**Files:**
- Modify: `build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `gradle.properties`
- Create: `app/schemas/.gitkeep`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardLimits.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardModels.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardLimitsTest.kt`

**Interfaces:**
- Produces: `ClipboardLimits`, `ClipboardEntryId`, `ClipboardKind`, `ClipboardStorageState`, `ClipboardHistoryState`, `ClipboardFailure`.
- Build contract: Room schemas are exported under `app/schemas`; Room compiler uses KSP2, not kapt.

- [ ] **Step 1: Écrire le test RED des limites exactes**

```kotlin
package ovh.jefe.keyboard.clipboard

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardLimitsTest {
    @Test
    fun `limits use binary mebibytes and approved counts`() {
        assertEquals(32, ClipboardLimits.MAX_GROUP_ITEMS)
        assertEquals(32, ClipboardLimits.INGEST_QUEUE_CAPACITY)
        assertEquals(64, ClipboardLimits.MAX_MIME_TYPES)
        assertEquals(8_192, ClipboardLimits.MAX_URI_CHARS)
        assertEquals(25L * 1_048_576L, ClipboardLimits.MAX_ENTRY_BYTES)
        assertEquals(25 * 1_048_576, ClipboardLimits.MAX_SNAPSHOT_TEXT_CHARS)
        assertEquals(128 * 1_024, ClipboardLimits.MAX_DIRECT_COMMIT_TEXT_UTF8_BYTES)
        assertEquals(500, ClipboardLimits.MAX_UNPINNED_ENTRIES)
        assertEquals(250L * 1_048_576L, ClipboardLimits.MAX_UNPINNED_BYTES)
        assertEquals(30_000L, ClipboardLimits.INGEST_TIMEOUT_MILLIS)
        assertEquals(60_000L, ClipboardLimits.GRANT_WINDOW_MILLIS)
        assertEquals(3, ClipboardLimits.MAX_GRANT_OPENS)
    }
}
```

- [ ] **Step 2: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardLimitsTest' --no-daemon`

Expected: FAIL à la compilation sur `ClipboardLimits`.

- [ ] **Step 3: Épingler les plugins et dépendances compatibles API 34**

Dans le build racine :

```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("androidx.room") version "2.8.4" apply false
}
```

Dans le module :

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

Remplacer l’unique dépendance coroutines Android `1.8.1` existante au lieu de la dupliquer. Ajouter `ksp.useKSP2=true` à `gradle.properties`.

- [ ] **Step 4: Créer les limites et modèles sans payload utilisateur**

```kotlin
package ovh.jefe.keyboard.clipboard

@JvmInline
internal value class ClipboardEntryId(val value: String)

internal enum class ClipboardKind { TEXT, LINK, HTML, IMAGE, VIDEO, AUDIO, FILE, GROUP }
internal enum class ClipboardStorageState { STAGING, READY, PROMOTING, REVOKING, DELETING }

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
    KEY_UNAVAILABLE("Historique protégé inaccessible"),
    MIME_REJECTED("Cette application n’accepte pas ce contenu"),
    TEXT_TOO_LARGE_FOR_EDITOR("Cette application ne peut pas recevoir ce texte volumineux"),
    EDITOR_REJECTED("L’éditeur a refusé le contenu"),
}
```

- [ ] **Step 5: Vérifier le GREEN et le build KSP**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardLimitsTest' kspDebugKotlin --no-daemon`

Expected: PASS ; aucun avertissement de kapt, répertoire de schémas reconnu.

- [ ] **Step 6: Contrôler et committer**

```bash
git diff --check
git add build.gradle.kts app/build.gradle.kts gradle.properties app/schemas/.gitkeep app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardLimits.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardModels.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardLimitsTest.kt
git commit -m "build: add the encrypted clipboard foundation"
```

### Task 2: Gateway Android défensif et policy d’ingestion pure

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/SystemClipboardGateway.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardIngestPolicy.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/SystemClipboardGatewayTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardIngestPolicyTest.kt`

**Interfaces:**
- Produces: `SystemClipSnapshot`, `SystemClipItemSnapshot`, `ClipboardGatewayResult`, `ClipboardSourceMarker`/`ClipboardSourceChange`, `SystemClipboardGateway.capturePrimaryClip()`.
- Produces: `ClipboardPolicyDecision.Accept/Reject` with kind, normalized MIME list and monotonic sensitivity input.
- Consumes from rail plan: `EditorPrivacyState.forceSensitiveClipboard` as a Boolean supplied by the service; the clipboard package never imports the UI.

- [ ] **Step 1: Écrire les tests RED de capture sans coercition**

```kotlin
@RunWith(RobolectricTestRunner::class)
class SystemClipboardGatewayTest {
    @Test
    fun `capture copies text html uri and sensitive metadata without coercing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = context.getSystemService(ClipboardManager::class.java)
        val description = ClipDescription("secret", arrayOf("text/html")).apply {
            extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        manager.setPrimaryClip(
            ClipData(
                description,
                ClipData.Item("fallback", "<b>fallback</b>", null, Uri.parse("content://source/1")),
            ),
        )

        val result = SystemClipboardGateway(context).capturePrimaryClip()

        val snapshot = (result as ClipboardGatewayResult.Captured).snapshot
        assertTrue(snapshot.isSensitive)
        assertEquals(listOf("text/html"), snapshot.mimeTypes)
        assertEquals("fallback", snapshot.items.single().text)
        assertEquals("<b>fallback</b>", snapshot.items.single().htmlText)
        assertEquals(Uri.parse("content://source/1"), snapshot.items.single().uri)
    }
}
```

Ajouter des tests qui vérifient `Empty` pour clip absent/vide, `Failure` générique pour `SecurityException`, copie défensive des chaînes, enregistrement/retrait idempotent du listener et absence totale d’appel à `ClipData.Item.coerceToText()`.

- [ ] **Step 2: Écrire la matrice RED de policy**

```kotlin
@Test
fun `policy classifies supported single items and groups`() {
    assertAccepted(textSnapshot("bonjour"), ClipboardKind.TEXT)
    assertAccepted(textSnapshot("https://example.com"), ClipboardKind.LINK)
    assertAccepted(htmlSnapshot("<b>x</b>", "x"), ClipboardKind.HTML)
    assertAccepted(uriSnapshot("image/png"), ClipboardKind.IMAGE)
    assertAccepted(uriSnapshot("video/mp4"), ClipboardKind.VIDEO)
    assertAccepted(uriSnapshot("audio/mp4"), ClipboardKind.AUDIO)
    assertAccepted(uriSnapshot("application/pdf"), ClipboardKind.FILE)
    assertAccepted(groupSnapshot(2), ClipboardKind.GROUP)
}

@Test
fun `policy rejects hostile or overbounded metadata before opening content`() {
    assertRejected(intentOnlySnapshot(), ClipboardFailure.UNSUPPORTED)
    assertRejected(fileUriSnapshot(), ClipboardFailure.UNSUPPORTED)
    assertRejected(groupSnapshot(33), ClipboardFailure.TOO_MANY_ITEMS)
    assertRejected(mimeSnapshot("x".repeat(256)), ClipboardFailure.INVALID_METADATA)
    assertRejected(mimeCountSnapshot(65), ClipboardFailure.INVALID_METADATA)
    assertRejected(longUriSnapshot(8_193), ClipboardFailure.INVALID_METADATA)
}

@Test
fun `one sensitive item or private editor makes the complete entry sensitive`() {
    assertTrue(accept(groupSnapshot(2, sensitiveItem = 1), privateEditor = false).isSensitive)
    assertTrue(accept(textSnapshot("1234"), privateEditor = true).isSensitive)
}
```

- [ ] **Step 3: Vérifier le RED ciblé**

Run: `./gradlew testDebugUnitTest --tests '*SystemClipboardGatewayTest' --tests '*ClipboardIngestPolicyTest' --no-daemon`

Expected: FAIL à la compilation sur les gateway/policy.

- [ ] **Step 4: Implémenter l’instantané défensif borné**

```kotlin
internal class SystemClipSnapshot(
    val capturedAtMillis: Long,
    val label: String?,
    val mimeTypes: List<String>,
    val isSensitive: Boolean,
    val items: List<SystemClipItemSnapshot>,
) {
    override fun toString(): String =
        "SystemClipSnapshot(items=${items.size}, sensitive=$isSensitive)"
}

internal class SystemClipItemSnapshot(
    val text: String?,
    val htmlText: String?,
    val uri: Uri?,
    val hasIntent: Boolean,
    val isSensitive: Boolean = false,
) {
    override fun toString(): String = "SystemClipItemSnapshot(redacted)"
}

internal sealed interface ClipboardGatewayResult {
    /** Null only when no primary clip/description was observable. */
    val sourceMarker: ClipboardSourceMarker?
    class Empty(override val sourceMarker: ClipboardSourceMarker?) : ClipboardGatewayResult
    class Captured(val snapshot: SystemClipSnapshot) : ClipboardGatewayResult {
        override val sourceMarker: ClipboardSourceMarker = snapshot.sourceMarker
    }
    class Failure(
        val failure: ClipboardFailure,
        override val sourceMarker: ClipboardSourceMarker?,
    ) : ClipboardGatewayResult
}
```

`capturePrimaryClip()` lit `primaryClip` et sa description une seule fois dans un bloc défensif, puis lit immédiatement le booléen sensible exact avant tout accès hostile au libellé ou aux items. Dès qu’une description est observée, son marqueur source est attaché à **tout** `ClipboardGatewayResult`, donc aussi à `Failure` et à `Empty` borné : l’acceptation de contenu ne peut jamais supprimer la preuve nécessaire au clear/reset, et la valeur n’est absente que sans clip/description observable. Si `itemCount > 32`, il retourne immédiatement `Failure(ClipboardFailure.TOO_MANY_ITEMS)` sans tronquer le groupe. Il refuse aussi plus de 64 MIME avant de les copier. Sinon il copie chaque représentation dès son unique lecture de `CharSequence.length` : il borne le total dans un `Long`, cache chaque unité UTF-16 une seule fois (y compris le low surrogate), puis valide/construit la chaîne sans retenir le `CharSequence` mutable (la limite UTF-8 exacte reste vérifiée en streaming à l’ingestion). Chaque URI est convertie une seule fois en chaîne, refusée au-delà de 8 192 caractères, puis reconstruite avec `Uri.parse`. Il borne label à 4 096 code points et chaque MIME à 255 avant allocation. API 24–30 fournit le marqueur legacy du callback ; API 31+ fournit le timestamp source ou `TimestampUnavailable`. La relation pure ne prétend `DEFINITELY_CHANGED` que pour le legacy documenté ou deux timestamps différents ; égal est `SAME_OR_COLLIDING` et toute absence est `UNKNOWN`. `SecurityException` et `RuntimeException` deviennent `ACCESS_DENIED` sans label/URI. Le listener conserve exactement une instance et `stopListening()` retire cette même instance. Les modèles contenant du clair sont des classes à `toString()` explicitement expurgé, jamais des `data class`; un test sentinelle exige que label, texte, HTML et URI soient absents de `toString()` et de tout message d’erreur.

- [ ] **Step 5: Implémenter la policy pure et exhaustive**

```kotlin
internal sealed interface ClipboardPolicyDecision {
    data class Accept(
        val kind: ClipboardKind,
        val isSensitive: Boolean,
        val items: List<AcceptedClipboardItem>,
    ) : ClipboardPolicyDecision
    data class Reject(val failure: ClipboardFailure) : ClipboardPolicyDecision
}

internal data class AcceptedClipboardItem(
    val itemIndex: Int,
    val candidateMimeTypes: List<String>,
)

internal object ClipboardIngestPolicy {
    fun evaluate(snapshot: SystemClipSnapshot, privateEditor: Boolean): ClipboardPolicyDecision {
        if (snapshot.items.isEmpty()) return ClipboardPolicyDecision.Reject(ClipboardFailure.EMPTY)
        if (snapshot.items.size > ClipboardLimits.MAX_GROUP_ITEMS) {
            return ClipboardPolicyDecision.Reject(ClipboardFailure.TOO_MANY_ITEMS)
        }
        val mimeTypes = snapshot.mimeTypes.distinct().map { mime ->
            if (mime.length > ClipboardLimits.MAX_MIME_CHARS || mime.any { it.code !in 0x20..0x7e }) {
                return ClipboardPolicyDecision.Reject(ClipboardFailure.INVALID_METADATA)
            }
            mime.lowercase(Locale.ROOT)
        }
        if (snapshot.items.any { it.uri?.scheme?.lowercase(Locale.ROOT) == "file" }) {
            return ClipboardPolicyDecision.Reject(ClipboardFailure.UNSUPPORTED)
        }
        if (snapshot.items.all { it.text == null && it.htmlText == null && it.uri == null }) {
            return ClipboardPolicyDecision.Reject(ClipboardFailure.UNSUPPORTED)
        }
        val kind = if (snapshot.items.size > 1) ClipboardKind.GROUP else {
            classifySingle(snapshot.items.single(), mimeTypes)
                ?: return ClipboardPolicyDecision.Reject(ClipboardFailure.UNSUPPORTED)
        }
        return ClipboardPolicyDecision.Accept(
            kind = kind,
            isSensitive = snapshot.isSensitive || privateEditor || snapshot.items.any { it.isSensitive },
            items = snapshot.items.mapIndexed { index, item ->
                AcceptedClipboardItem(
                    itemIndex = index,
                    candidateMimeTypes = candidateMimeTypesFor(item, mimeTypes),
                )
            },
        )
    }
}
```

`classifySingle()` applique dans l’ordre : HTML ; URI `content://` selon préfixe MIME image/video/audio, sinon FILE ; texte dont le schéma exact est `http`, `https`, `mailto` ou `tel` → LINK ; autre texte → TEXT. `candidateMimeTypesFor` conserve seulement les MIME déclarés compatibles avec la représentation de l’item ; pour chaque URI, le MIME effectif reste à résoudre individuellement par `ContentResolver.getType()` dans la source bornée de Task 5. Un groupe image + PDF + audio ne réutilise donc jamais aveuglément le MIME global pour tous ses items. Un `Intent` présent en plus d’une représentation sûre est ignoré, jamais sérialisé.

- [ ] **Step 6: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*SystemClipboardGatewayTest' --tests '*ClipboardIngestPolicyTest' --no-daemon`

Expected: PASS ; aucune lecture URI, coercition, ouverture réseau ou persistance.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/SystemClipboardGateway.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardIngestPolicy.kt app/src/test/java/ovh/jefe/keyboard/clipboard/SystemClipboardGatewayTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardIngestPolicyTest.kt
git commit -m "feat: capture clipboard snapshots defensively"
```

### Task 3: Chiffrement Keystore, empreinte HMAC et manifest binaire borné

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardKeyStore.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardCrypto.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardManifestCodec.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardCryptoTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardManifestCodecTest.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardKeyStoreInstrumentedTest.kt`

**Interfaces:**
- Produces: `ClipboardKeyProvider`, `AndroidClipboardKeyStore`, `ClipboardCrypto.encrypt/decrypt/encryptStream/verifyThenDecryptStream/newFingerprintBuilder`.
- Produces: versioned `ClipboardManifest`, `ClipboardManifestItem`, and bounded binary `ClipboardManifestCodec`.
- Security boundary: AES and HMAC aliases are distinct and non-exportable in production.

- [ ] **Step 1: Écrire les tests RED cryptographiques**

```kotlin
class ClipboardCryptoTest {
    private val keys = InMemoryClipboardKeyProvider()
    private val crypto = ClipboardCrypto(keys, SecureRandom())

    @Test
    fun `round trip authenticates id kind and version`() {
        val aad = ClipboardAad("entry-1", "manifest", -1, "manifest")
        val encrypted = crypto.encrypt(aad, "secret".encodeToByteArray())
        assertArrayEquals(
            "secret".encodeToByteArray(),
            crypto.decrypt(aad, encrypted),
        )
        assertThrows(GeneralSecurityException::class.java) {
            crypto.decrypt(aad.copy(entryId = "entry-2"), encrypted)
        }
    }

    @Test
    fun `identical plaintext gets different nonces but stable streaming hmac`() {
        val first = crypto.encrypt(ClipboardAad("a", "blob-a", 0, "text"), byteArrayOf(1, 2, 3))
        val second = crypto.encrypt(ClipboardAad("b", "blob-b", 0, "text"), byteArrayOf(1, 2, 3))
        assertFalse(first.nonce.contentEquals(second.nonce))
        assertArrayEquals(streamingFingerprint(crypto, byteArrayOf(1, 2, 3)), streamingFingerprint(crypto, byteArrayOf(1, 2, 3)))
    }

    private fun streamingFingerprint(crypto: ClipboardCrypto, bytes: ByteArray): ByteArray =
        crypto.newFingerprintBuilder(ClipboardKind.TEXT).use { builder ->
            builder.beginPayload(0, ClipboardPayloadRole.TEXT, "text/plain")
            builder.update(bytes, 0, bytes.size)
            builder.endPayload()
            builder.finish()
        }
}
```

Ajouter des tests pour altération du nonce/ciphertext/tag/AAD, lecture en flux, dépassement de 25 MiB, annulation sans fichier résiduel et remise à zéro du buffer mutable dans `finally`.

- [ ] **Step 2: Écrire les tests RED du codec**

Construire un manifest contenant texte exact, HTML + repli, blob, nom Unicode et groupe ordonné ; vérifier un aller-retour byte-for-byte, le refus d’une version inconnue, d’une longueur négative, de 33 items, d’un MIME >255 et de données tronquées.

- [ ] **Step 3: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardCryptoTest' --tests '*ClipboardManifestCodecTest' --no-daemon`

Expected: FAIL à la compilation sur les nouveaux types.

- [ ] **Step 4: Écrire et exécuter le RED Android Keystore avant la production**

Dans `ClipboardKeyStoreInstrumentedTest`, demander les deux clés à `AndroidClipboardKeyStore`, vérifier qu’elles sont distinctes, non exportables (`encoded == null`), qu’un aller-retour AES-GCM fonctionne, puis que `deleteKeys()` enlève exactement les deux alias et que leur recréation produit de nouvelles clés utilisables.

Run on device: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.ClipboardKeyStoreInstrumentedTest --no-daemon`

Expected: FAIL à la compilation sur `AndroidClipboardKeyStore` et les contrats crypto encore absents. Cette preuve RED est enregistrée avant d’écrire les classes de production.

- [ ] **Step 5: Créer deux clés Keystore distinctes**

```kotlin
internal interface ClipboardKeyProvider {
    fun aesKey(): SecretKey
    fun hmacKey(): SecretKey
    fun deleteKeys()
}

internal class AndroidClipboardKeyStore : ClipboardKeyProvider {
    override fun aesKey(): SecretKey = getOrCreate(
        alias = "jefe.clipboard.aes.v1",
        algorithm = KeyProperties.KEY_ALGORITHM_AES,
        purposes = KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        blockMode = KeyProperties.BLOCK_MODE_GCM,
        padding = KeyProperties.ENCRYPTION_PADDING_NONE,
        keySize = 256,
    )

    override fun hmacKey(): SecretKey = getOrCreateHmac(
        alias = "jefe.clipboard.hmac.v1",
        digest = KeyProperties.DIGEST_SHA256,
    )
}
```

`deleteKeys()` supprime exactement ces deux alias. Aucune clé de repli logicielle n’est créée si Android Keystore est indisponible ou invalidé.

- [ ] **Step 6: Implémenter l’enveloppe AES-GCM et le HMAC canonique**

```kotlin
internal data class EncryptedObject(val nonce: ByteArray, val ciphertextAndTag: ByteArray)

internal data class ClipboardAad(
    val entryId: String,
    val objectId: String,
    val itemIndex: Int,
    val representation: String,
    val version: Int = 1,
)

internal object EncryptedObjectCodec {
    fun encode(value: EncryptedObject): ByteArray
    fun decode(bytes: ByteArray): EncryptedObject
}

internal class ClipboardCrypto(
    private val keys: ClipboardKeyProvider,
    private val secureRandom: SecureRandom,
) {
    fun encrypt(aad: ClipboardAad, plaintext: ByteArray): EncryptedObject
    fun decrypt(aad: ClipboardAad, encrypted: EncryptedObject): ByteArray
    fun encryptStream(aad: ClipboardAad, input: InputStream, output: OutputStream, maxPlaintextBytes: Long): Long
    fun verifyThenDecryptStream(aad: ClipboardAad, source: StableCiphertextSnapshot, output: OutputStream): Long
    fun newFingerprintBuilder(kind: ClipboardKind): ClipboardFingerprintBuilder
}

internal interface ClipboardFingerprintBuilder : Closeable {
    fun beginPayload(itemIndex: Int, role: ClipboardPayloadRole, mimeType: String)
    fun update(bytes: ByteArray, offset: Int, length: Int)
    fun endPayload()
    fun finish(): ByteArray
}
```

Chaque chiffrement génère 12 octets par `SecureRandom`, utilise `AES/GCM/NoPadding`, tag 128 bits et sérialise l’AAD par champs longueur-préfixés : version, entryId, objectId, itemIndex et representation. Pour un frame, `objectId` est son `payloadId` UUID généré **avant** chiffrement ; itemIndex et rôle empêchent de permuter deux frames valides d’une même entrée, tandis que le manifest authentifié lie payloadId au containerId/offset/longueur. Le manifest utilise `objectId=manifest`, index -1, rôle `manifest`.

`ClipboardFingerprintBuilder` ne matérialise jamais un payload complet : pendant l’unique copie il calcule pour chaque représentation un SHA-256 streaming et sa longueur 64 bits, puis `finish()` calcule HMAC-SHA-256 sur la forme canonique `version, kind, itemIndex, role, MIME, longueur exacte, digest`. Seul ce HMAC final est persisté. Les tests alimentent un flux de 25 MiB par blocs, vérifient une empreinte stable et affirment qu’aucun `ByteArray` proportionnel au payload n’est créé. Les buffers de chiffrement/digest font 64 KiB et sont écrasés dans `finally`; `close()` invalide un builder non fini.

```kotlin
internal class StableCiphertextSnapshot internal constructor(
    internal val channel: FileChannel,
    internal val offset: Long,
    internal val length: Long,
) : Closeable {
    override fun close() = channel.close()
}
```

`StableCiphertextSnapshot` garde un unique `FileChannel` lecture seule ouvert sur le même inode immuable et une plage bornée. `verifyThenDecryptStream` positionne ce channel au même offset, exécute toute la première passe vers un sink nul et exige `doFinal()` valide ; aucun octet clair ne sort. Elle repositionne **le même channel/fd** au même offset, recommence avec la même AAD et écrit la seconde passe dans `output`. Un remplacement du chemin entre les passes ne change pas l’inode ouvert. Le test remplace le fichier sur disque après la première passe et vérifie que la seconde lit encore le snapshot authentifié ; un fake « valide puis altéré » n’est plus représentable par l’API. Ne jamais utiliser `CipherInputStream` directement vers un client avant validation du tag.

`EncryptedObjectCodec` est l’unique conversion vers les BLOB Room : magic `JFCE`, version 1, nonce length exactement 12, ciphertext/tag length 32 bits bornée, puis octets. `PreparedClipboardEntry.encryptedManifest` reçoit `encode(crypto.encrypt(...))`; le repository appelle `decode` avant `crypto.decrypt`. Les tests altèrent magic/version/longueurs et vérifient un refus avant allocation.

- [ ] **Step 7: Implémenter le manifest binaire versionné**

```kotlin
internal class ClipboardManifest(
    val version: Int = 1,
    val kind: ClipboardKind,
    val items: List<ClipboardManifestItem>,
    val thumbnail: ClipboardPayloadRef? = null,
) {
    override fun toString(): String = "ClipboardManifest(kind=$kind, items=${items.size}, redacted=true)"
}

internal class ClipboardManifestItem(
    val mimeTypes: List<String>,
    val displayName: String?,
    val payloads: List<ClipboardPayloadRef>,
) {
    override fun toString(): String = "ClipboardManifestItem(payloads=${payloads.size}, redacted=true)"
}

internal enum class ClipboardPayloadRole { TEXT, HTML, FALLBACK, CONTENT, THUMBNAIL }

internal data class ClipboardPayloadRef(
    val role: ClipboardPayloadRole,
    val mimeType: String,
    val storage: ClipboardPayloadStorage,
    val plainByteSize: Long,
)

internal sealed interface ClipboardPayloadStorage {
    class Inline(val bytes: ByteArray) : ClipboardPayloadStorage {
        override fun toString(): String = "Inline(size=${bytes.size}, redacted=true)"
    }
    data class Blob(
        val containerId: String,
        val payloadId: String,
        val offset: Long,
        val encryptedLength: Long,
    ) : ClipboardPayloadStorage
}
```

Le codec écrit magic `JFCB`, version 1, enum par nom ASCII, compte 0..32 et listes/chaînes/octet arrays avec longueurs bornées. Chaque représentation choisit exactement `Inline` ou un frame `Blob`; ainsi HTML et fallback peuvent chacun dépasser 64 KiB avec deux `payloadId` distincts dans le même conteneur d’entrée. Les UUID sont validés, offsets/longueurs positifs, croissants, non chevauchants et bornés par la taille du conteneur ; MIME distincts ≤255, rôles uniques par item sauf plusieurs `CONTENT` MIME explicitement ordonnés. Le codec ne rend jamais le manifest en JSON ni dans `toString()`. `ClipboardManifest` et `ClipboardManifestItem` ne sont pas des `data class`; le test sentinelle vérifie que texte inline et displayName n’apparaissent jamais dans leur `toString()`.

- [ ] **Step 8: Vérifier JVM et Android Keystore**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardCryptoTest' --tests '*ClipboardManifestCodecTest' --no-daemon`

Run on device: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.ClipboardKeyStoreInstrumentedTest --no-daemon`

Expected: JVM PASS ; sur API 24 et 34, clés `SecretKey` non exportables (`encoded == null`), alias distincts, aller-retour et suppression/recréation réussis.

- [ ] **Step 9: Contrôler et committer**

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardKeyStore.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardCrypto.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardManifestCodec.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardCryptoTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardManifestCodecTest.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardKeyStoreInstrumentedTest.kt
git commit -m "feat: encrypt clipboard objects with keystore keys"
```

### Task 4: Schéma Room metadata-only et transactions instrumentées

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardEntities.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardEntryDao.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardDatabase.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardEntryDaoInstrumentedTest.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardDatabaseInstrumentedTest.kt`

**Interfaces:**
- Produces: Room v1 tables `clipboard_entries`, `clipboard_containers`, `sensitive_text_hashes`.
- Produces: DAO queries that expose only `READY` entries and order pinned first, then `lastCopiedAt DESC`.
- The opaque hashes and encrypted BLOBs are metadata-safe; no preview/name/text column exists.

- [ ] **Step 1: Écrire les tests instrumentés RED du DAO**

```kotlin
@RunWith(AndroidJUnit4::class)
class ClipboardEntryDaoInstrumentedTest {
    private lateinit var database: ClipboardDatabase

    @Before
    fun open() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ClipboardDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun close() = database.close()

    @Test
    fun onlyReadyEntriesAreObservedPinnedThenRecent() = runTest {
        val dao = database.entries()
        dao.insertEntry(entity("staging", ClipboardStorageState.STAGING, false, 30))
        dao.insertEntry(entity("recent", ClipboardStorageState.READY, false, 20))
        dao.insertEntry(entity("pinned", ClipboardStorageState.READY, true, 10))

        assertEquals(listOf("pinned", "recent"), dao.observeReady().first().map { it.id })
    }

    @Test
    fun `fingerprint lookup and opaque sensitive hashes are exact`() = runTest {
        val fingerprint = byteArrayOf(1, 2, 3)
        val entry = entity("one", ClipboardStorageState.READY, false, 1, fingerprint)
        database.withTransaction {
            database.entries().insertEntry(entry)
            database.entries().insertSensitiveHashes(listOf(SensitiveTextHashEntity("one", 0, "text", byteArrayOf(9))))
        }
        assertEquals("one", database.entries().findByFingerprint(fingerprint)?.id)
        assertTrue(database.entries().containsSensitiveTextHash(byteArrayOf(9)))
    }
}
```

Ajouter des tests pour `STAGING -> READY`, `READY -> PROMOTING -> READY`, `READY -> REVOKING -> DELETING`, suppression en cascade des containers/hashes, somme et compte des seuls non épinglés, pagination non sensible, liste de purge par ancienneté et conflit de fingerprint unique.

- [ ] **Step 2: Vérifier le RED sur appareil**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.ClipboardEntryDaoInstrumentedTest --no-daemon`

Expected: FAIL à la compilation sur les entités/DAO/base.

- [ ] **Step 3: Créer les trois entités sans donnée affichable**

```kotlin
@Entity(
    tableName = "clipboard_entries",
    indices = [
        Index(value = ["fingerprintHmac"], unique = true),
        Index(value = ["storageState", "isPinned", "lastCopiedAt"]),
    ],
)
internal data class ClipboardEntryEntity(
    @PrimaryKey val id: String,
    val createdAt: Long,
    val lastCopiedAt: Long,
    val kind: ClipboardKind,
    val itemCount: Int,
    val isPinned: Boolean,
    val isSensitive: Boolean,
    val storedByteSize: Long,
    val fingerprintHmac: ByteArray,
    val storageState: ClipboardStorageState,
    val encryptedManifest: ByteArray,
    val revision: Long,
)

@Entity(
    tableName = "clipboard_containers",
    primaryKeys = ["entryId", "containerId"],
    foreignKeys = [ForeignKey(
        entity = ClipboardEntryEntity::class,
        parentColumns = ["id"], childColumns = ["entryId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class ClipboardContainerEntity(
    val entryId: String,
    val containerId: String,
    val encryptedByteSize: Long,
)

@Entity(
    tableName = "sensitive_text_hashes",
    primaryKeys = ["entryId", "itemIndex", "representation"],
    indices = [Index(value = ["exactTextHmac"])],
    foreignKeys = [ForeignKey(
        entity = ClipboardEntryEntity::class,
        parentColumns = ["id"], childColumns = ["entryId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
internal data class SensitiveTextHashEntity(
    val entryId: String,
    val itemIndex: Int,
    val representation: String,
    val exactTextHmac: ByteArray,
)
```

Ajouter des `TypeConverter` basés sur `enum.name` avec refus d’une valeur inconnue. Aucun `fallbackToDestructiveMigration()` n’est autorisé.

- [ ] **Step 4: Créer le DAO et la base version 1**

Le repository exécute ses transitions sous un `Mutex` applicatif puis `database.withTransaction`; le DAO reste mécanique et expose exactement ces signatures/SQL, sans concaténation dynamique :

```kotlin
@Dao
internal interface ClipboardEntryDao {
    @Query("SELECT * FROM clipboard_entries WHERE storageState = 'READY' ORDER BY isPinned DESC, lastCopiedAt DESC")
    fun observeReady(): Flow<List<ClipboardEntryEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM clipboard_entries WHERE id = :id AND storageState = 'READY')")
    suspend fun isReady(id: String): Boolean

    @Query("SELECT * FROM clipboard_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ClipboardEntryEntity?

    @Query("SELECT * FROM clipboard_entries WHERE fingerprintHmac = :hash LIMIT 1")
    suspend fun findByFingerprint(hash: ByteArray): ClipboardEntryEntity?

    @Query("SELECT * FROM clipboard_entries WHERE storageState = 'READY' AND isSensitive = 0 ORDER BY lastCopiedAt DESC LIMIT :limit OFFSET :offset")
    suspend fun readyNonSensitivePage(limit: Int, offset: Int): List<ClipboardEntryEntity>

    @Query("SELECT * FROM clipboard_entries WHERE storageState IN ('STAGING','PROMOTING','REVOKING','DELETING') ORDER BY createdAt ASC")
    suspend fun intermediateEntries(): List<ClipboardEntryEntity>

    @Query("SELECT COUNT(*) FROM clipboard_entries WHERE storageState = 'READY' AND isPinned = 0")
    suspend fun readyUnpinnedCount(): Int

    @Query("SELECT COALESCE(SUM(storedByteSize), 0) FROM clipboard_entries WHERE storageState = 'READY' AND isPinned = 0")
    suspend fun readyUnpinnedBytes(): Long

    @Query("SELECT * FROM clipboard_entries WHERE storageState = 'READY' AND isPinned = 0 AND id != :excludedId ORDER BY lastCopiedAt ASC, id ASC LIMIT :limit")
    suspend fun oldestReadyUnpinned(excludedId: String, limit: Int): List<ClipboardEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(value: ClipboardEntryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertContainers(values: List<ClipboardContainerEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSensitiveHashes(values: List<SensitiveTextHashEntity>)

    @Update
    suspend fun updateEntry(value: ClipboardEntryEntity): Int

    @Query("DELETE FROM clipboard_containers WHERE entryId = :entryId AND containerId = :containerId")
    suspend fun deleteContainer(entryId: String, containerId: String): Int

    @Query("DELETE FROM clipboard_entries WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query("SELECT * FROM clipboard_containers WHERE entryId = :entryId ORDER BY containerId")
    suspend fun containersFor(entryId: String): List<ClipboardContainerEntity>

    @Query("SELECT containerId FROM clipboard_containers")
    suspend fun allContainerIds(): List<String>

    @Query("SELECT EXISTS(SELECT 1 FROM sensitive_text_hashes WHERE exactTextHmac = :hash)")
    suspend fun containsSensitiveTextHash(hash: ByteArray): Boolean
}
```

Chaque transition relit l’entité sous la transaction, vérifie état + `revision`, puis appelle `updateEntry(entity.copy(..., revision = revision + 1))`; un résultat autre que 1 est un conflit sûr. Les victimes de quota sont relues sous la même transaction. Cette API fournit la pagination de recherche et tous les besoins Task 6 sans exposer de SQL à la couche UI.

```kotlin
@Database(
    entities = [ClipboardEntryEntity::class, ClipboardContainerEntity::class, SensitiveTextHashEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(ClipboardRoomConverters::class)
internal abstract class ClipboardDatabase : RoomDatabase() {
    abstract fun entries(): ClipboardEntryDao
}
```

- [ ] **Step 5: Vérifier le schéma exporté et le GREEN**

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.ClipboardEntryDaoInstrumentedTest,ovh.jefe.keyboard.clipboard.ClipboardDatabaseInstrumentedTest --no-daemon`

Expected: PASS ; `app/schemas/ovh.jefe.keyboard.clipboard.ClipboardDatabase/1.json` existe et ne contient aucun champ de texte/nom/aperçu utilisateur.

- [ ] **Step 6: Contrôler et committer**

```bash
git diff --check
git add app/schemas app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardEntities.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardEntryDao.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardDatabase.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardEntryDaoInstrumentedTest.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardDatabaseInstrumentedTest.kt
git commit -m "feat: store encrypted clipboard metadata in room"
```

### Task 5: Blob store chiffré, ingestion bornée et récupération sans fragment clair

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/EncryptedClipboardBlobStore.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardStorageSpaceManager.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/AndroidClipboardContentSource.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPreviewSanitizer.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardIngestor.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/EncryptedClipboardBlobStoreTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPreviewSanitizerTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardIngestorTest.kt`

**Interfaces:**
- Produces: `ContentStreamOpener`, `EncryptedClipboardBlobStore`, `PreparedClipboardEntry`, `ClipboardIngestResult`.
- Consumes: gateway snapshot, policy decision, crypto and manifest codec.
- Disk protocol: un conteneur `.part` préalloué par entrée dans `noBackupFilesDir/clipboard/v1`, frames chiffrés écrits dans cette allocation puis trim + renommage atomique en `.blob`, jamais de temporaire plaintext ni double pic disque.

- [ ] **Step 1: Écrire les tests RED du blob store**

Tester avec un répertoire temporaire : container/payload UUID imposés par le store, `.part` réellement préalloué avant ouverture source, plusieurs frames ordonnés dans le même fichier, `.blob` après trim/finalize, suppression sur exception/annulation, refus de `../`, lecture exacte d’un frame staging pour miniature, déchiffrement exact, deux encryptions différentes du même contenu et `storedByteSize == finalFile.length()`. Avec exactement N octets libres, préallouer N puis écrire N dans le même inode doit réussir sans espace N supplémentaire.

- [ ] **Step 2: Écrire les tests RED de sanitisation d’aperçu**

```kotlin
@Test
fun `preview removes controls and bidi formatting without changing payload`() {
    val exact = "bon\u202Etxt\njour\u0000"
    assertEquals("bontxt jour", ClipboardPreviewSanitizer.preview(exact, 256))
    assertEquals(exact, exact) // le sanitizer ne reçoit jamais la référence utilisée au collage
}

@Test
fun `preview is bounded by code points`() {
    assertEquals(256, ClipboardPreviewSanitizer.preview("é".repeat(300), 256).codePointCount(0, 256))
}
```

- [ ] **Step 3: Écrire les tests RED de l’ingestor**

La fixture `FakeContentStreamOpener` compte les ouvertures et fournit tailles/noms/flux. Couvrir : tous les types, texte >64 KiB en blob, groupe ordonné, nom 4 096 caractères, source inaccessible, timeout 30 s avec horloge/test dispatcher, total **stocké après chiffrement** égal à 25 MiB accepté, octet stocké supplémentaire refusé, thumbnail abandonnée si elle seule dépasserait la limite, 33 items refusés avant ouverture, manque d’espace, annulation et zéro fragment résiduel. Affirmer une seule ouverture de chaque URI source.

- [ ] **Step 4: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*EncryptedClipboardBlobStoreTest' --tests '*ClipboardPreviewSanitizerTest' --tests '*ClipboardIngestorTest' --no-daemon`

Expected: FAIL à la compilation sur les nouveaux composants.

- [ ] **Step 5: Implémenter le store et son protocole**

```kotlin
internal data class StagedEntryContainer(
    val containerId: String,
    val entryId: ClipboardEntryId,
    val partFile: File,
    val finalFile: File,
    val reservedBytes: Long,
    var usedBytes: Long,
)

internal data class StagedPayloadFrame(
    val containerId: String,
    val payloadId: String,
    val aad: ClipboardAad,
    val offset: Long,
    val encryptedLength: Long,
)

internal fun interface StorageSpaceReclaimer {
    suspend fun reclaimNonPinned(requiredBytes: Long): Long
}

internal class EncryptedClipboardBlobStore(
    private val root: File,
    private val crypto: ClipboardCrypto,
) {
    fun tryBeginEntry(entryId: ClipboardEntryId, containerBudgetBytes: Long): StagedEntryContainer?
    fun stagePayload(
        container: StagedEntryContainer,
        itemIndex: Int,
        role: ClipboardPayloadRole,
        input: InputStream,
        maxPlaintextBytes: Long,
    ): StagedPayloadFrame
    fun readStagedVerified(container: StagedEntryContainer, frame: StagedPayloadFrame, output: OutputStream): Long
    fun sealForStaging(container: StagedEntryContainer, encryptedManifestBytes: Long)
    fun finalize(container: StagedEntryContainer)
    fun discard(container: StagedEntryContainer)
    fun stableCiphertext(containerId: String, offset: Long, length: Long): StableCiphertextSnapshot
    fun delete(containerId: String): Boolean
    fun listManagedContainerIds(): Set<String>
}

internal class ClipboardStorageSpaceManager(
    private val store: EncryptedClipboardBlobStore,
    private val reclaimer: StorageSpaceReclaimer,
) {
    suspend fun beginEntry(
        entryId: ClipboardEntryId,
        containerBudgetBytes: Long,
        encryptedManifestBudgetBytes: Long,
    ): StagedEntryContainer?
}
```

Avant toute ouverture source, l’ingestor calcule exactement la taille maximale du manifest chiffré à partir du nombre d’items/représentations, des metadata déjà bornées, des payloads inline connus et d’une éventuelle référence thumbnail fixe. Il impose `containerBudgetBytes + encryptedManifestBudgetBytes <= MAX_ENTRY_BYTES`; les pages SQLite globales ne font pas partie de `storedByteSize`. `tryBeginEntry` est un store bas niveau sans repository : il crée directement le futur conteneur UUID `.part` et réserve `containerBudgetBytes` **sur ce même inode** avec `Os.posix_fallocate`; sur `ENOSYS/EOPNOTSUPP`, le fallback API 24 écrit des blocs zéro bornés puis `fsync`, jamais un `setLength` sparse. ENOSPC ferme/supprime le part et retourne null.

`ClipboardStorageSpaceManager`, construit seulement après le repository, exige via `statvfs` l’espace pour conteneur **et** budget manifest/Room avant d’appeler `tryBeginEntry`; si insuffisant/null, il demande à `StorageSpaceReclaimer` de purger crash-safe les plus anciens non épinglés, attend cette purge puis réessaie une seule fois. Si `containerBudgetBytes == 0` (entrée entièrement Inline), il vérifie/réserve logiquement le budget manifest + page Room, n’appelle pas le blob store et retourne `null`; l’éventuel ENOSPC SQLite suit le même purge + retry borné. Il retourne `PINNED_STORAGE_FULL` seulement s’il ne reste aucune victime et que la seconde tentative échoue. Cette séparation évite tout cycle store → repository → store. Le test prouve qu’aucune source n’est ouverte avant succès, couvre inline sans `.part`, le retry après purge et l’espace libre exactement égal au conteneur final + BLOB manifest chiffré + une page Room conservatrice, sans second fichier de réservation.

`stagePayload` génère/valide un `payloadId` UUID avant crypto, construit `ClipboardAad(entryId, payloadId, itemIndex, role.name)`, cherche à `usedBytes` et écrit une frame `JFBO + version + nonce + ciphertext/tag` **dans les blocs déjà alloués** sans créer un second fichier. Il refuse tout dépassement de `reservedBytes`, fsync puis avance `usedBytes`. `readStagedVerified` lit cette même plage afin de générer une miniature sans finaliser ni rouvrir la source fournisseur. Après création du manifest exact, `sealForStaging` vérifie `usedBytes + encryptedManifestBytes <= MAX_ENTRY_BYTES`, tronque/fsync le tail inutilisé et rend ces blocs avant l’insertion Room ; si le BLOB Room rencontre malgré tout ENOSPC, le manager purge puis retente la transaction, sinon discard sûr. `finalize` renomme le `.part` déjà trimé en `.blob`, puis fsync le répertoire ; `discard` ferme et supprime l’unique part. HTML, fallback et contenus d’un groupe ont des frames distinctes mais partagent le conteneur. Le manifest stocke containerId/payloadId/offset/length, et `storedByteSize = finalFile.length() + encryptedManifest.size` (inline, nonce/tag/thumbnail inclus), à l’exclusion des pages SQLite globales. Utiliser seulement `File.length()`/`FileChannel.size()`, compatibles API 24, jamais `java.nio.file.Files`.

- [ ] **Step 6: Implémenter la source Android annulable**

```kotlin
internal class OpenedClipboardContent(
    val stream: InputStream,
    val resolvedMimeType: String,
    val declaredLength: Long?,
    val encryptedDisplayName: String?,
    private val cancelSource: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    fun cancel() {
        if (closed.compareAndSet(false, true)) {
            cancelSource()
            stream.close()
        }
    }
    override fun close() = cancel()
}

internal fun interface ContentStreamOpener {
    suspend fun open(uri: Uri, candidateMimeTypes: List<String>): OpenedClipboardContent
}
```

`AndroidClipboardContentSource` refuse tout schéma autre que `content`, crée un `CancellationSignal`, résout séparément le MIME de **chaque URI** avec `ContentResolver.getType(uri)`, le normalise/borne puis exige sa compatibilité avec au moins un MIME candidat. Il interroge seulement `OpenableColumns.DISPLAY_NAME/SIZE` avec limites, puis ouvre `openAssetFileDescriptor(uri, "r", signal)` une seule fois. Query et open utilisent les variantes acceptant `CancellationSignal`; l’annulation appelle immédiatement `signal.cancel()`, ferme tout descriptor déjà reçu et annule le Future. `getType()` n’accepte malheureusement aucun signal et un provider Binder hostile peut ignorer l’interruption : le timeout libère toujours la coroutine/FIFO au bout de 30 s, met cet executor bloqué en quarantaine et fait tourner vers un executor borné neuf, sans prétendre tuer l’appel distant. Le composant plafonne à deux executors mis en quarantaine ; au-delà il retourne `TIMED_OUT` sans nouvelle tâche jusqu’à la fin d’un worker, afin d’éviter une fuite de threads. L’objet retourné conserve signal **et** descriptor dans `cancelSource`, afin que `cancel()` ferme le fd après `open()`. Cursor, descriptor, stream et signal sont fermés/annulés idempotemment. `SecurityException`, provider null, MIME absent/hostile et longueur négative deviennent des échecs sûrs ; aucun appel à `coerceToText`, réseau ou chemin venant du nom. Les tests couvrent un provider `getType()` qui ignore l’interruption tout en prouvant que B est traité après le timeout de A, ainsi qu’un groupe mixte image/PDF/audio dont chaque URI renvoie un MIME différent.

- [ ] **Step 7: Implémenter l’ingestor en un seul passage source**

```kotlin
internal data class PreparedClipboardEntry(
    val id: ClipboardEntryId,
    val kind: ClipboardKind,
    val isSensitive: Boolean,
    val capturedAtMillis: Long,
    val fingerprintHmac: ByteArray,
    val encryptedManifest: ByteArray,
    val stagedContainer: StagedEntryContainer?,
    val storedByteSize: Long,
    val sensitiveTextHashes: List<PreparedSensitiveTextHash>,
)

internal data class PreparedSensitiveTextHash(
    val itemIndex: Int,
    val representation: String,
    val exactTextHmac: ByteArray,
)

internal sealed interface ClipboardIngestResult {
    data class Prepared(val entry: PreparedClipboardEntry) : ClipboardIngestResult
    data class Failure(val failure: ClipboardFailure) : ClipboardIngestResult
}
```

`ClipboardIngestor` reçoit `ingestTimeoutMillis = ClipboardLimits.INGEST_TIMEOUT_MILLIS` pour injecter 50 ms dans le test de flux bloquant, le pool IO rotatif et `ClipboardStorageSpaceManager`. `prepare()` s’exécute sous `withTimeout(ingestTimeoutMillis)`, calcule séparément budget conteneur et budget manifest (borne totale de 25 MiB si longueur fournisseur inconnue), obtient le conteneur préalloué **avant** toute ouverture fournisseur lorsque le budget conteneur est non nul, sinon conserve explicitement `stagedContainer=null` pour une entrée entièrement Inline ; il encode les textes UTF-8 et chiffre chaque URI dès sa seule ouverture.

La copie bloquante est enveloppée dans `suspendCancellableCoroutine` : le travail est soumis à l’executor, et `continuation.invokeOnCancellation` appelle immédiatement `opened.cancel()` puis `future.cancel(true)`. Le callback worker ne reprend la continuation que si `isActive`. Après ouverture, timeout/disable ferment signal+fd même si `InputStream.read()` ne consulte pas l’état coroutine ; avant ouverture, la limite `getType()` non annulable suit la quarantaine bornée décrite Step 6. Le composant ferme les executors terminables lors de sa réinitialisation totale et marque toute tâche tardive annulée afin qu’elle ne touche ni crypto ni repository.

L’ingestor alimente `ClipboardFingerprintBuilder` avec kind, MIME, ordre, longueurs et digests pendant le même passage que le chiffrement ; il ne construit jamais un `Sequence<ByteArray>` du contenu. Le compteur inclut enveloppes, tags/nonces, manifest chiffré et miniature chiffrée ; il ne compare jamais seulement la taille plaintext. `sealForStaging`, miniature, `discard` et toute autre opération de fichier sont conditionnées par `stagedContainer != null`. Au premier dépassement, timeout, `SecurityException`, `IOException` ou annulation, `finally` ferme les sources et fingerprint builder, détruit le conteneur staging s’il existe et libère les références.

Les miniatures non sensibles sont générées après la copie via `readStagedVerified` sur la frame interne : `BitmapFactory.Options.inJustDecodeBounds`, refus des dimensions/produits hostiles, puis `inSampleSize` pour au plus 256 × 256 et 4 MiB de pixels. Elles sont réencodées en PNG (API 24+) dans une nouvelle frame du même conteneur ; ne pas utiliser `WEBP_LOSSLESS`, API 30. Un échec de miniature n’échoue pas l’entrée. Aucune seconde ouverture du fournisseur source n’est permise.

- [ ] **Step 8: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*EncryptedClipboardBlobStoreTest' --tests '*ClipboardPreviewSanitizerTest' --tests '*ClipboardIngestorTest' --no-daemon`

Expected: PASS ; aucune source ouverte deux fois et aucun fichier clair/partiel après chaque branche d’échec.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/EncryptedClipboardBlobStore.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardStorageSpaceManager.kt app/src/main/java/ovh/jefe/keyboard/clipboard/AndroidClipboardContentSource.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPreviewSanitizer.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardIngestor.kt app/src/test/java/ovh/jefe/keyboard/clipboard/EncryptedClipboardBlobStoreTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPreviewSanitizerTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardIngestorTest.kt
git commit -m "feat: ingest clipboard payloads into encrypted blobs"
```

### Task 6: Repository, déduplication sensible, quotas et réconciliation

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryRepository.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/RoomClipboardHistoryRepository.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPayloadReader.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardEntryAccessRevocationHub.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPayloadReaderTest.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/RoomClipboardHistoryRepositoryInstrumentedTest.kt`

**Interfaces:**
- Produces: observation, finalize prepared entry, authenticated payload reader, pin/unpin, promote sensitive, delete, clear, search and reconcile.
- Returns: `StoreResult.Inserted`, `StoreResult.Duplicate`, or safe `StoreResult.Failure`; duplicate preserves id/pin and uses sensitivity OR.
- `RoomClipboardHistoryRepository` implémente aussi `StorageSpaceReclaimer`; cette méthode n’est appelée que par `ClipboardStorageSpaceManager` et suit la même transition `REVOKING -> revoke/join -> DELETING`.

- [ ] **Step 1: Écrire les tests RED transactionnels**

Sous base Room temporaire + vrai container store de test, couvrir : insertion `STAGING -> READY`, ordre, doublon qui remonte, épingle conservée, doublon sensible promu avant republication, thumbnail supprimée, hashes sensibles insérés, 501e non épinglé purge le plus ancien, dépassement 250 MiB, épinglés jamais purgés, entrée 25 MiB épinglée acceptée, manque disque dû aux épinglés, plan d’impact avant désépinglage et suppression interrompue. Pour la réconciliation, couvrir séparément `STAGING` inline sans container, `.part` scellé seul, `.blob` seul, aucun fichier, part tronqué et part+blob contradictoires ; couvrir aussi containers orphelins et manifest/fichier manquant. Un `RecordingEntryAccessRevoker` doit prouver l’ordre `PROMOTING non lisible -> revoke attendu -> READY sensible` pour promotion, et `READY absent -> revoke attendu -> DELETING -> fichiers supprimés` pour delete manuel, clear, quota, désépinglage, `quarantineCorrupt` et réconciliation.

Dans `ClipboardPayloadReaderTest`, construire TEXT et FALLBACK >64 KiB, thumbnail et CONTENT en blobs : `readExact` rend chaque octet exact, `readVerifiedPreviewPrefix` ne rend le préfixe qu’après authentification de tout le blob, `writeExact` pipe le payload complet, et `writeJoinedTextGroupExact` exige la recette ordonnée résolue, la revalide contre le manifest, authentifie puis écrit les représentations séparées par `\n` sans gros buffer. Mauvais tag ne livre aucun octet, MIME/taille/sélecteur divergent ou groupe mixte échoue, les additions de taille sont overflow-safe, toutes limites sont appliquées et `close()` remet les buffers à zéro.

- [ ] **Step 2: Vérifier les deux frontières RED avant la production**

Run JVM: `./gradlew testDebugUnitTest --tests '*ClipboardPayloadReaderTest' --no-daemon`

Expected: FAIL à la compilation sur `ClipboardPayloadReader` et ses résultats bornés.

Run Android: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.RoomClipboardHistoryRepositoryInstrumentedTest --no-daemon`

Expected: FAIL à la compilation sur l’interface/repository.

- [ ] **Step 3: Définir l’interface sans Room dans les appelants**

```kotlin
internal sealed interface StoreResult {
    data class Inserted(val summary: ClipboardEntrySummary) : StoreResult
    data class Duplicate(val summary: ClipboardEntrySummary) : StoreResult
    data class Failure(val failure: ClipboardFailure) : StoreResult
}

internal class LoadedClipboardEntry(
    val id: ClipboardEntryId,
    val kind: ClipboardKind,
    val isSensitive: Boolean,
    val manifest: ClipboardManifest,
) : Closeable {
    override fun close() {
        manifest.items.forEach { item ->
            item.payloads.forEach { payload ->
                (payload.storage as? ClipboardPayloadStorage.Inline)?.bytes?.fill(0)
            }
        }
        (manifest.thumbnail?.storage as? ClipboardPayloadStorage.Inline)?.bytes?.fill(0)
    }
    override fun toString(): String =
        "LoadedClipboardEntry(id=${id.value}, kind=$kind, sensitive=$isSensitive, redacted=true)"
}

internal sealed interface PinResult {
    data object Applied : PinResult
    data class RequiresConfirmation(
        val entriesPurged: List<ClipboardEntryId>,
        val bytesPurged: Long,
        val expectedRevision: Long,
    ) : PinResult
    data object StaleRevision : PinResult
    data class Failure(val failure: ClipboardFailure) : PinResult
}

internal class ClipboardSearchHit(
    val id: ClipboardEntryId,
    val kind: ClipboardKind,
    val preview: String,
    val typeLabel: String,
    val revision: Long,
) {
    override fun toString(): String =
        "ClipboardSearchHit(id=${id.value}, kind=$kind, revision=$revision, redacted=true)"
}

internal class SearchResult(
    val generation: Long,
    val hits: List<ClipboardSearchHit>,
) {
    override fun toString(): String = "SearchResult(generation=$generation, hits=${hits.size}, redacted=true)"
}

internal sealed interface SensitiveTextMatch {
    data object Match : SensitiveTextMatch
    data object NoMatch : SensitiveTextMatch
    data class Unavailable(val failure: ClipboardFailure) : SensitiveTextMatch
}

internal sealed interface ClipboardRepositoryEvent {
    data class EntryPromoting(val id: ClipboardEntryId) : ClipboardRepositoryEvent
    data class EntryReady(val id: ClipboardEntryId, val revision: Long) : ClipboardRepositoryEvent
    data class EntriesRemoved(val ids: Set<ClipboardEntryId>) : ClipboardRepositoryEvent
}

internal fun interface ClipboardEntryAccessRevoker {
    suspend fun revokeAccess(ids: Set<ClipboardEntryId>)
}

internal class ClipboardEntryAccessRevocationHub : ClipboardEntryAccessRevoker {
    fun register(listener: ClipboardEntryAccessRevoker): Closeable
    override suspend fun revokeAccess(ids: Set<ClipboardEntryId>)
}

internal data class ClipboardPayloadSelector(
    val itemIndex: Int,
    val role: ClipboardPayloadRole,
    val mimeType: String? = null,
)

internal class ClipboardPlaintextBuffer internal constructor(
    val bytes: ByteArray,
) : Closeable {
    override fun close() = bytes.fill(0)
    override fun toString(): String = "ClipboardPlaintextBuffer(size=${bytes.size})"
}

internal sealed interface ClipboardPayloadReadResult {
    data class Success(val buffer: ClipboardPlaintextBuffer) : ClipboardPayloadReadResult
    data class Failure(val failure: ClipboardFailure) : ClipboardPayloadReadResult
}

internal interface ClipboardPayloadReader {
    suspend fun readExact(
        id: ClipboardEntryId,
        selector: ClipboardPayloadSelector,
        maxBytes: Long = ClipboardLimits.MAX_ENTRY_BYTES,
    ): ClipboardPayloadReadResult

    suspend fun readVerifiedPreviewPrefix(
        id: ClipboardEntryId,
        selector: ClipboardPayloadSelector,
        maxUtf8Bytes: Int,
    ): ClipboardPayloadReadResult

    suspend fun writeExact(
        id: ClipboardEntryId,
        selector: ClipboardPayloadSelector,
        output: OutputStream,
        maxBytes: Long = ClipboardLimits.MAX_ENTRY_BYTES,
    ): ClipboardFailure?

    suspend fun writeJoinedTextGroupExact(
        id: ClipboardEntryId,
        orderedSelectors: List<ClipboardPayloadSelector>,
        output: OutputStream,
        maxBytes: Long = ClipboardLimits.MAX_ENTRY_BYTES,
    ): ClipboardFailure?
}

internal interface ClipboardHistoryRepository {
    val events: SharedFlow<ClipboardRepositoryEvent>
    fun observeSummaries(): Flow<List<ClipboardEntrySummary>>
    fun observeStats(): Flow<ClipboardHistoryStats>
    suspend fun store(prepared: PreparedClipboardEntry): StoreResult
    suspend fun load(id: ClipboardEntryId): LoadedClipboardEntry?
    suspend fun isReady(id: ClipboardEntryId): Boolean
    suspend fun setPinned(
        id: ClipboardEntryId,
        pinned: Boolean,
        expectedRevision: Long,
        confirmPurge: Boolean = false,
    ): PinResult
    suspend fun markSensitive(id: ClipboardEntryId): Boolean
    suspend fun quarantineCorrupt(id: ClipboardEntryId): Boolean
    suspend fun delete(id: ClipboardEntryId): Boolean
    suspend fun clearAll()
    suspend fun search(query: String, generation: Long): SearchResult
    suspend fun containsSensitiveExactText(text: String): SensitiveTextMatch
    suspend fun reconcile()
}
```

`LoadedClipboardEntry` possède `close()` et remet à zéro ses `ByteArray` mutables dans `finally`; son `toString()` ne contient que id/kind/sensible.

`RoomClipboardPayloadReader` reçoit directement DAO, codec, crypto et container store — pas le repository — afin d’éviter une dépendance circulaire. Il charge et authentifie d’abord le manifest `READY`, résout en interne le sélecteur exact et refuse toute référence fournie par l’appelant qui ne correspond pas au manifest. Pour `Inline`, il copie dans un buffer possédé ; pour `Blob`, il valide containerId/payloadId/offset/length, reconstruit l’AAD et obtient un `StableCiphertextSnapshot` du store. `readVerifiedPreviewPrefix` effectue la passe complète de vérification GCM puis une seconde passe qui conserve au plus la borne demandée tout en drainant/finalisant le cipher ; aucun préfixe n’est publié avant tag valide, et la coupe recule jusqu’à une frontière UTF-8 valide avant le sanitizer code-point. `readExact` borne avant allocation et sert les collages texte/HTML fallback ; `writeExact` sert le provider riche et ne livre rien avant authentification. Le repository reçoit ce reader pour sa recherche ; panel, prompt, coordinateur et provider consomment le même contrat — jamais directement les coordonnées du frame.

`ClipboardEntryAccessRevocationHub` sérialise les listeners sous mutex, prend un snapshot puis attend chacun hors verrou. Task 6 l’utilise même sans listener ; Task 7 place la même instance dans le composant ; Task 8 y enregistre le registre de grants/permissions/pipes. Une exception listener devient un échec sûr et laisse une suppression en `REVOKING` pour retry de réconciliation, jamais `DELETING` avec accès encore vivant.

- [ ] **Step 4: Implémenter le protocole deux phases**

Pour une nouvelle empreinte : la première transaction insère toujours entrée/hashes en `STAGING`, et insère une row container **seulement si** `prepared.stagedContainer != null`. Dans ce cas uniquement, hors transaction, renommer son `.part` scellé en `.blob` et fsync le répertoire. Pour une entrée entièrement Inline (`stagedContainer == null`), aucune row/finalize fichier n’est exécutée. Les deux chemins rejoignent ensuite la même seconde transaction : vérifier la révision, calculer les victimes de quota, passer la nouvelle entrée `READY` **et** les victimes `REVOKING` atomiquement. `observeReady` ne peut donc jamais émettre 501 entrées ou >250 MiB et tout nouvel accès provider échoue dès cette transaction via `isReady=false`. Hors transaction, appeler et attendre `ClipboardEntryAccessRevoker.revokeAccess(ids)` afin d’annuler pipes, tokens et permissions URI actifs, puis passer `DELETING`, supprimer les fichiers éventuels et les lignes. Un crash laisse `REVOKING/DELETING` que la réconciliation termine idempotemment. Si une finalisation requise échoue, garder `STAGING` et ne rien publier ; une entrée Inline n’attend jamais un fichier inexistant.

`quarantineCorrupt(id)` est idempotent : transaction `READY -> REVOKING` (ou accepte déjà `REVOKING/DELETING`), émet `EntriesRemoved`, attend la même barrière de révocation, passe `DELETING`, supprime conteneurs/lignes et retourne false seulement si l’entrée n’existait plus. Il n’est jamais appelé depuis le writer provider lui-même : celui-ci ferme d’abord sa lease et publie l’id vers le collector du composant.

Pour un doublon déjà sensible ou toujours clair : transaction verrouillée par l’index unique, conserver `isPinned`, mettre `lastCopiedAt`, incrémenter `revision`, puis publier. Si une copie sensible promeut un doublon clair — ou si l’utilisateur appelle `markSensitive` — ne jamais tronquer/réécrire le conteneur final immuable. Construire copy-on-write un nouveau conteneur UUID avec uniquement les frames non-thumbnail (copie ciphertext exacte, offsets réécrits, payloadId/AAD conservés), fsync/rename, puis chiffrer un manifest sans thumbnail. Une transaction atomique met `isSensitive=true`, insère tous les hashes, installe nouveau manifest/container row/storedByteSize/revision et passe `PROMOTING`; l’ancien container row reste temporairement pour reprise. Émettre `EntryPromoting(id)` afin que panel, recherche et prompt masquent/détachent immédiatement toute preview/Bitmap, puis attendre `ClipboardEntryAccessRevoker.revokeAccess(id)`, supprimer ancien fichier/row, passer `READY` et publier uniquement la version générique. Un crash en `PROMOTING` identifie le conteneur non référencé par le nouveau manifest, reprend revoke/nettoyage puis READY. Un `.blob` copy-on-write créé avant transaction et sans row est supprimé comme orphelin. Le conteneur staging d’un doublon non retenu est toujours détruit.

- [ ] **Step 5: Appliquer exactement les quotas non épinglés**

Après insertion/touch/unpin, calculer simultanément count et somme `storedByteSize` des `READY AND isPinned = 0`. Choisir par `lastCopiedAt ASC` jusqu’à count ≤500 et bytes ≤250 MiB, sans sélectionner l’entrée courante ni un épinglé. `setPinned(..., pinned=false, confirmPurge=false)` retourne `RequiresConfirmation` sans mutation. Après confirmation UI, rappeler avec `confirmPurge=true` et la même revision ; sous une seule transaction le repository recalcule, refuse `StaleRevision` si elle a changé, désépingle l’entrée et marque les victimes `REVOKING`, puis suit la même barrière revoke → `DELETING` → nettoyage. `pinned=true` n’exige jamais ce flag.

- [ ] **Step 6: Rechercher seulement en session et hors sensible**

`search()` charge par lots de 32 entrées `READY AND isSensitive = 0`, utilise `ClipboardPayloadReader.readExact` pour les représentations TEXT/HTML/FALLBACK Inline **et** Blob sur `Dispatchers.IO`, compare par décodage borné puis ferme chaque buffer dans `finally`. Les noms restent la `displayName` ≤4 096 code points du manifest authentifié : ils sont sanitizés/consultés pendant que `LoadedClipboardEntry` est ouvert, jamais passés au payload reader. La recherche normalise la requête en mémoire avec `Locale.ROOT`, vérifie `currentCoroutineContext().ensureActive()` entre chaque payload et retourne seulement si la génération est encore courante ; une correspondance située après les 256 premiers caractères reste trouvable. Aucun index, query ou résultat plaintext n’entre en base. Fermer le panneau annule le scope et libère les références.

- [ ] **Step 7: Réconcilier les états techniques**

Au démarrage, traiter chaque `STAGING` exhaustivement après authentification du manifest Room :

- entrée inline sans ligne container : rejouer directement la transaction commune de finalisation/quota ;
- container row + `.part` scellé seul : authentifier toutes les frames référencées, reprendre `rename(.part, .blob)` + fsync répertoire, puis rejouer la transaction commune ;
- container row + `.blob` seul : authentifier et rejouer cette même transaction ;
- `.part` et `.blob` tous deux présents, frame/tag invalide, aucun fichier, ou manifest qui référence une plage absente : garder l’entrée invisible, supprimer row + fragments idempotemment et publier seulement `CORRUPT_ENTRY` générique.

Avant cela, supprimer les `.part` qui n’ont aucune ligne `STAGING`. La transaction commune recalcule toujours quotas, passe nouvelle entrée `READY` et victimes `REVOKING` atomiquement, puis exécute barrière revoke/nettoyage ; ne jamais faire un simple `STAGING -> READY`. Pour `PROMOTING`, réappeler le revoker idempotent puis repasser `READY` sensible ; pour `REVOKING`, réappeler le revoker puis passer `DELETING`; pour `DELETING`, finir les suppressions ; supprimer les `.blob` absents de `clipboard_containers`; isoler une entrée `READY` dont le manifest/tag échoue via `REVOKING -> revoke -> DELETING`. Une clé invalidée retourne `ClipboardFailure.KEY_UNAVAILABLE` et n’efface rien sans action utilisateur. Les tests injectent un crash exact après transaction STAGING, après seal, après rename et avant transaction finale.

- [ ] **Step 8: Vérifier le GREEN et committer**

Run JVM: `./gradlew testDebugUnitTest --tests '*ClipboardPayloadReaderTest' --no-daemon`

Run Android: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.RoomClipboardHistoryRepositoryInstrumentedTest --no-daemon`

Expected: PASS pour toutes les transactions, quotas, crash points et promotions sensibles.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryRepository.kt app/src/main/java/ovh/jefe/keyboard/clipboard/RoomClipboardHistoryRepository.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPayloadReader.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardEntryAccessRevocationHub.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPayloadReaderTest.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/RoomClipboardHistoryRepositoryInstrumentedTest.kt
git commit -m "feat: reconcile and retain encrypted clipboard history"
```

### Task 7: Activation opt-in, FIFO sérialisée et cycle de vie IME

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardActivationStore.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryController.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardComponent.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardAdministrativeRuntime.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardActivationStoreTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryControllerTest.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt`

**Interfaces:**
- Produces: durable `ClipboardActivationState`, one application-scoped `ClipboardComponent`, `ClipboardAdministrativeRuntime` et un unique `ClipboardHistoryController`; controller `start/enable/clearAllAndResume/disableAndPurge/resetProtectedHistory/onEditorPrivacyChanged`.
- FIFO contract: every accepted snapshot is processed in arrival order; capacity overflow is a visible typed failure, never a generation discard.

- [ ] **Step 1: Écrire les tests RED d’opt-in**

Tester : état initial `DISABLED` ; `enable()` persiste `ENABLED` avec `commit()` avant listener ; listener absent avant enable ; enable enregistre une seule fois puis importe le primary clip courant ; restart activé réconcilie puis relit le clip courant. `clearAllAndResume` persiste d’abord `CLEARING_ENABLED`; un crash à chaque frontière reprend le clear sans listener/import puis revient `ENABLED`. `disableAndPurge` persiste d’abord `DISABLING`; un crash à chaque frontière reprend la purge au startup sans listener/import, puis seulement écrit `DISABLED`. `RESETTING_ENABLED` reprend de même un reset de clé confirmé et revient `ENABLED` propre. Après clear ou reset confirmé, le store persiste le marqueur source capturé **avant** la purge : sur API 31+, timestamp non nul différent seulement lève la suppression ; égal est `SAME_OR_COLLIDING` et nul `UNKNOWN`, donc restent supprimés. Sur API 24–30 le prochain callback est la preuve legacy documentée. Tester un recopy distinct dans la même milliseconde, un rollback d’horloge, un timestamp nul et un redémarrage : `start()` ne lève jamais le marqueur.

- [ ] **Step 2: Écrire le test RED A lent puis B rapide**

```kotlin
@Test
fun `slow media A and fast text B are both stored in FIFO order`() = runTest {
    val ingestor = ControllableIngestor()
    val repository = RecordingRepository()
    val gateway = QueuedFakeClipboardGateway()
    val controller = controller(gateway, ingestor, repository, backgroundScope)
    controller.onEditorPrivacyChanged(privateEditor = false)
    controller.enable()

    gateway.enqueue(mediaSnapshot("A")); controller.onPrimaryClipChanged()
    assertTrue(ingestor.awaitStarted("A"))
    gateway.enqueue(textSnapshot("B")); controller.onPrimaryClipChanged()
    ingestor.release("A")
    advanceUntilIdle()

    assertEquals(listOf("A", "B"), repository.storedLabels)
}
```

Ajouter le test exact où A est déjà actif et 31 événements attendent : ces 32 travaux sont acceptés, le 33e est refusé avec `Contenu non enregistré : presse-papiers saturé`, sans écraser les travaux acceptés. Ajouter `shutdownForTest()` pendant ingestion qui supprime les fragments, destruction/recréation du service qui **ne** perd pas A, et `clearAllAndResume()` pendant A lent : A et les événements déjà en file sont annulés/joints avant purge, aucun ne réapparaît après le clear, puis une nouvelle copie C est capturée normalement.

- [ ] **Step 3: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardActivationStoreTest' --tests '*ClipboardHistoryControllerTest' --tests '*JefeKeyboardServiceTest' --no-daemon`

Expected: FAIL à la compilation sur activation/controller/component.

- [ ] **Step 4: Implémenter l’activation et la FIFO**

```kotlin
internal sealed interface ClipboardControllerEvent {
    data class EntryStored(val id: ClipboardEntryId) : ClipboardControllerEvent
    data class Failure(val failure: ClipboardFailure) : ClipboardControllerEvent
}

internal enum class ClipboardActivationState {
    DISABLED,
    ENABLED,
    CLEARING_ENABLED,
    DISABLING,
    RESETTING_ENABLED,
}

internal interface ClipboardActivationStore {
    fun readState(): ClipboardActivationState
    fun writeState(state: ClipboardActivationState): Boolean
    /** null = no suppression; TimestampUnavailable is a persisted fail-closed suppression. */
    fun suppressedSourceMarker(): ClipboardSourceMarker?
    fun setSuppressedSourceMarker(marker: ClipboardSourceMarker?): Boolean
}

internal interface ClipboardAdministrativeParticipant {
    suspend fun revokeAccessAndJoin()
    suspend fun clearMemoryCaches()
}

internal class ClipboardAdministrativeRuntime(
    private val keys: ClipboardKeyProvider,
    private val containerStore: EncryptedClipboardBlobStore,
) {
    fun register(participant: ClipboardAdministrativeParticipant): Closeable
    suspend fun revokeAllAndJoin()
    suspend fun clearAllCaches()
    fun deleteTemporaryArtifacts()
    fun deleteKeys()
    fun recreateKeys()
}

internal class ClipboardHistoryController(
    private val gateway: SystemClipboardGateway,
    private val ingestor: ClipboardIngestor,
    private val repository: ClipboardHistoryRepository,
    private val activation: ClipboardActivationStore,
    private val administrativeRuntime: ClipboardAdministrativeRuntime,
    private val applicationScope: CoroutineScope,
) {
    private var queue: Channel<QueuedClip>? = null
    private var inFlightPermits: Semaphore? = null
    private var workerJob: Job? = null
    val state: StateFlow<ClipboardHistoryState>
    val events: SharedFlow<ClipboardControllerEvent>

    fun start()
    suspend fun enable()
    fun onPrimaryClipChanged()
    suspend fun clearAllAndResume()
    suspend fun disableAndPurge()
    suspend fun resetProtectedHistory()
    fun onEditorPrivacyChanged(privateEditor: Boolean)
    suspend fun awaitIdle()
    suspend fun shutdownForTest()
}
```

Le controller conserve le dernier `privateEditor` fourni par `onEditorPrivacyChanged`, initialisé à `true` (fail-closed) ; `start()` et `enable()` n’acceptent donc aucun booléen divergent depuis Settings/panel. `onPrimaryClipChanged()` capture immédiatement l’instantané **avant** toute décision sur la suppression. Si `suppressedSourceMarker()` est présent, il compare les marqueurs typés puis n’efface durablement ce marqueur et n’ingère la copie que pour `DEFINITELY_CHANGED`; `SAME_OR_COLLIDING` et `UNKNOWN` gardent la suppression. API 31+ compare le timestamp source persisté : différent prouve seulement un changement, égal n’est jamais une identité ; timestamp absent reste `UNKNOWN`. API 24–30 utilise `LegacyListenerEvent`, dont le callback est la preuve legacy documentée. `start()` ne compare ni n’efface jamais le marqueur. `QueuedClip` contient l’instantané déjà copié et le booléen privacy au moment exact du listener. `start/enable` créent une Channel neuve, un `Semaphore(ClipboardLimits.INGEST_QUEUE_CAPACITY)` neuf et un unique consumer `for (clip in queue)` si aucun worker n’existe. Le listener doit acquérir un permit avec `tryAcquire()` **avant** `trySend`; un échec d’acquisition ou d’envoi publie `QUEUE_SATURATED`, et l’échec d’envoi rend immédiatement son permit. Le consumer rend exactement un permit dans son `finally` après chaque travail. La limite 32 couvre donc le travail actif **plus** toute la file, et non 32 en attente en plus de l’actif.

Après `ingestor.prepare` puis `repository.store`, le consumer mappe **les deux** résultats `StoreResult.Inserted(summary)` et `StoreResult.Duplicate(summary)` vers `ClipboardControllerEvent.EntryStored(summary.id)` ; Failure reste Failure. C’est l’unique source du prompt de Task 10, ce qui évite de confondre événements techniques du repository et événements produit de capture.

`clearAllAndResume()` écrit d’abord `CLEARING_ENABLED`, retire le listener, ferme/annule le worker et le joint, capture le primary clip courant puis persiste le `sourceMarker` de son résultat avant d’appeler `repository.clearAll()`; seulement après la purge il écrit `ENABLED`, recrée queue/permits/worker et rattache le listener, sans réimporter le primary clip ancien. Il persiste donc aussi le marqueur d’un clip rejeté/borné ; si la capture ne fournit pas de timestamp API 31+, il persiste `TimestampUnavailable` et reste fail-closed. Le marqueur survit au redémarrage et seul `DEFINITELY_CHANGED` dans un callback listener le retire. `disableAndPurge` suit la même barrière puis purge et désactive, mais permet un futur `enable` qui recrée queue+worker. Les générations ne filtrent que `state/events`, jamais le consumer.

`ClipboardActivationStore` persiste l’enum et le `ClipboardSourceMarker?` par `SharedPreferences.commit()` et vérifie le booléen de retour. `disableAndPurge` écrit `DISABLING` **avant** de retirer listener/queue/grants et d’effacer ; le startup voyant cet état reprend la purge sans aucune capture, retire le marqueur anti-réimport, puis écrit `DISABLED` en dernier. `resetProtectedHistory` suit `RESETTING_ENABLED`, arrête capture/grants, capture/persiste le marqueur avant purge, efface DB/containers/caches, supprime puis recrée les clés, revient `ENABLED` et rattache un listener vide sans réimporter l’ancien primary clip. Aucun contenu n’est effacé pour `KEY_UNAVAILABLE` tant que l’utilisateur n’a pas confirmé cette action.

`ClipboardAdministrativeRuntime` est un registre app-scoped sérialisé : il prend un snapshot de participants puis les attend hors verrou. Task 8 y enregistre grants/permissions/pipes ; Task 9 y enregistre panel/prompt/Bitmap/search caches. `deleteTemporaryArtifacts()` supprime seulement `.part`/miniatures orphelines sous la racine validée du store. `deleteKeys/recreateKeys` délèguent aux deux alias Keystore exacts. L’ordre controller est fixe : état durable → listener stop → FIFO cancel/join → `revokeAllAndJoin` → `clearAllCaches` → `repository.clearAll` (DB + containers) → temporaires → clés si disable/reset → état final. Aucun contrôleur Settings ne touche directement ces dépendances.

`start()` branche exhaustivement sur l’état durable : `DISABLED` ne crée rien ; `CLEARING_ENABLED` reprend le clear puis revient `ENABLED` sans import ; `DISABLING` reprend la purge ; `RESETTING_ENABLED` reprend le reset ; `ENABLED` réconcilie, crée FIFO/worker/listener puis importe une fois le primary clip uniquement si `suppressedSourceMarker()==null`. Il ne compare ni ne change jamais ce marqueur. `enable()` est une nouvelle décision explicite : il retire d’abord le marqueur, écrit `ENABLED`, crée les mêmes ressources puis importe le clip courant une fois ; si une écriture durable échoue, il publie un Failure sûr et n’attache pas le listener.

`shutdownForTest()` n’est utilisé que par les fixtures/composant de test : il retire le listener, annule/joint worker puis executor. En production, le coordinator vit exactement aussi longtemps que le processus applicatif ; la mort du processus arrête naturellement listener et coroutines, et `reconcile()` enlève tout `.part` au prochain démarrage. `awaitIdle()` permet aux tests de joindre le travail accepté sans détruire le coordinator.

`applicationScope` appartient au composant unique, jamais au service, à Settings ou à `sessionScope` : `onStartInput`, `onFinishInput`, destruction/recréation du service et changement de sélection ne peuvent pas abandonner A ou B déjà acceptés. Seules les barrières administratives `clearAllAndResume`, `disableAndPurge`, la mort du processus et `shutdownForTest` annulent le consumer.

- [ ] **Step 5: Construire le composant partagé sans service permanent**

`ClipboardComponent.get(applicationContext)` crée paresseusement sous verrou, dans cet ordre sans cycle : database + key store + crypto + container store ; `ClipboardAdministrativeRuntime(keys, containerStore)` ; revocation hub ; payload reader ; repository ; `ClipboardStorageSpaceManager(repository comme StorageSpaceReclaimer)` ; ingestor + gateway ; `applicationScope` ; enfin **un seul** `ClipboardHistoryController` recevant ce runtime. Le composant expose le runtime seulement comme registre de `ClipboardAdministrativeParticipant`, jamais les clés/store directement. Ce controller est l’unique propriétaire du listener et de la FIFO pour tout le processus. Settings/activity partagent repository/crypto/reader et appellent les opérations administratives de ce même controller ; ils ne créent jamais de listener, Channel ou worker concurrent. Task 8 étendra ce composant avec grants/session/provider et enregistrera leur revoker dans le hub et le runtime, sans recréer le stockage. Ajouter un test qui ouvre service + Settings simultanément, bloque A puis lance clear depuis Settings : le worker unique est joint avant purge et A ne peut pas réapparaître. Aucun `Service` Android supplémentaire ni notification n’est ajouté.

- [ ] **Step 6: Brancher uniquement le cycle de vie du service**

Dans `onCreate`, obtenir `clipboardController = ClipboardComponent.get(applicationContext).controller`. Dans `onStartInput`, appeler d’abord `onEditorPrivacyChanged(editorPrivacy.forceSensitiveClipboard)`, résultat de `EditorPrivacyPolicy.evaluate(info)`. Dans `onCreateInputView/onStartInputView`, appeler `start()` si activé. Dans `onFinishInput`, transmettre `privateEditor=true` avant de perdre l’éditeur afin qu’une copie sans provenance claire échoue fermée, sans arrêter la FIFO. Dans `onDestroy`, détacher uniquement les collecteurs UI du `serviceScope`; ne jamais fermer le controller, repository, base ou grants partagés. Tester qu’un second service retrouve la même FIFO sans double listener et que le clavier reste saisissable si le repository est en `Error`.

- [ ] **Step 7: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardActivationStoreTest' --tests '*ClipboardHistoryControllerTest' --tests '*JefeKeyboardServiceTest' --no-daemon`

Expected: PASS ; listener opt-in, FIFO exacte, overflow visible, aucune dépendance réseau.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardActivationStore.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardAdministrativeRuntime.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryController.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardComponent.kt app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardActivationStoreTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryControllerTest.kt app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt
git commit -m "feat: ingest clipboard changes through an opt-in fifo"
```

### Task 8: Grants temporaires, provider chiffré et collage compatible

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardGrantRegistry.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/EditorSessionRegistry.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardContentProvider.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPasteCoordinator.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardComponent.kt` (grants/session + revocation hub)
- Modify: `app/src/main/AndroidManifest.xml` (`<application>` provider)
- Modify: `app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt` (authoritative `InputBinding.uid` handoff)
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardGrantRegistryTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/EditorSessionRegistryTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPasteCoordinatorTest.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardContentProviderInstrumentedTest.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardPasteInstrumentedTest.kt`
- Modify: `settings.gradle.kts` (include test-only `:clipboard-test-client` module)
- Create: `clipboard-test-client/build.gradle.kts`
- Create: `clipboard-test-client/src/main/AndroidManifest.xml`
- Create: `clipboard-test-client/src/main/java/ovh/jefe/keyboard/testclient/RemoteClipboardProbeService.kt`

**Interfaces:**
- Produces: application-scoped `EditorSessionRegistry` and token grant bound to entry/payload selector/MIME/session/target UID, 60 s, maximum three `openFile` calls.
- Produces: text/link ≤128 KiB UTF-8 via one `commitText`; larger text through one scoped `text/plain` `commitContent` when supported, otherwise refusal before mutation ; HTML rich when accepted else the same bounded text fallback ; rich item one `commitContent`; group text one joined direct commit or one joined provider payload ; group rich item-by-item only.
- API split: API 24 grants URI manually; API 25+ uses `INPUT_CONTENT_GRANT_READ_URI_PERMISSION`.

- [ ] **Step 1: Écrire les tests RED du registre**

```kotlin
@Test
fun `grant accepts only matching uid session and first three opens`() {
    val clock = FakeElapsedClock(1_000)
    val registry = ClipboardGrantRegistry(clock, "ovh.jefe.keyboard.clipboard", RecordingPermissionRevoker())
    val grant = registry.issue(
        payload = resolvedItemPayload("e1", 0, ClipboardPayloadRole.CONTENT, "image/png", 3),
        targetUid = 42, sessionId = 7,
        safeDisplayName = "Image", isSensitive = false,
    )

    val first = registry.authorizeOpenAndLease(grant.token, 42, 7)
    val second = registry.authorizeOpenAndLease(grant.token, 42, 7)
    val third = registry.authorizeOpenAndLease(grant.token, 42, 7)
    assertTrue(first is GrantOpenAuthorization.Allowed)
    assertTrue(second is GrantOpenAuthorization.Allowed)
    assertTrue(third is GrantOpenAuthorization.Allowed)
    assertEquals(ClipboardGrantState.EXHAUSTED, registry.stateOf(grant.token))
    assertTrue(registry.authorizeOpenAndLease(grant.token, 42, 7) is GrantOpenAuthorization.Denied)
    assertTrue(registry.authorizeOpenAndLease(grant.token, 43, 7) is GrantOpenAuthorization.Denied)
}

@Test
fun `grant expires on time session deletion or explicit revoke`() {
    val clock = FakeElapsedClock(0)
    val registry = ClipboardGrantRegistry(clock, "ovh.jefe.keyboard.clipboard", RecordingPermissionRevoker())
    val grant = registry.issue(
        payload = resolvedItemPayload("e1", 0, ClipboardPayloadRole.CONTENT, "application/pdf", 4),
        targetUid = 42, sessionId = 7,
        safeDisplayName = "Fichier", isSensitive = false,
    )
    clock.advanceBy(60_001)
    assertTrue(registry.authorizeOpenAndLease(grant.token, 42, 7) is GrantOpenAuthorization.Denied)
    assertNull(registry.stateOf(grant.token))
}
```

`resolvedItemPayload` est un helper de test qui passe par le vrai resolver et un manifest authentifié de fixture, jamais un constructeur libre. Ajouter les cas mauvaise session, entrée supprimée, token inconnu et collision impossible avec un générateur déterministe de test. Le mauvais MIME se teste au provider via `openTypedAssetFile`, car `getType/query/openFile` ne reçoivent aucun MIME appelant. Ajouter des RED prouvant qu’un sélecteur `image/png` ne peut produire metadata `application/pdf`, qu’une taille différente du `plainByteSize` authentifié est impossible, et qu’un groupe `TEXT, HTML/FALLBACK, LINK` conserve exactement cet ordre et choisit FALLBACK pour HTML. Ajouter deux courses déterministes : revoke entre `authorizeOpenAndLease` et `lease.attach` refuse l’attach et ferme pipe/job ; la troisième lease attachée lit jusqu’au dernier octet malgré l’état `EXHAUSTED`, alors que la quatrième est refusée. Dans `EditorSessionRegistryTest`, une nouvelle session révoque tokens, permissions et pipe jobs de l’ancienne avant publication ; `finish` rend `current=null`; même id avec UID/package/connection différent est refusé ; provider sans session courante ne révèle aucune metadata.

- [ ] **Step 2: Écrire les tests RED du coordinateur de collage**

Couvrir avec `RecordingInputConnection` : texte exact, lien exact sans réseau, `commitText=false` sans seconde mutation, exactement 128 KiB UTF-8 via un seul `commitText`, 128 KiB + 1 avec `text/plain` annoncé via un seul `commitContent`, la même taille sans MIME compatible via `TEXT_TOO_LARGE_FOR_EDITOR` et zéro mutation, HTML compatible → un `commitContent`, HTML incompatible petit → un seul repli texte, HTML incompatible volumineux → provider `text/plain` ou refus sûr, image MIME compatible/incompatible, groupe textuel joint par `\n` via un seul commit direct sous la borne ou un seul payload provider au-dessus, groupe riche qui refuse cette action et expose chaque item, premier item riche refusé sans tentative suivante. Vérifier aussi Unicode à la frontière d’octets UTF-8, `RemoteException`, session devenue obsolète et révocation immédiate du grant refusé. Ajouter UID `InputBinding` absent, package absent et package dont l’UID diffère : zéro grant et zéro mutation ; un UID binding correct est l’autorité même si une fixture `EditorInfo` tente d’en fournir une autre.

- [ ] **Step 3: Vérifier le RED JVM**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardGrantRegistryTest' --tests '*ClipboardPasteCoordinatorTest' --no-daemon`

Expected: FAIL à la compilation sur registry/provider/coordinator.

- [ ] **Step 4: Créer le helper inter-UID et les tests Android RED, sans production**

Créer le module `clipboard-test-client` et son `RemoteClipboardProbeService`, puis écrire `ClipboardContentProviderInstrumentedTest` et `ClipboardPasteInstrumentedTest`. Les tests référencent les contrats provider/session/collage encore absents et couvrent API 24/34, UID distinct réel, MIME, expiration, lectures concurrentes, gros texte `text/plain`, groupe textuel joint et révocation.

Build/install helper: `./gradlew :clipboard-test-client:assembleDebug --no-daemon`, puis `adb install -r clipboard-test-client/build/outputs/apk/debug/clipboard-test-client-debug.apk`.

Run RED: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.ClipboardContentProviderInstrumentedTest,ovh.jefe.keyboard.clipboard.ClipboardPasteInstrumentedTest --no-daemon`

Expected: FAIL à la compilation du module de test app sur les contrats provider/coordinator manquants. Enregistrer ce RED avant toute classe de production de cette tâche.

- [ ] **Step 5: Implémenter un registre lié et monotone**

```kotlin
internal fun interface ElapsedRealtimeClock {
    fun nowMillis(): Long
}

internal enum class ClipboardGrantState { ACTIVE, EXHAUSTED, REVOKED }

internal class ResolvedClipboardGrantPayload private constructor(
    val entryId: ClipboardEntryId,
    val orderedSelectors: List<ClipboardPayloadSelector>,
    val mimeType: String,
    val plainByteSize: Long,
    val joinWithNewlines: Boolean,
) {
    companion object {
        fun item(entry: LoadedClipboardEntry, itemIndex: Int, preferredMime: String): ResolvedClipboardGrantPayload?
        fun joinedTextGroup(entry: LoadedClipboardEntry): ResolvedClipboardGrantPayload?
    }

    override fun toString(): String =
        "ResolvedClipboardGrantPayload(entry=${entryId.value}, parts=${orderedSelectors.size}, redacted=true)"
}

internal data class ClipboardGrant(
    val token: String,
    val uri: Uri,
    val payload: ResolvedClipboardGrantPayload,
    val targetUid: Int,
    val sessionId: Long,
    val expiresAtElapsed: Long,
    val remainingOpens: Int,
    val safeDisplayName: String,
    val isSensitive: Boolean,
    val state: ClipboardGrantState,
) {
    val entryId: ClipboardEntryId get() = payload.entryId
    val mimeType: String get() = payload.mimeType
    val plainByteSize: Long get() = payload.plainByteSize
}

internal sealed interface GrantAuthorization {
    data class Allowed(val grant: ClipboardGrant) : GrantAuthorization
    data object Denied : GrantAuthorization
}

internal sealed interface GrantOpenAuthorization {
    data class Allowed(val grant: ClipboardGrant, val lease: ActiveGrantLease) : GrantOpenAuthorization
    data object Denied : GrantOpenAuthorization
}

internal interface ActiveGrantLease : Closeable {
    fun attach(writeEnd: ParcelFileDescriptor, writer: Job): Boolean
    fun complete()
}

internal fun interface ClipboardCorruptionReporter {
    fun report(entryId: ClipboardEntryId)
}

internal object ClipboardGrantUri {
    fun build(authority: String, token: String): Uri
    fun parseOrNull(authority: String, uri: Uri): String?
}

internal fun interface ClipboardUriPermissionRevoker {
    fun revoke(uri: Uri)
}

internal class ClipboardGrantRegistry(
    private val clock: ElapsedRealtimeClock,
    private val authority: String,
    private val permissionRevoker: ClipboardUriPermissionRevoker,
    private val random: SecureRandom = SecureRandom(),
) {
    fun issue(
        payload: ResolvedClipboardGrantPayload,
        targetUid: Int,
        sessionId: Long,
        safeDisplayName: String,
        isSensitive: Boolean,
    ): ClipboardGrant
    fun authorizeMetadata(token: String, callingUid: Int, sessionId: Long): GrantAuthorization
    fun authorizeOpenAndLease(token: String, callingUid: Int, sessionId: Long): GrantOpenAuthorization
    fun revokeTokenNow(token: String): List<Job>
    fun revokeSessionNow(sessionId: Long): List<Job>
    suspend fun revokeEntryAndJoin(entryId: ClipboardEntryId)
    suspend fun revokeAllAndJoin()
    fun stateOf(token: String): ClipboardGrantState?
}

internal data class EditorSessionIdentity(
    val id: Long,
    val targetUid: Int,
    val packageName: String,
    val connection: InputConnection,
)

internal class EditorSessionRegistry(
    private val grants: ClipboardGrantRegistry,
) : EditorSessionValidator {
    fun start(targetUid: Int, packageName: String, connection: InputConnection): EditorSessionIdentity
    fun finish(sessionId: Long)
    fun current(): EditorSessionIdentity?
    override fun isCurrent(target: EditorPasteTarget): Boolean
}
```

Le token contient 16 octets aléatoires encodés avec `android.util.Base64.encodeToString(bytes, URL_SAFE or NO_WRAP or NO_PADDING)` — jamais `java.util.Base64`, absente en API 24. Toute comparaison sensible utilise `MessageDigest.isEqual`. `ClipboardGrantUri` n’accepte que `content://<authority>/v1/<token>` avec exactement deux segments, aucun user-info/port/query/fragment, autorité exacte et token canonique redécodé à 16 octets ; toute autre forme échoue avant lookup. `issue` construit et stocke cette URI avec l’autorité injectée et n’accepte aucun MIME/taille/id séparé du payload résolu.

`ResolvedClipboardGrantPayload.item` part uniquement d’un `LoadedClipboardEntry` authentifié, résout le payload exact compatible avec `preferredMime`, puis copie `entryId`, selector, MIME et `plainByteSize` depuis ce même ref. `joinedTextGroup` exige un groupe entièrement textuel et fabrique une recette immuable dans l’ordre du manifest : rôle TEXT pour texte/lien, rôle FALLBACK pour HTML, MIME final fixe `text/plain`, taille égale à la somme overflow-safe des refs plus un octet `\n` entre items. Ses constructeurs restent privés ; ni registry, coordinator ni provider ne peuvent fournir séparément une metadata divergente. Le provider revalide quand même cette recette contre le manifest READY au moment d’écrire, fail-closed si revision/payload diffère.

`ClipboardUriPermissionRevoker` est l’adapter Android exact `context.revokeUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` et devient un fake en JVM. `authorizeMetadata` valide UID/session/temps sans consommer d’ouverture et retourne le grant autorisé, dont MIME et taille sont dérivés du payload résolu. Toute observation d’un grant expiré le passe `REVOKED`, retire son token puis révoque sa permission ; `stateOf` retourne donc `null`. `authorizeOpenAndLease` valide les mêmes champs, crée et enregistre la lease **dans la même section critique** que la décrémentation ; revoke ne peut donc jamais manquer un pipe tardif. `lease.attach` s’exécute sous le même état : si déjà révoquée elle ferme immédiatement PFD/job et retourne false.

Après la troisième autorisation, le grant passe `EXHAUSTED`: metadata et nouvelles ouvertures sont refusées et la permission URI est retirée, mais les trois leases déjà accordées peuvent terminer. Seuls expiration, changement de session, suppression/`REVOKING` ou revoke explicite passent `REVOKED`, annulent les jobs et ferment les write-ends actifs. `revokeTokenNow/revokeSessionNow` ferment/cancel synchroniquement et rendent les jobs à joindre hors verrou ; `revokeEntryAndJoin/revokeAllAndJoin` attendent explicitement leur fin pour la barrière repository. `ClipboardComponent` programme un job basé sur `elapsedRealtime` pour révoquer registre **et** permission URI Android à 60 s même si le client ne rappelle jamais le provider.

`ClipboardComponent` construit un unique `EditorSessionRegistry`, qui alloue lui-même les ids avec un `AtomicLong` monotone de durée de processus. Dans `onStartInput`, après disponibilité de binding/connection/package, le service appelle `sessionRegistry.start(uid, package, connection)` et conserve l’identité retournée ; cette méthode appelle `revokeSessionNow` sur l’ancienne session avant d’installer la nouvelle. `onFinishInput/onDestroy` appelle `finish` avec l’id conservé, et ne peut donc pas fermer une session plus récente créée par un autre service. Le provider demande `sessionRegistry.current()` à chaque `getType/query/openFile`, exige id+UID courants puis passe cet id à `authorizeMetadata/authorizeOpenAndLease`; il n’invente jamais une « session courante » locale.

À l’initialisation Task 8, `ClipboardComponent` crée une seule fois grants + session registry puis enregistre dans son `ClipboardEntryAccessRevocationHub` un listener qui appelle `grants.revokeEntryAndJoin` pour chaque id. Il enregistre aussi le registre comme `ClipboardAdministrativeParticipant`: `revokeAccessAndJoin = grants.revokeAllAndJoin`, cache no-op. Les deux `Closeable` restent possédés par le composant pour toute la vie du processus. Ainsi delete manuel, clear, purge quota, désépinglage et reconcile empruntent tous la barrière `REVOKING` de Task 6, tandis que disable/reset révoquent même un grant en cours de création.

- [ ] **Step 6: Ajouter le provider non exporté et son pipe**

```xml
<provider
    android:name=".clipboard.ClipboardContentProvider"
    android:authorities="${applicationId}.clipboard"
    android:exported="false"
    android:grantUriPermissions="true" />
```

`getType(uri)` et `query()` commencent par `ClipboardGrantUri.parseOrNull`, obtiennent l’identité depuis `EditorSessionRegistry`, puis appellent `authorizeMetadata(token, Binder.getCallingUid(), session.id)` et vérifient `repository.isReady(grant.entryId)` avant toute réponse ; `getType` retourne uniquement `grant.mimeType`. Comme ces callbacks ContentProvider sont synchrones, un helper privé exécute uniquement ce booléen metadata sur le dispatcher DB avec `runBlocking { withTimeout(2_000) { repository.isReady(id) } }`; timeout/exception = refus, jamais lecture sur le main thread ni déchiffrement. `query()` répond depuis les champs sûrs déjà copiés dans le grant : `OpenableColumns.SIZE=plainByteSize` et `DISPLAY_NAME=safeDisplayName` (`Contenu sensible` si sensible, sinon un type générique borné), sans appel repository suspendu supplémentaire ni nom privé. `openTypedAssetFile(uri, mimeFilter, ...)` appelle d’abord cette autorisation metadata et exige `ClipDescription.compareMimeTypes(grant.mimeType, mimeFilter)` avant de déléguer à l’ouverture ; un filtre incompatible est refusé sans consommer d’ouverture.

`openFile(uri, "r")` refait parse/session, refuse tout autre mode, puis appelle `authorizeOpenAndLease(token, uid, session.id)` et vérifie READY depuis le grant retourné. Il crée le pipe et un job IO `CoroutineStart.LAZY`, appelle `lease.attach(writeEnd, job)`, **revérifie READY après attach**, démarre seulement alors le writer et retourne le read-end. Échec attach/recheck ferme les deux extrémités. Le reader valide d’abord une passe GCM complète vers un sink nul ; uniquement après tag valide, il repositionne le même `StableCiphertextSnapshot` et écrit la seconde passe. Le lecteur peut bloquer, mais ne reçoit aucun octet avant authentification. Le writer appelle `lease.complete()` dans `finally` avant de signaler une corruption afin d’éviter de joindre son propre job.

Une altération publie seulement l’id sur un `ClipboardCorruptionReporter` app-scoped ; un collector séparé appelle `repository.quarantineCorrupt(id)`, qui fait `READY -> REVOKING -> revokeAccessAndJoin -> DELETING` puis nettoyage. Tag invalide signifie donc zéro octet clair livré, aucune auto-deadlock et entrée indisponible. Le test bloque précisément entre authorize/attach, entre attach/recheck et pendant la troisième lecture.

Le provider délègue uniquement au payload reader : une recette à un selector appelle `writeExact(entryId, selector, output)` ; une recette `joinWithNewlines` appelle `writeJoinedTextGroupExact(entryId, orderedSelectors, output)`. Le reader revalide selectors, MIME, ordre et taille totale contre le manifest authentifié avant d’écrire, puis diffuse les représentations séparées par `\n` sans jamais créer une chaîne de 25 MiB. Pour `Inline`, le manifest entier est authentifié avant création du pipe ; pour `Blob`, le reader reconstruit l’AAD et utilise le même snapshot stable/fd pour les deux passes. Le provider ne manipule donc ni coordonnées de conteneur brutes ni hypothèse sur le stockage de HTML/fallback.

Le module `clipboard-test-client` est une petite application **debug/test uniquement**, applicationId `ovh.jefe.keyboard.testclient`, minSdk 24. Son `RemoteClipboardProbeService` exporté dans ce seul APK accepte par Binder une URI déjà grantée, exécute `getType/query/openFile` depuis son UID distinct et ne retourne aux tests que statut, MIME, taille, nom sûr et digest SHA-256 — jamais le payload en Bundle/log. Les tests installent app + helper, résolvent l’UID helper réel, émettent le grant vers son package et prouvent sur API 24 et 34 : mauvais UID refusé, bon UID lit exactement, mauvais MIME/session refusé, troisième ouverture finit, quatrième échoue et revoke ferme une lecture lente. Ce module n’est jamais une dépendance/runtime du module `app` et n’entre pas dans l’APK livré.

- [ ] **Step 7: Implémenter le coordinateur et le chemin API 24**

```kotlin
internal sealed interface ClipboardPasteResult {
    data class Success(
        val entryId: ClipboardEntryId,
        val isSensitive: Boolean,
    ) : ClipboardPasteResult
    data class OpenGroup(val entryId: ClipboardEntryId) : ClipboardPasteResult
    data class Failure(val failure: ClipboardFailure) : ClipboardPasteResult
}

internal data class EditorPasteTarget(
    val connection: InputConnection,
    val editorInfo: EditorInfo,
    val targetUid: Int,
    val editorSessionId: Long,
)

internal fun interface EditorSessionValidator {
    fun isCurrent(target: EditorPasteTarget): Boolean
}

internal class ClipboardPasteCoordinator(
    private val context: Context,
    private val repository: ClipboardHistoryRepository,
    private val payloadReader: ClipboardPayloadReader,
    private val grants: ClipboardGrantRegistry,
    private val sessionValidator: EditorSessionValidator,
) {
    suspend fun pasteEntry(
        id: ClipboardEntryId,
        target: EditorPasteTarget,
    ): ClipboardPasteResult

    suspend fun pasteGroupItem(
        id: ClipboardEntryId,
        itemIndex: Int,
        target: EditorPasteTarget,
    ): ClipboardPasteResult

    suspend fun pasteTextGroupAll(
        id: ClipboardEntryId,
        target: EditorPasteTarget,
    ): ClipboardPasteResult
}
```

Le service construit `EditorPasteTarget` uniquement depuis `currentInputConnection`, `currentInputEditorInfo`, `currentInputBinding?.uid` et l’identité courante retournée par `EditorSessionRegistry`; si l’un manque ou diffère, il refuse avant d’appeler le coordinateur. `EditorSessionValidator` compare à chaque fois id de session, identité de `InputConnection`, UID du binding et package courant. Le coordinateur l’appelle avant le load, **après tout load/déchiffrement suspendu**, immédiatement avant d’émettre un grant et immédiatement avant chaque `commitText/commitContent`; toute obsolescence révoque ce qui existe et retourne une erreur sûre sans mutation supplémentaire. Le test bloque `repository.load()`, démarre une nouvelle session puis libère le load et exige zéro commit dans l’ancienne connection.

Le coordinateur résout `target.editorInfo.packageName` avec `PackageManager` et exige que ce package appartienne exactement à `target.targetUid`; le nom de package seul n’est jamais une autorité. Prévalider avec `EditorInfoCompat.getContentMimeTypes` et `ClipDescription.compareMimeTypes`. Pour API 24 : `grantUriPermission(packageName, uri, FLAG_GRANT_READ_URI_PERMISSION)`, flags commit = 0. Pour API ≥25 : flags commit = `InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION`. Si `commitContent` retourne false, révoquer grant et permission manuelle immédiatement ; ne jamais essayer ensuite `commitText`.

Le coordinateur choisit le chemin **avant toute mutation de l’éditeur**, à partir du manifest authentifié et du total UTF-8 exact. Pour TEXT/LINK et repli HTML de taille ≤ `MAX_DIRECT_COMMIT_TEXT_UTF8_BYTES`, il obtient les octets via `payloadReader.readExact(..., maxBytes = limite)`, décode UTF-8 strictement, revalide la session puis appelle exactement un `commitText`. Cette borne de 128 KiB garde la transaction Binder largement sous son tampon partagé d’environ 1 MiB, y compris pour l’encodage UTF-16 et les en-têtes Parcel.

Au-dessus de 128 KiB, le coordinateur ne matérialise pas une grande `String` et n’essaie jamais un `commitText` risqué. Si l’éditeur annonce `text/plain`, il demande `ResolvedClipboardGrantPayload.item(loadedEntry, itemIndex, "text/plain")`, émet le grant résolu puis effectue exactement un `commitContent` ; le provider diffuse les octets texte exacts depuis le stockage chiffré. Pour HTML, `text/html` annoncé reste prioritaire ; sinon le resolver choisit explicitement FALLBACK `text/plain`. Si aucun MIME compatible ou payload exact n’est résolu, retourner `TEXT_TOO_LARGE_FOR_EDITOR` **avant** grant et mutation. Un `commitContent=false` ou `RemoteException` révoque immédiatement le grant et ne déclenche aucun repli/second appel.

`pasteTextGroupAll` n’est proposé que si `ResolvedClipboardGrantPayload.joinedTextGroup(loadedEntry)` rend une recette authentifiée : ordre exact, TEXT pour texte/lien, FALLBACK pour HTML, somme UTF-8 plus un octet `\n` entre items avec addition overflow-safe. Sous la borne directe, il lit ces mêmes selectors, joint les petits buffers, les efface dans `finally` et appelle un unique `commitText`. Au-dessus, si `text/plain` est annoncé, il émet cette recette dans un unique grant et un unique `commitContent`; le provider revalide puis diffuse les items ordonnés. Sinon il retourne `TEXT_TOO_LARGE_FOR_EDITOR` sans mutation. Tout groupe contenant un payload riche retourne `OpenGroup`; `pasteEntry` retourne aussi `OpenGroup` pour un parent de groupe, et `pasteGroupItem` reste le seul chemin item par item.

- [ ] **Step 8: Tester le provider réel sur API 24 et 34**

Build/install helper first: `./gradlew :clipboard-test-client:assembleDebug :app:assembleDebug --no-daemon`, puis `adb install -r clipboard-test-client/build/outputs/apk/debug/clipboard-test-client-debug.apk`.

Run: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.ClipboardContentProviderInstrumentedTest,ovh.jefe.keyboard.clipboard.ClipboardPasteInstrumentedTest --no-daemon`

Expected on API 24: grant manuel visible puis révoqué ; payload exact lu depuis l’UID helper via pipe. Expected on API 34: flag commitContent, UID/MIME/session validés, texte 128 KiB direct, texte 128 KiB + 1 et groupe volumineux diffusés exactement par provider, troisième lecture complète, quatrième ouverture refusée, aucune donnée claire sur disque.

- [ ] **Step 9: Contrôler et committer**

```bash
git diff --check
git add settings.gradle.kts clipboard-test-client app/src/main/AndroidManifest.xml app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardComponent.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardGrantRegistry.kt app/src/main/java/ovh/jefe/keyboard/clipboard/EditorSessionRegistry.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardContentProvider.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPasteCoordinator.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardGrantRegistryTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/EditorSessionRegistryTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPasteCoordinatorTest.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardContentProviderInstrumentedTest.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardPasteInstrumentedTest.kt
git commit -m "feat: paste encrypted clipboard content with scoped grants"
```

### Task 9: Panneau natif, consentement, groupes et recherche interne

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ui/ClipboardPanelModels.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ui/ClipboardPanelController.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ui/ClipboardEntryAdapter.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ui/ClipboardPanelView.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ui/ClipboardHistoryActivity.kt`
- Create: `app/src/main/res/layout/clipboard_history_activity.xml`
- Create: `app/src/main/res/drawable/bg_clipboard_tile.xml`
- Create: `app/src/main/res/drawable/bg_clipboard_tile_pressed.xml`
- Create: `app/src/main/res/drawable/ic_search.xml`
- Create: `app/src/main/res/drawable/ic_more.xml`
- Create: `app/src/main/res/drawable/ic_back.xml`
- Modify: `app/src/main/AndroidManifest.xml` (`ClipboardHistoryActivity` non exportée)
- Modify: `app/src/main/java/ovh/jefe/keyboard/KeyboardRootView.kt` (`KeyboardRootMode` and panel host)
- Modify: `app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt` (`KeyboardLayoutMode.SEARCH`)
- Modify: `app/src/main/res/values/strings.xml` (all clipboard labels)
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ui/ClipboardPanelViewTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ui/ClipboardPanelControllerTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ui/ClipboardEntryAdapterTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/KeyboardRootViewTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/KeyboardViewTest.kt`

**Interfaces:**
- Produces: `ClipboardPanelState.Disabled/Loading/Empty/Content/Group/Error`, `ClipboardTileUi`, `ClipboardPanelController`, `ClipboardPanelView.render`.
- Root modes: normal keyboard, full clipboard panel, clipboard search with compact alphabetic keyboard; total IME height unchanged.
- No `EditText` is used inside the IME panel.

- [ ] **Step 1: Écrire les tests RED des états et masquage**

```kotlin
@Test
fun `sensitive tile ignores accidental preview and stays generic`() {
    val panel = ClipboardPanelView(context)
    panel.render(
        ClipboardPanelState.Content(
            pinned = emptyList(),
            recent = listOf(tile(id = "s", sensitive = true, preview = "mot-de-passe")),
            query = "",
        ),
    )
    val tile = panel.visibleTiles().single()
    assertEquals("Contenu sensible ••••••", tile.previewText())
    assertFalse(panel.dumpVisibleText().contains("mot-de-passe"))
    assertFalse(tile.contentDescription.toString().contains("mot-de-passe"))
}

@Test
fun `content has pinned and recent sections in a two column grid`() {
    val panel = ClipboardPanelView(context)
    panel.render(contentState(pinned = 1, recent = 3))
    assertEquals(listOf("Épinglés", "Récents"), panel.sectionTitles())
    assertEquals(2, (panel.recyclerView.layoutManager as GridLayoutManager).spanCount)
}
```

Ajouter les états disabled avec bouton Activer, loading, empty, error réessayable, compte/size, miniatures non sensibles, type fichier, menu 44 dp et ordre TalkBack. Exécuter chaque état sous `uiMode=UI_MODE_NIGHT_NO` puis `UI_MODE_NIGHT_YES` et affirmer les rôles de couleur/contrastes décrits Step 6 ; aucune surface blanche fixe n’est acceptée en sombre.

- [ ] **Step 2: Écrire les tests RED de navigation et recherche sans éditeur**

Vérifier : onglet ouvre le panneau sans modifier `InputConnection`; retour le ferme ; groupe ouvre la liste ordonnée ; appui simple entrée appelle paste ; menu appelle pin/mark sensitive/delete ; recherche bascule `KeyboardView` en `SEARCH`, les caractères alimentent `panel.query`, delete retire un code point, espace ajoute un espace, close annule query et rend les callbacks à l’éditeur ; une réponse de recherche ancienne ne remplace jamais la plus récente.

Dans `ClipboardPanelControllerTest`, utiliser un repository dont `load()` est contrôlable : le contrôleur observe les summaries, charge/mappe par lots de 32 sur IO, ferme chaque `LoadedClipboardEntry` dans `finally`, neutralise une entrée sensible avant publication, annule l’ancien lot sur nouvelle émission et libère toutes les références/bitmaps quand le panneau se ferme. Tester `setQuery` générationnel, `openGroup`, retry, pin/unpin avec confirmation, marquer sensible, delete, clear, ainsi que l’absence de toute logique repository/déchiffrement dans `ClipboardPanelView`. Ajouter le cas `Tout effacer` depuis le panneau ouvert : l’opération administrative finit sans auto-annuler son appelant, le panneau reste vivant/Empty, puis une nouvelle copie C est observée et affichée.

- [ ] **Step 3: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardPanelControllerTest' --tests '*ClipboardPanelViewTest' --tests '*ClipboardEntryAdapterTest' --tests '*KeyboardRootViewTest' --tests '*KeyboardViewTest' --no-daemon`

Expected: FAIL à la compilation sur panel/adapter/modes.

- [ ] **Step 4: Définir des modèles UI sans payload sensible**

```kotlin
internal class ClipboardTileUi(
    val id: ClipboardEntryId,
    val itemIndex: Int?,
    val kind: ClipboardKind,
    val preview: String,
    val typeLabel: String,
    val isPinned: Boolean,
    val isSensitive: Boolean,
    val itemCount: Int,
    val storedByteSize: Long,
    val lastCopiedAt: Long,
    val revision: Long,
    val thumbnail: Bitmap?,
    val contentDescription: String,
) {
    override fun toString(): String =
        "ClipboardTileUi(id=${id.value}, kind=$kind, sensitive=$isSensitive, redacted=true)"
}

internal sealed interface ClipboardPanelState {
    data object Disabled : ClipboardPanelState
    data object Loading : ClipboardPanelState
    data object Empty : ClipboardPanelState
    class Content(
        val pinned: List<ClipboardTileUi>,
        val recent: List<ClipboardTileUi>,
        val query: String,
    ) : ClipboardPanelState {
        override fun toString(): String =
            "ClipboardPanelState.Content(pinned=${pinned.size}, recent=${recent.size}, redacted=true)"
    }
    data class Group(val parent: ClipboardTileUi, val items: List<ClipboardTileUi>) : ClipboardPanelState
    data class Error(val failure: ClipboardFailure, val canRetry: Boolean) : ClipboardPanelState
}

internal interface ClipboardPanelActions {
    suspend fun enable()
    suspend fun paste(tile: ClipboardTileUi): ClipboardPasteResult
    suspend fun setPinned(id: ClipboardEntryId, pinned: Boolean, revision: Long, confirmPurge: Boolean): PinResult
    suspend fun markSensitive(id: ClipboardEntryId): Boolean
    suspend fun delete(id: ClipboardEntryId): Boolean
    suspend fun clearAll()
}

internal class ClipboardPanelController(
    private val repository: ClipboardHistoryRepository,
    private val payloadReader: ClipboardPayloadReader,
    private val actions: ClipboardPanelActions,
    private val scope: CoroutineScope,
) : Closeable {
    val state: StateFlow<ClipboardPanelState>
    fun start()
    fun setQuery(query: String)
    fun openGroup(id: ClipboardEntryId)
    fun closeGroup()
    fun retry()
    override fun close()
}
```

Le contrôleur court-circuite une summary sensible **avant** `repository.load` ou tout appel payloadReader ; le test exige compteurs load/read = 0. Le mapper UI remplace impérativement preview/thumbnail/description par des valeurs génériques si `isSensitive`, même si le repository lui remet une valeur erronée. `typeLabel` est recalculé uniquement depuis `ClipboardKind` (`Texte`, `Image`, `Vidéo`, `Audio`, `Fichier`, `Groupe`) afin d’informer sans révéler MIME précis ni nom ; `storedByteSize` et `lastCopiedAt` viennent des metadata sûres et rendent taille/heure. Les listes sensibles ne passent jamais dans le moteur de recherche.

Le contrôleur est l’unique consommateur UI du repository : son scope de panneau contient un `observationJob` durable et des jobs enfants séparés `mapping/search/decode`. Il observe metadata et `repository.events`, charge les manifests nécessaires sur IO par lots annulables, puis utilise `payloadReader.readVerifiedPreviewPrefix` et `readExact` (thumbnail bornée) avant de fermer tous les buffers dans `finally`. TEXT/HTML >64 KiB et miniatures Blob restent donc affichables sans accès direct au ciphertext ; `displayName` reste borné dans le manifest. Dès `EntryPromoting`, il remplace optimistement la tuile par sa forme sensible, recycle Bitmap, annule recherche/read en cours et demande au service de remasquer/dismiss le prompt du même id. La View et l’adapter ne voient que `ClipboardTileUi` et des callbacks.

À l’ouverture il s’enregistre comme participant administratif. `clearMemoryCaches()` annule/joint seulement mapping/search/decode, recycle les bitmaps et libère preview/query ; il ne ferme ni le scope du panneau, ni `observationJob`, ni la coroutine administrative appelante. Après la barrière, l’observation publie naturellement Empty puis les nouvelles copies. `close()` seul annule observation + scope et retire le handle du runtime. Les actions clear/disable/reset délèguent au composant, qui lance la transaction administrative dans un `SupervisorJob` enfant de `applicationScope` indépendant du scope panneau/service, puis rend son résultat par `Deferred.await`; aucun participant ne peut donc annuler son propre appelant. Aucun payload n’est placé dans un ViewModel applicatif, singleton ou état sauvegardé.

`itemIndex = 0` pour une entrée simple, `null` pour la tuile parent d’un groupe, puis l’ordinal réel pour chaque enfant. `DiffUtil.areContentsTheSame` compare `revision` et les champs UI par une méthode explicite (la classe n’est pas une `data class`) ; le callback de groupe transmet toujours `(id, requireNotNull(itemIndex))` à `pasteGroupItem`. Un test sentinelle injecte un aperçu secret et exige son absence de `toString()` pour la tuile, les hits de recherche, les prompts et les états UI ; ces modèles implémentent tous un `toString()` expurgé.

- [ ] **Step 5: Construire RecyclerView et actions natives**

`ClipboardEntryAdapter` utilise `ListAdapter` + `DiffUtil` par id/revision, `GridLayoutManager(2)` et `SpanSizeLookup` : headers/états/toolbar occupent 2 colonnes, tiles 1. Chaque tile expose appui, menu explicite et long appui vers le même menu. Le panel possède une toolbar Retour, titre, compteur, recherche et Tout effacer ; toutes les cibles ont `minimumWidth/minimumHeight = 44dp`.

- [ ] **Step 6: Appliquer le thème système puis transformer la racine en hôte de modes stables**

Réutiliser exclusivement les tokens DayNight créés par le plan rail : `paper` pour le fond panneau/dialogue, `elevated_surface` pour tuile/toolbar, `mist` pour pressed, `ink` pour titre/preview/action, `secondary_text` pour type/compteur, `divider` pour contours et `pen_blue` pour sélection/focus. `bg_clipboard_tile.xml` et `bg_clipboard_tile_pressed.xml` référencent ces tokens, jamais un hex ni `@android:color/white`; tous les vecteurs reçoivent un tint `ink` ou `secondary_text`. Les tests calculent ≥4,5:1 pour tout texte normal et ≥3:1 pour icônes/contours en clair et sombre. Le consentement, les menus et dialogues utilisent le thème DayNight de l’activité, pas un contexte clair forcé.

```kotlin
internal enum class KeyboardRootMode { KEYBOARD, CLIPBOARD, CLIPBOARD_SEARCH }
internal enum class KeyboardLayoutMode { NORMAL, SEARCH }

internal sealed interface ClipboardSearchAction {
    data class Character(val codePoint: Int) : ClipboardSearchAction
    data object Space : ClipboardSearchAction
    data object Delete : ClipboardSearchAction
    data object Close : ClipboardSearchAction
}

internal interface ClipboardSearchKeyboardContract {
    var layoutMode: KeyboardLayoutMode
    var onClipboardSearchAction: ((ClipboardSearchAction) -> Unit)?
}

internal class KeyboardRootView : FrameLayout {
    val keyboardLayer: LinearLayout
    val railView: KeyboardRailView
    val keyboardView: KeyboardView
    val clipboardPanel: ClipboardPanelView
    var mode: KeyboardRootMode = KeyboardRootMode.KEYBOARD
        private set

    fun showKeyboard()
    fun showClipboard()
    fun showClipboardSearch()
}
```

`KeyboardView` implémente `ClipboardSearchKeyboardContract`; sa propriété membre `layoutMode` invalide/recalcule la géométrie à chaque changement. Chaque méthode racine assigne d’abord `mode`, puis visibilité et `keyboardView.layoutMode`. `KEYBOARD` montre rail+clavier, masque panel et force `NORMAL`; `CLIPBOARD` masque ce layer, montre le panel plein cadre et force `NORMAL`; `CLIPBOARD_SEARCH` masque le rail, montre panel dans 44 % de la hauteur et `KeyboardView(layoutMode=SEARCH)` dans 56 %. En SEARCH, seules lettres, apostrophes, espace, delete et fermer sont dessinées et dispatchent `ClipboardSearchAction`; le host transforme ces actions en `panelController.setQuery` par code point. Aucun callback ne touche `InputConnection`. Revenir remet `onClipboardSearchAction=null`, rétablit `layoutMode=NORMAL` et les callbacks éditeur. Les tests parcourent les trois transitions dans les deux sens et vérifient `mode`, visibilités, proportions et absence de mutation éditeur.

- [ ] **Step 7: Ajouter consentement et activité de gestion**

Le state Disabled rend le texte exact : stockage local chiffré, aucune expiration, quotas, sensibles masqués mais collables, suppression possible ; sur API ≤28 ajouter l’avertissement d’exposition système historique. Le bouton `Activer` appelle controller.enable puis importe le clip courant. Déclarer `ClipboardHistoryActivity` avec `android:exported="false"`; elle ouvre le même contenu en **mode gestion** depuis les réglages, sans démarrer de listener si l’historique est désactivé. Ce mode possède son propre `EditText` de recherche (l’interdiction d’`EditText` vise seulement la fenêtre IME), réutilise `ClipboardPanelController` pour filtrer, et autorise épingler/désépingler, marquer sensible, supprimer et tout effacer. Il ne reçoit aucun `InputConnection` : l’appui sur une tuile ne colle rien et affiche le libellé sûr `Le collage se fait depuis le clavier`; l’action Coller est masquée/désactivée. Tester recherche Activity, rotation sans sauvegarder query/results plaintext, actions de gestion, et zéro appel `ClipboardPasteCoordinator`.

- [ ] **Step 8: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardPanelControllerTest' --tests '*ClipboardPanelViewTest' --tests '*ClipboardEntryAdapterTest' --tests '*KeyboardRootViewTest' --tests '*KeyboardViewTest' --no-daemon`

Expected: PASS ; aucune fuite sensible, recherche sans `EditText`, hauteur stable et thème système hérité.

```bash
git diff --check
git add app/src/main/AndroidManifest.xml app/src/main/java/ovh/jefe/keyboard/clipboard/ui app/src/main/java/ovh/jefe/keyboard/KeyboardRootView.kt app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt app/src/main/res/layout/clipboard_history_activity.xml app/src/main/res/drawable/bg_clipboard_tile.xml app/src/main/res/drawable/bg_clipboard_tile_pressed.xml app/src/main/res/drawable/ic_search.xml app/src/main/res/drawable/ic_more.xml app/src/main/res/drawable/ic_back.xml app/src/main/res/values/strings.xml app/src/test/java/ovh/jefe/keyboard/clipboard/ui app/src/test/java/ovh/jefe/keyboard/KeyboardRootViewTest.kt app/src/test/java/ovh/jefe/keyboard/KeyboardViewTest.kt
git commit -m "feat: browse encrypted clipboard history from the keyboard"
```

### Task 10: Proposition de collage typée et minuterie réellement visible

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPromptFormatter.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPromptController.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPromptFormatterTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPromptControllerTest.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt` (`railInputs`, prompt events and paste callback)
- Modify: `app/src/main/java/ovh/jefe/keyboard/KeyboardRailView.kt` (`preview` ellipsis only)
- Modify: `app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/KeyboardRailViewTest.kt`

**Interfaces:**
- Produces exact matrix `ClipboardPromptUi(entryId, preview, typeLabel, contentDescription, isSensitive)`.
- Countdown decrements only while keyboard visible and effective `TopRailState` is `ClipboardPrompt`.

- [ ] **Step 1: Écrire la matrice RED du formatter**

```kotlin
@Test
fun `formatter covers every approved type and never leaks sensitive content`() {
    assertPrompt(textTile("bonjour le monde"), "bonjour le monde", "Texte")
    assertPrompt(linkTile("https://example.com/path"), "https://example.com/path", "Lien")
    assertPrompt(htmlTile("bonjour"), "bonjour", "HTML")
    assertPrompt(imageTile("Screenshot_1.png"), "Capture d’écran", "Image")
    assertPrompt(audioTile(null), "Audio copié", "Audio")
    assertPrompt(videoTile("clip.mp4"), "clip.mp4", "Vidéo")
    assertPrompt(fileTile("rapport.pdf"), "rapport.pdf", "PDF")
    assertPrompt(groupTile(4), "4 éléments", "Groupe")
    assertPrompt(sensitiveTextTile("secret"), "Contenu sensible ••••••", "Texte")
}
```

Tester troncature par code points, contrôles/bidi neutralisés, contenuDescription ≤160 caractères et aucun nom/preview pour sensible.

- [ ] **Step 2: Écrire les tests RED du compteur**

```kotlin
@Test
fun `twenty seconds count only while prompt is actually visible`() = runTest {
    val clock = FakeElapsedClock(0)
    val prompt = ClipboardPromptController(clock, backgroundScope, testScheduler)
    prompt.show(promptUi("one"))
    prompt.setActuallyVisible(true)
    clock.advanceBy(8_000); advanceTimeBy(8_000); runCurrent()
    prompt.setActuallyVisible(false) // traduction prioritaire
    clock.advanceBy(30_000); advanceTimeBy(30_000); runCurrent()
    assertNotNull(prompt.current.value)
    prompt.setActuallyVisible(true)
    clock.advanceBy(12_001); advanceTimeBy(12_001); runCurrent()
    assertNull(prompt.current.value)
}
```

Ajouter : nouvelle copie remplace et remet 20 s, frappe/fermeture/paste/session dismiss, hideWindow met en pause, retour visible reprend, traduction Success/Error reste une pause, `close()` libère job.

- [ ] **Step 3: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardPromptFormatterTest' --tests '*ClipboardPromptControllerTest' --tests '*KeyboardRailViewTest' --no-daemon`

Expected: FAIL à la compilation sur formatter/controller et le prompt monolithique existant.

- [ ] **Step 4: Implémenter formatter et compteur monotone**

Le formatter force d’abord la preview sensible générique, mais conserve un type **générique sûr** recalculé depuis `ClipboardKind` (`Texte`, `Image`, `Vidéo`, `Audio`, `Fichier`, `Groupe`) — jamais le MIME ou nom fournisseur. Pour les autres, il produit séparément `preview` et `typeLabel`; seule `preview` est ellipsable. Le rail rend trois zones : libellé fixe `Coller`, preview `layout_weight=1` + `ellipsize=END`, type non réductible, puis fermer 48 dp. À 240 dp, le test affirme encore `Coller` et le type complet.

`ClipboardPromptController` conserve `remainingMillis`, `visibleSinceElapsed`, `current` et un unique `expiryJob`. `setActuallyVisible(true)` mémorise l’instant puis arme `scope.launch { delay(remainingMillis); expireIfStillVisibleAndDue() }`; false annule le job et soustrait une seule fois le temps visible. `show`, `dismiss`, changement de visibilité et `close` annulent toujours l’ancien job avant d’en créer un autre. L’expiration ne dépend donc d’aucun futur render ni d’un `tick()` de test. Toute nouvelle copie remplace l’id et remet exactement 20 000 ms ; le test utilise le scheduler coroutine et prouve l’expiration automatique. Le controller implémente aussi `ClipboardAdministrativeParticipant` : `revokeAccessAndJoin` est un no-op, `clearMemoryCaches` appelle `dismiss()` et libère toute preview. Le service enregistre ce participant dans `ClipboardComponent.administrativeRuntime` à sa création et ferme le handle avant `promptController.close()` à sa destruction ; clear/disable/reset ne peuvent donc conserver un aperçu ancien.

Le service construit un prompt non sensible avec `payloadReader.readVerifiedPreviewPrefix` (ou la `displayName` bornée du manifest authentifié), ferme le buffer/entrée immédiatement après formatage et n’utilise jamais les coordonnées du conteneur directement. Pour un sensible, il ne lit aucun payload et force la chaîne générique. Ajouter un test TEXT >64 KiB stocké en Blob dont le début est proposé correctement, et un tag invalide qui ne révèle rien et n’affiche aucun prompt.

- [ ] **Step 5: Intégrer avec la priorité du rail et le collage**

À chaque render, résoudre l’état puis appeler `promptController.setActuallyVisible(keyboardVisible && root.mode == KeyboardRootMode.KEYBOARD && root.railView.isAttachedToWindow && root.railView.isShown && state is TopRailState.ClipboardPrompt)`. Le test ouvre successivement le panneau et la recherche pendant 30 s et prouve que le compteur ne baisse pas, même si la fenêtre IME reste visible. Le service collecte `ClipboardControllerEvent.EntryStored`, émis par Task 7 après `StoreResult.Inserted` **ou** `StoreResult.Duplicate`, puis charge la summary READY correspondante pour construire le prompt ; aucun événement repository inexistant n’est utilisé. Le tap appelle `ClipboardPasteCoordinator`; Success dismiss et enregistre `NON_SENSITIVE_PASTE`, ou invalide le gate si sensible. Toute frappe locale, fermeture, changement de session et suppression de l’entrée dismiss. Une traduction ou un mode panneau masque sans supprimer.

- [ ] **Step 6: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardPromptFormatterTest' --tests '*ClipboardPromptControllerTest' --tests '*KeyboardRailViewTest' --tests '*JefeKeyboardServiceTest' --no-daemon`

Expected: PASS ; type toujours visible, 20 secondes réellement affichées et priorité traduction conservée.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPromptFormatter.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPromptController.kt app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt app/src/main/java/ovh/jefe/keyboard/KeyboardRailView.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPromptFormatterTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardPromptControllerTest.kt app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt app/src/test/java/ovh/jefe/keyboard/KeyboardRailViewTest.kt
git commit -m "feat: offer clipboard paste with visible-time feedback"
```

### Task 11: Provenance sensible, blocage réseau et promotion irréversible

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/SensitiveClipboardGuard.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/SensitiveClipboardGuardTest.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/clipboard/RoomClipboardHistoryRepository.kt` (`markSensitive`, exact-text HMAC)
- Modify: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPasteCoordinator.kt` (`ClipboardPasteResult.Success.isSensitive`)
- Modify: `app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt` (`translation preflight and session taint`)
- Modify: `app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt`
- Modify: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/RoomClipboardHistoryRepositoryInstrumentedTest.kt`

**Interfaces:**
- Produces: `SensitiveClipboardGuard.startSession/recordSensitivePaste/canTranslate`.
- Exact-text check uses opaque HMAC rows and fails closed if repository/key is unavailable.
- Mark sensitive is irreversible and purges every preview/thumbnail/cache before UI publication.

- [ ] **Step 1: Écrire les tests RED du garde session**

```kotlin
@Test
fun `sensitive paste blocks translation until next valid input session`() = runTest {
    val repository = FakeSensitiveMatcher(matches = false)
    val guard = SensitiveClipboardGuard(repository)
    guard.startSession(10)
    assertTrue(guard.canTranslate(10, "bonjour") is SensitiveTranslationDecision.Allowed)
    guard.recordSensitivePaste(10)
    assertTrue(guard.canTranslate(10, "autre texte") is SensitiveTranslationDecision.Blocked)
    guard.startSession(11)
    assertTrue(guard.canTranslate(11, "autre texte") is SensitiveTranslationDecision.Allowed)
}

@Test
fun `exact sensitive text is blocked after restart without plaintext scan`() = runTest {
    val repository = FakeSensitiveMatcher(matches = true)
    val guard = SensitiveClipboardGuard(repository)
    guard.startSession(20)
    assertTrue(guard.canTranslate(20, "1234") is SensitiveTranslationDecision.Blocked)
    assertEquals(listOf("1234"), repository.queries)
}
```

Ajouter session obsolète, texte modifié autorisé honnêtement, repository indisponible → blocage générique et champ privé → blocage avant repository.

Ajouter `paste sensible -> caractère local accepté -> toujours zéro suggestion` et réouverture de vue toujours zéro ; seul un nouvel `onStartInput` valide retire le taint. Le collage non sensible conserve au contraire `NON_SENSITIVE_PASTE` et les suggestions approuvées.

- [ ] **Step 2: Écrire les tests RED de promotion**

Créer une entrée claire avec thumbnail + preview déchiffré en cache, appeler `markSensitive`, puis affirmer la séquence Task 6 : `EntryPromoting` masque immédiatement cache/recherche/prompt, copy-on-write produit un nouveau conteneur/manifest sans thumbnail, revoke est attendu, puis `EntryReady` publie `isSensitive=true` avec tous les hashes exacts. Une seconde copie claire identique ne doit jamais la rétrograder. Injecter un crash à chaque frontière de cette promotion et reprendre sans réécriture en place.

- [ ] **Step 3: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*SensitiveClipboardGuardTest' --tests '*JefeKeyboardServiceTest' --no-daemon`

Run on device: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.RoomClipboardHistoryRepositoryInstrumentedTest --no-daemon`

Expected: FAIL sur le garde et les comportements de promotion/session.

- [ ] **Step 4: Implémenter le HMAC exact séparé**

Lors de l’ingestion de chaque représentation texte sensible, calculer `HMAC("jefe-sensitive-text-v1" || uint64(length) || exactUtf8)` avec la clé HMAC existante et enregistrer `(entryId, itemIndex, representation, hash)` ; `representation` vaut `text`, `html` ou `fallback`, jamais une valeur utilisateur. Pour une sélection, calculer un seul HMAC puis utiliser la requête BLOB indexée ; ne jamais déchiffrer les 500 entrées. Si une entrée claire est marquée sensible, Task 11 calcule seulement tous les HMAC à partir du manifest/payload reader authentifiés, puis les remet au protocole copy-on-write `PROMOTING -> EntryPromoting -> revoke/join -> ancien conteneur supprimé -> READY` défini Task 6. Il est interdit de supprimer la thumbnail, modifier le manifest ou passer `isSensitive=true` directement/en place dans cette tâche.

- [ ] **Step 5: Implémenter le garde conservateur**

```kotlin
internal sealed interface SensitiveTranslationDecision {
    data object Allowed : SensitiveTranslationDecision
    data class Blocked(val safeMessage: String) : SensitiveTranslationDecision
}

internal class SensitiveClipboardGuard(
    private val repository: ClipboardHistoryRepository,
) {
    private var sessionId = Long.MIN_VALUE
    private var tainted = false

    fun startSession(id: Long) { sessionId = id; tainted = false }
    fun recordSensitivePaste(id: Long) { if (id == sessionId) tainted = true }
    suspend fun canTranslate(id: Long, selectedText: String): SensitiveTranslationDecision
}
```

`canTranslate` bloque session différente, session tainted, puis branche exhaustivement sur `containsSensitiveExactText`: `Match` bloque, `NoMatch` autorise et `Unavailable` bloque avec `Impossible de vérifier la confidentialité de cette sélection`. Une erreur de clé/base ne peut donc jamais être confondue avec une absence de correspondance. Un texte partiellement modifié après nouvelle session peut ne plus être reconnu ; cette limite reste documentée et aucune heuristique de mot de passe n’est ajoutée.

- [ ] **Step 6: Brancher avant tout appel distant**

`translateSelection()` évalue d’abord `EditorPrivacyPolicy`, capture la sélection, puis appelle le garde avant `launchTranslation`. Aucun compteur fake de `TranslateClient` ne bouge si bloqué. Un `ClipboardPasteResult.Success(isSensitive=true)` appelle `recordSensitivePaste`, `suggestionGate.taintForSession()` et masque prompt ; toute frappe ultérieure reste inéligible jusqu’au prochain `onStartInput`. Non sensible enregistre `NON_SENSITIVE_PASTE`.

- [ ] **Step 7: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*SensitiveClipboardGuardTest' --tests '*JefeKeyboardServiceTest' --no-daemon`

Run on device: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.RoomClipboardHistoryRepositoryInstrumentedTest --no-daemon`

Expected: PASS ; sensible toujours collable volontairement, mais jamais envoyé à Translate dans les garanties définies.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/SensitiveClipboardGuard.kt app/src/main/java/ovh/jefe/keyboard/clipboard/RoomClipboardHistoryRepository.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardPasteCoordinator.kt app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt app/src/test/java/ovh/jefe/keyboard/clipboard/SensitiveClipboardGuardTest.kt app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/RoomClipboardHistoryRepositoryInstrumentedTest.kt
git commit -m "feat: keep sensitive clipboard text off remote services"
```

### Task 12: Réglages, purge totale et exclusions de sauvegarde

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardSettingsController.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryController.kt` (durable clear/disable/reset order)
- Modify: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardAdministrativeRuntime.kt` (participants and key reset)
- Modify: `app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardComponent.kt` (wire administrative dependencies)
- Modify: `app/src/main/java/ovh/jefe/keyboard/SettingsActivity.kt` (`SettingsFragment` clipboard preferences)
- Modify: `app/src/main/res/xml/preferences.xml` (`Presse-papiers` category)
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `README.md` (privacy, limits and process lifecycle)
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardSettingsControllerTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/SettingsActivityTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/SettingsPrivacyTest.kt`

**Interfaces:**
- Produces settings state/status with no user content.
- `clearAll` keeps activation et pose le marqueur anti-réimport ; `disableAndErase` persiste `DISABLING`, stops capture/revokes grants, deletes DB/containers/thumbnails/caches/keys, puis seulement persiste `DISABLED`.
- `resetProtectedHistory` n’apparaît que pour `KEY_UNAVAILABLE`; aucune donnée n’est effacée sans confirmation explicite.

- [ ] **Step 1: Écrire les tests RED des préférences**

Affirmer présence et texte exact de : état activé/désactivé, `Sans expiration · 500 éléments · 250 Mo`, `Ouvrir l’historique`, stats nombre/taille, `Tout effacer`, `Désactiver et effacer`. Quand l’état est `KEY_UNAVAILABLE`, afficher aussi `Réinitialiser l’historique protégé`; vérifier qu’il est absent sinon. Tester l’ouverture de l’activité non exportée, consentement avant activation, avertissement API 24–28, confirmations mentionnant les épinglés et absence de tout aperçu dans les résumés. Pour le reset : refus du dialogue = zéro mutation ; confirmation = `RESETTING_ENABLED`, capture/persistance du marqueur source avant purge/recréation des clés, retour `ENABLED`, historique vide et primary clip courant supprimé logiquement après redémarrage jusqu’à `DEFINITELY_CHANGED` selon le contrat Task 7.

- [ ] **Step 2: Écrire le test RED de purge ordonnée**

```kotlin
@Test
fun `disable stops capture and grants before deleting every protected artifact`() = runTest {
    val order = mutableListOf<String>()
    val controller = settingsController(order)
    controller.disableAndErase(confirmed = true)
    assertEquals(
        listOf(
            "activation-disabling",
            "listener-stop",
            "queue-cancel-and-join",
            "grants-revoke-and-join",
            "cache-clear",
            "database-clear",
            "files-clear",
            "keys-delete",
            "activation-disabled",
        ),
        order,
    )
}
```

Injecter un crash après **chaque** élément de cet ordre, puis recréer le composant : `DISABLING` reprend sans listener/import, répète les nettoyages idempotents et finit `DISABLED`. Ajouter redémarrage vide, aucune capture après disable, grant actif refusé, `.part`/thumbnail supprimés et annulation utilisateur sans mutation. Pour clear-all activé, bloquer une ingestion A, mettre B en file, confirmer le clear puis vérifier que `ClipboardHistoryController.clearAllAndResume()` écrit `CLEARING_ENABLED`, retire le listener, annule/joint A+B, capture et persiste le marqueur source avant purge, redémarre une FIFO vide, écrit `ENABLED` en dernier, ne réimporte pas l’ancien primary clip même après mort/recréation du processus, puis capture C seulement sur un callback `DEFINITELY_CHANGED`. Vérifier séparément timestamp API 31+ égal (collision : rester supprimé), différent (capturer C), nul (rester supprimé) et API 24–30 (callback legacy : capturer C). Répéter ce test avec crash injecté à chaque frontière du clear.

- [ ] **Step 3: Étendre le RED des règles backup**

`SettingsPrivacyTest` parse les ressources fusionnées et exige `sharedpref`, `database` et `file` dans legacy, cloud-backup et device-transfer, en plus de `allowBackup=false` dans le manifest packagé.

- [ ] **Step 4: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardSettingsControllerTest' --tests '*SettingsActivityTest' --tests '*SettingsPrivacyTest' --no-daemon`

Expected: FAIL sur catégorie/actions/règles et purge.

- [ ] **Step 5: Ajouter la catégorie et le contrôleur**

```xml
<PreferenceCategory app:key="clipboard_category" app:title="@string/clipboard_title">
    <SwitchPreferenceCompat app:key="clipboard_enabled" app:title="@string/clipboard_enabled_title" />
    <Preference app:key="clipboard_summary" app:title="@string/clipboard_limits_title" app:summary="@string/clipboard_limits_summary" app:selectable="false" />
    <Preference app:key="clipboard_open_history" app:title="@string/clipboard_open_history" />
    <Preference app:key="clipboard_stats" app:title="@string/clipboard_stats" app:selectable="false" />
    <Preference app:key="clipboard_clear_all" app:title="@string/clipboard_clear_all" />
    <Preference app:key="clipboard_disable_and_clear" app:title="@string/clipboard_disable_and_clear" />
    <Preference app:key="clipboard_reset_protected_history" app:title="@string/clipboard_reset_protected_history" app:visible="false" />
</PreferenceCategory>
```

Le switch retourne `false` à `onPreferenceChange` jusqu’à réponse au dialogue ; après confirmation il appelle enable/import puis met l’état. Stats utilisent uniquement metadata count/bytes formatés. `Tout effacer` inclut explicitement les épinglés et appelle la barrière `clearAllAndResume()` du controller, jamais directement `repository.clearAll()`, afin qu’aucune ingestion acceptée avant la confirmation ne puisse réapparaître après. `Désactiver et effacer` appelle exclusivement `controller.disableAndPurge()` et suit l’ordre durable testé. `Réinitialiser l’historique protégé` n’est visible que si le state expose `KEY_UNAVAILABLE`; son dialogue explique que l’ancien historique chiffré sera irrécupérable, puis appelle exclusivement `controller.resetProtectedHistory()` après confirmation. Cette opération écrit `RESETTING_ENABLED`, capture et persiste le marqueur source avant toute suppression, reprend après crash à chaque frontière, recrée AES+HMAC, écrit `ENABLED` en dernier et garde ce marqueur jusqu’à `DEFINITELY_CHANGED`.

- [ ] **Step 6: Écrire les exclusions exactes**

```xml
<!-- res/xml/backup_rules.xml -->
<full-backup-content>
    <exclude domain="sharedpref" path="." />
    <exclude domain="database" path="." />
    <exclude domain="file" path="." />
</full-backup-content>
```

```xml
<!-- res/xml/data_extraction_rules.xml -->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="sharedpref" path="." />
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
    </cloud-backup>
    <device-transfer>
        <exclude domain="sharedpref" path="." />
        <exclude domain="database" path="." />
        <exclude domain="file" path="." />
    </device-transfer>
</data-extraction-rules>
```

Le blob store utilise en plus `noBackupFilesDir/clipboard/v1`. Le README explique opt-in, chiffrement au repos, sensible masqué/collable, limites Android 7–9, copies manquées lorsque le processus est absent et limite honnête de provenance après modification.

- [ ] **Step 7: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardSettingsControllerTest' --tests '*SettingsActivityTest' --tests '*SettingsPrivacyTest' --no-daemon`

Expected: PASS ; aucune chaîne utilisateur dans les préférences, toutes les exclusions présentes.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardSettingsController.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardHistoryController.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardAdministrativeRuntime.kt app/src/main/java/ovh/jefe/keyboard/clipboard/ClipboardComponent.kt app/src/main/java/ovh/jefe/keyboard/SettingsActivity.kt app/src/main/res/xml/preferences.xml app/src/main/res/xml/backup_rules.xml app/src/main/res/xml/data_extraction_rules.xml app/src/main/res/values/strings.xml README.md app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardSettingsControllerTest.kt app/src/test/java/ovh/jefe/keyboard/SettingsActivityTest.kt app/src/test/java/ovh/jefe/keyboard/SettingsPrivacyTest.kt
git commit -m "feat: manage and erase encrypted clipboard history"
```

### Task 13: Tests API 24/34, captures clair/sombre et gate final

**Files:**
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardLifecycleInstrumentedTest.kt`
- Create: `app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardPrivacyInstrumentedTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardArchitectureTest.kt`
- Create: `.github/scripts/run-clipboard-process-death-test.sh`
- Modify: `.github/workflows/build.yml` (instrumented matrix API 24/34)
- Modify: `app/src/test/java/ovh/jefe/keyboard/KeyboardRootViewTest.kt` (visual outputs)
- Modify: `app/src/test/java/ovh/jefe/keyboard/SettingsActivityTest.kt` (light/dark outputs)

**Interfaces:**
- Final evidence: unit + Room/Keystore/provider lifecycle on API 24 and 34, lint 0 errors, assembled/signature-verified APK, packaged privacy rules and inspected native renders.

- [ ] **Step 1: Écrire les tests instrumentés RED de bout en bout**

`ClipboardLifecycleInstrumentedTest` active, importe texte/HTML/image, redémarre le controller, vérifie READY, colle, change session, révoque grant, désactive et vérifie listener/base/files/keys vides. `ClipboardPrivacyInstrumentedTest` inspecte les fichiers/base bruts pour absence du texte sentinelle, vérifie sensible masqué mais collé exact, utilise le helper APK à UID distinct pour mauvais UID/MIME/session, et exige aucune requête réseau via compteurs clients à zéro.

Les scénarios de vraie mort de processus sont deux méthodes indépendantes pilotées par l’hôte : `setupClearOrResetRestart` prépare ancien clip + opération interrompue et persiste le résultat attendu sans assertion post-stop ; l’hôte force-stop le package ; `verifyClearOrResetRestart` relance le composant, affirme reprise de `CLEARING_ENABLED/RESETTING_ENABLED`, ancien primary absent, puis provoque une nouvelle copie C et l’attend READY. Chaque méthode lit les arguments instrumentation `phase` et `scenario`; sans la combinaison attendue elle appelle `Assume.assumeTrue` et est SKIPPED dans un `connectedDebugAndroidTest` général. Une instrumentation ne tente jamais de continuer après avoir force-stop son propre processus.

Créer **dans cette Step 1**, avant le premier run, `.github/scripts/run-clipboard-process-death-test.sh`. Le script assemble/installe une fois app + androidTest, puis boucle `scenario in clear reset` avec trois commandes explicites par scénario : setup (`-e phase setup -e scenario "$scenario"`), `adb shell am force-stop ovh.jefe.keyboard`, verify (`-e phase verify -e scenario "$scenario"`). Il n’installe rien entre setup et verify, vérifie chaque code retour et n’imprime aucun contenu. Les étapes suivantes l’invoquent toujours avec `bash`, donc aucun bit exécutable implicite n’est requis.

- [ ] **Step 2: Ajouter le test d’architecture RED**

Lire les sources clipboard et échouer si elles importent `TranslateClient`, `WhisperClient`, `android.util.Log`, `java.net`, `okhttp3`, `java.nio.file` ou écrivent dans `cacheDir/filesDir`; vérifier que seul `EncryptedClipboardBlobStore` construit le chemin `noBackupFilesDir/clipboard/v1`, que les autres composants reçoivent ce store par injection et que le manifest provider reste non exporté. Instancier tous les modèles pouvant porter label/texte/HTML/URI/preview/query/suggestion avec une sentinelle et exiger que ni leur `toString()` ni les états englobants ne la contiennent. Interdire aussi `data class SystemClip`, `data class ClipboardManifest`, `data class ClipboardPromptUi`, `data class ClipboardTileUi` et `java.util.Base64` par scan source.

- [ ] **Step 3: Vérifier le RED ciblé**

Run: `./gradlew testDebugUnitTest --tests '*ClipboardArchitectureTest' --no-daemon`

Run on device: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=ovh.jefe.keyboard.clipboard.ClipboardLifecycleInstrumentedTest,ovh.jefe.keyboard.clipboard.ClipboardPrivacyInstrumentedTest --no-daemon`

Run process-death phases: `bash .github/scripts/run-clipboard-process-death-test.sh`

Expected: les nouveaux scénarios exposent toute intégration manquante avant la correction finale.

- [ ] **Step 4: Ajouter la matrice CI sans action émulateur tierce**

```yaml
  instrumented:
    runs-on: ubuntu-latest
    timeout-minutes: 50
    strategy:
      fail-fast: false
      matrix:
        api: [24, 34]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - name: Enable KVM
        run: |
          echo 'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules
          sudo udevadm control --reload-rules
          sudo udevadm trigger --name-match=kvm
      - name: Install and create emulator
        run: |
          sdkmanager "platform-tools" "emulator" "platforms;android-${{ matrix.api }}" "system-images;android-${{ matrix.api }};google_apis;x86_64"
          echo no | avdmanager create avd --force --name jefe_api_${{ matrix.api }} --package "system-images;android-${{ matrix.api }};google_apis;x86_64"
      - name: Start emulator
        run: |
          nohup "$ANDROID_HOME/emulator/emulator" -avd jefe_api_${{ matrix.api }} -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect > /tmp/jefe-emulator.log 2>&1 &
          adb wait-for-device
          until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ]; do sleep 2; done
          adb shell settings put global window_animation_scale 0
          adb shell settings put global transition_animation_scale 0
          adb shell settings put global animator_duration_scale 0
      - name: Run connected tests
        run: |
          ./gradlew :clipboard-test-client:assembleDebug :app:assembleDebug --no-daemon
          adb install -r clipboard-test-client/build/outputs/apk/debug/clipboard-test-client-debug.apk
          ./gradlew connectedDebugAndroidTest --no-daemon
          bash .github/scripts/run-clipboard-process-death-test.sh
```

Le script, déjà écrit en Step 1, assemble une fois `:app:assembleDebug :app:assembleDebugAndroidTest`, installe avec `adb install -r` l’APK app et l’APK androidTest **sans réinstallation entre les phases**, puis exécute pour chacun de `clear` et `reset` :

```bash
adb shell am instrument -w -e phase setup -e scenario clear -e class 'ovh.jefe.keyboard.clipboard.ClipboardLifecycleInstrumentedTest#setupClearOrResetRestart' ovh.jefe.keyboard.test/androidx.test.runner.AndroidJUnitRunner
adb shell am force-stop ovh.jefe.keyboard
adb shell am instrument -w -e phase verify -e scenario clear -e class 'ovh.jefe.keyboard.clipboard.ClipboardLifecycleInstrumentedTest#verifyClearOrResetRestart' ovh.jefe.keyboard.test/androidx.test.runner.AndroidJUnitRunner
```

Il répète exactement ces deux phases avec `scenario reset`; les méthodes setup/verify branchent exhaustivement sur cet argument, et toute valeur inconnue échoue. Le test général sans arguments ne déclenche aucune des deux phases destructrices.

- [ ] **Step 5: Générer les captures système clair/sombre**

Run:

```bash
VISUAL_OUTPUT_DIR=/tmp/jefe-keyboard-clipboard ./gradlew testDebugUnitTest --tests '*KeyboardRootViewTest' --tests '*SettingsActivityTest' --no-daemon
```

Vérifier fichiers non vides puis inspecter : keyboard empty/suggestions/prompt/translation light+dark, clipboard disabled/empty/content/group/search light+dark, settings light+dark. Rejeter fuite sensible, fond blanc en sombre, type tronqué, cible <44 dp ou contraste <4,5:1.

- [ ] **Step 6: Exécuter le gate propre complet**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug :clipboard-test-client:assembleDebug --no-daemon`

Run on API 24: `./gradlew connectedDebugAndroidTest --no-daemon`

Run on API 34: `./gradlew connectedDebugAndroidTest --no-daemon`

Expected: tous tests verts, lint 0 erreur, APK debug non vide.

- [ ] **Step 7: Inspecter l’artefact packagé**

```bash
apkanalyzer manifest print app/build/outputs/apk/debug/app-debug.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Confirmer package `ovh.jefe.keyboard`, min 24/target 34, service IME, provider non exporté + grants temporaires, activité historique non exportée, `allowBackup=false`, règles backup packagées et signature v2 valide.

- [ ] **Step 8: Vérifier la séparation réseau et committer**

```bash
rg -n "TranslateClient|WhisperClient|okhttp3|java\.net|android\.util\.Log" app/src/main/java/ovh/jefe/keyboard/clipboard
git diff --check
git status --short
git add .github/workflows/build.yml .github/scripts/run-clipboard-process-death-test.sh app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardLifecycleInstrumentedTest.kt app/src/androidTest/java/ovh/jefe/keyboard/clipboard/ClipboardPrivacyInstrumentedTest.kt app/src/test/java/ovh/jefe/keyboard/clipboard/ClipboardArchitectureTest.kt app/src/test/java/ovh/jefe/keyboard/KeyboardRootViewTest.kt app/src/test/java/ovh/jefe/keyboard/SettingsActivityTest.kt
git commit -m "test: verify clipboard privacy on supported android versions"
```

Expected for `rg`: no match, exit 1. Après commit, `git status --short` est vide et `git log -13 --oneline` montre une tranche par tâche.

- [ ] **Step 9: Livrer sans force sur la branche principale**

Invoquer `superpowers:finishing-a-development-branch`. Puis récupérer l’état distant et vérifier que le travail contient bien `origin/main` :

```bash
git fetch origin main
git merge-base --is-ancestor origin/main HEAD
```

Si la seconde commande échoue parce que `main` a avancé, fusionner `origin/main` dans `codex/keyboard-clipboard` sans réécrire l’historique, résoudre seulement les conflits de cette fonctionnalité, puis relancer intégralement Step 6 et Step 7. Quand le résultat est propre et toujours vert :

```bash
git push origin HEAD:main
test "$(git rev-parse HEAD)" = "$(git ls-remote origin refs/heads/main | cut -f1)"
```

Expected: push fast-forward accepté, aucun `--force`, et le SHA distant de `main` égale exactement le commit vérifié. Ne modifier ni nettoyer le worktree principal partagé pour réaliser cette livraison.

## Final Acceptance Checklist

- [ ] Rail vide sans capsule ; suggestions seulement après mutation locale acceptée.
- [ ] Traduction persistante, verrouillée, annulable et privée.
- [ ] Historique désactivé par défaut, consentement explicite et aucune notification permanente.
- [ ] Tous types prévus ingérés en FIFO ou rejetés par erreur sûre.
- [ ] AES-GCM/HMAC Keystore distincts ; aucun plaintext durable, backup, log ou réseau.
- [ ] Sensible masqué/recherche exclue, mais collage volontaire exact.
- [ ] Déduplication monotone, épingles et quotas exacts, aucune expiration temporelle.
- [ ] Provider lié UID/MIME/session, trois ouvertures/60 s, API 24 manuel et API 34 flag.
- [ ] Troisième ouverture terminée, quatrième refusée ; revoke ne peut manquer aucun pipe tardif.
- [ ] Groupes textuels en une seule mutation directe ou provider selon la taille ; groupes riches item par item sans fausse atomicité.
- [ ] Prompt typé 20 secondes réellement visibles, pause sous traduction.
- [ ] Panneau accessible, recherche interne, clair/sombre système et 44 dp.
- [ ] Désactivation retire listener/grants avant purge totale et redémarre vide.
- [ ] Clear/reset ne réimportent jamais le clip courant après redémarrage ; seule une nouvelle copie réactive la capture.
- [ ] Tests JVM, API 24, API 34, lint, assemble, APK, manifest, backup, signature et captures validés.
- [ ] Le commit entièrement vérifié est publié en fast-forward sur `origin/main`, sans force.
