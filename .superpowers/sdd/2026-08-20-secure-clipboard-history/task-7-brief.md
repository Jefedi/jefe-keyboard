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

`clearAllAndResume()` écrit d’abord `CLEARING_ENABLED`, retire le listener, ferme/annule le worker et le joint, capture le primary clip courant puis persiste son `ClipboardSourceMarker` avant d’appeler `repository.clearAll()`; seulement après la purge il écrit `ENABLED`, recrée queue/permits/worker et rattache le listener, sans réimporter le primary clip ancien. Si la capture ne fournit pas de timestamp API 31+, il persiste `TimestampUnavailable` et reste fail-closed. Le marqueur survit au redémarrage et seul `DEFINITELY_CHANGED` dans un callback listener le retire. `disableAndPurge` suit la même barrière puis purge et désactive, mais permet un futur `enable` qui recrée queue+worker. Les générations ne filtrent que `state/events`, jamais le consumer.

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
