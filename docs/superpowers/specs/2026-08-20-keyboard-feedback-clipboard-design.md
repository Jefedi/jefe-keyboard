# Jefe Keyboard — rail intelligent et historique du presse-papiers

Date : 2026-08-20

Statut : conception simplifiée validée par l’utilisateur le 2026-08-21 ; prête pour mise à jour du plan

Branche de conception : `codex/keyboard-clipboard`

## 1. Résumé

Cette évolution poursuit trois objectifs liés :

1. corriger le rail de suggestions afin qu’aucune capsule vide ne soit dessinée ;
2. rendre la traduction visiblement active pendant toute la requête ;
3. ajouter un historique local dans le stockage privé Android, accessible depuis un onglet permanent du rail supérieur.

L’identité visuelle retenue est **Bleu d’encre**. Elle existe en clair et en sombre et suit automatiquement le thème Android. Le rail supérieur devient une surface d’état stable, plate et non une rangée de trois capsules permanentes.

L’historique est activé explicitement au premier usage. Il conserve sans expiration temporelle jusqu’à 500 entrées non épinglées et 250 Mo de contenu non épinglé. Chaque entrée, groupe compris, est limitée à 25 Mo. Les éléments épinglés ne sont soumis ni à la limite de nombre ni au quota cumulé de 250 Mo, mais restent soumis à la limite individuelle de 25 Mo ; ils ne sont jamais supprimés automatiquement.

Les contenus signalés sensibles ne sont pas rejetés : ils sont conservés dans le stockage privé, masqués dans l’interface et collés en clair seulement après un appui volontaire. Ils sont exclus de la recherche, des suggestions et de toute opération réseau automatique.

## 2. Contexte et causes racines

### 2.1 Capsules de suggestions vides

`KeyboardView.computeLayout()` réserve toujours trois emplacements et `onDraw()` peint toujours le fond et le contour de ces trois emplacements, même lorsque `suggestions` est vide. Le service efface correctement la liste, mais la vue dessine malgré tout trois capsules sans texte. `renderedSuggestions()` expose également trois contrôles vides, et un test existant encode involontairement ce comportement.

La correction doit préserver la hauteur du rail pour éviter un saut vertical du clavier, tout en dessinant, exposant à l’accessibilité et rendant interactifs uniquement les éléments réellement présents.

### 2.2 Retour de traduction insuffisant

Le seul retour actuel pendant une traduction est un toast court. La requête peut durer plus longtemps que le toast, l’icône ne change pas et plusieurs appuis peuvent lancer plusieurs traductions concurrentes. Le service possède déjà des gardes de session et de sélection solides, mais aucun état persistant n’est présenté dans `KeyboardView`.

### 2.3 Absence de presse-papiers interne

L’application n’utilise actuellement ni `ClipboardManager`, ni base de données, ni stockage durable autre que les préférences. Un nouvel historique est donc un sous-système et non une simple extension de la liste de suggestions.

## 3. Références open source

La conception reprend les éléments éprouvés suivants sans copier leurs faiblesses :

- **HeliBoard** : écoute du presse-papiers, historique SQLite, déduplication, épinglage, suppression par geste et option de durée illimitée. Son implémentation confirme qu’un stockage privé Android simple est viable, mais Jefe Keyboard applique une politique plus stricte de masquage sensible et d’exclusion des sauvegardes. [ClipboardHistoryManager](https://github.com/Helium314/HeliBoard/blob/50d13c1bd6c3f4ee6d69644b3d422145cb928503/app/src/main/java/helium314/keyboard/latin/ClipboardHistoryManager.kt)
- **FlorisBoard** : séparation entre gestionnaire, base Room, flux d’état et interface ; sections épinglées/récentes ; filtrage et nettoyage transactionnel. Cette séparation sert de modèle architectural. [ClipboardManager](https://github.com/florisboard/florisboard/blob/2a44855c7fcce943a2d3b2092fe45808037ad258/app/src/main/kotlin/dev/patrickgold/florisboard/ime/clipboard/ClipboardManager.kt)
- **FUTO Keyboard** : recherche, mosaïque/colonne, quotas de fichiers, copie privée des médias et nettoyage des orphelins. Jefe Keyboard reprend les bornes et le nettoyage, mais utilise Room plutôt qu’un fichier JSON et interdit toute journalisation du contenu. [ClipboardHistoryAction](https://github.com/futo-org/android-keyboard/blob/eaf0389f962b0dba07778d0feab6511e6e98c581/java/src/org/futo/inputmethod/latin/uix/actions/clipboard/ClipboardHistoryAction.kt)
- **AnySoftKeyboard** : historique opt-in et retrait du listener lors de la désactivation, mais sa liste RAM de 15 textes est insuffisante pour le besoin durable. [ClipboardV11](https://github.com/AnySoftKeyboard/AnySoftKeyboard/blob/6643bda9d400c0ca3025e67ca46361e28ba5e441/ime/app/src/main/java/com/anysoftkeyboard/devicespecific/ClipboardV11.java)
- **AOSP LatinIME** : référence pour le cycle asynchrone des suggestions, mais ne fournit pas d’historique du presse-papiers.

Android recommande Room pour les données structurées et le stockage interne app-private pour les données propres à l’application. Jefe Keyboard suit ce modèle sans ajouter de couche cryptographique applicative. [Room](https://developer.android.com/training/data-storage/room), [stockage interne](https://developer.android.com/training/data-storage/app-specific#internal)

## 4. Décisions produit validées

### 4.1 Activation et capture

- L’onglet presse-papiers est visible même lorsque l’historique est désactivé.
- Au premier appui, une explication courte décrit le stockage local privé, l’absence d’expiration temporelle, les quotas, la conservation des contenus sensibles masqués et la possibilité de tout supprimer.
- L’utilisateur active ensuite explicitement l’historique.
- Juste après cette activation, le clip système courant est importé une fois s’il respecte la policy ; aucune capture antérieure n’est reconstruite.
- L’écoute utilise le cycle de vie normal de l’IME, sans service permanent et sans notification persistante.
- Si Android a arrêté le processus, le presse-papiers système courant est relu au prochain démarrage, sauf après `Tout effacer` : dans ce cas le clip courant observé avant la purge reste supprimé logiquement jusqu’au prochain changement prouvé du presse-papiers. Les copies intermédiaires éventuellement survenues pendant l’arrêt ne peuvent pas être récupérées. Le contrôleur persiste une observation source typée, jamais un booléen d’identité : `NoPrimaryClip` n’active aucune suppression, tandis que `Observed(marker)` devient `Suppressed(marker)`. Sur API 31+, seul un callback dont le timestamp source non nul est différent peut lever cette suppression ; timestamp identique (collision possible) ou indisponible reste supprimé. Sur API 24–30, où les callbacks de classification n’existent pas, le prochain callback listener est la preuve legacy documentée. Le démarrage ne lève jamais ce marqueur.
- Tant que le processus vit, chaque changement est placé dans une file FIFO d’ingestion ; une copie lente n’est pas abandonnée parce qu’une nouvelle arrive.
- Désactiver l’historique demande confirmation puis efface la base, les fichiers, les miniatures, les autorisations temporaires et les caches mémoire.
- Sur Android 7 à 9, ce consentement avertit aussi que le système permet encore à d’autres applications en arrière-plan de lire le presse-papiers avant sa copie dans l’historique privé.

Android 10 et versions ultérieures autorisent le clavier par défaut à lire le presse-papiers même lorsqu’une application ordinaire sans focus ne le peut pas. Android reste toutefois libre d’arrêter un processus pour récupérer de la mémoire. [Accès Android 10](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data), [cycle de vie des processus](https://developer.android.com/guide/components/activities/process-lifecycle)

### 4.2 Contenus couverts

Une entrée d’historique peut préserver :

- texte brut ;
- texte HTML accompagné de son repli texte brut ;
- liens ;
- images et captures d’écran ;
- vidéos et audio ;
- documents et autres fichiers accessibles par `content://` ;
- plusieurs éléments compatibles appartenant au même `ClipData`, conservés comme un groupe ordonné.

Un `Intent` exécutable n’est pas un contenu collable dans un éditeur et n’est pas persisté. Une URI inaccessible, expirée ou refusée par son fournisseur reste dans le presse-papiers système, mais n’entre pas dans l’historique. L’ingestion n’utilise jamais `coerceToText()` sur une URI : elle traite explicitement les représentations texte, HTML et fichier afin de ne pas déclencher une lecture implicite hostile ou imprévisible.

Le HTML est conservé avec son repli texte, mais n’est jamais rendu dans une `WebView` du clavier. Un lien `http`, `https`, `mailto` ou `tel` est conservé comme chaîne exacte et n’est jamais ouvert ni téléchargé par le clavier. Les MIME, libellés et noms venant d’un fournisseur sont considérés non fiables ; ils ne servent jamais de chemin de fichier. Les miniatures sont facultatives, décodées avec des dimensions et une mémoire bornées, et leur échec ne fait pas perdre le contenu original.

La taille maximale de 25 Mo s’applique au groupe complet après copie dans le stockage privé. Un groupe contient au plus 32 items. Les flux sont copiés de manière bornée et annulable, avec un délai maximal de 30 secondes par entrée : l’ingestion s’arrête dès qu’une limite est dépassée, sans conserver de fichier partiel. Les métadonnées sont bornées avant traitement : MIME ASCII de 255 caractères, libellé ou nom de 4 096 caractères et aperçu nettoyé de 256 caractères au plus. Les contrôles invisibles et caractères bidirectionnels sont neutralisés dans l’affichage, sans modifier le payload collé.

### 4.3 Rétention

- aucune expiration fondée sur le temps ;
- maximum 500 entrées non épinglées ;
- maximum 250 Mo stockés pour les entrées non épinglées ;
- maximum 25 Mo par entrée ;
- aucune limite automatique de nombre ni de volume cumulé sur les entrées épinglées, la limite individuelle de 25 Mo restant applicable ;
- les épinglés ne sont jamais supprimés automatiquement ;
- si les épinglés remplissent le stockage disponible, une nouvelle ingestion échoue proprement et invite à gérer les épinglés ;
- désépingler replace immédiatement l’entrée sous les quotas ordinaires et annonce toute purge nécessaire avant confirmation ;
- une entrée identique existante remonte en tête et conserve son état épinglé au lieu de créer un doublon ;
- la sensibilité est monotone lors d’une déduplication : une entrée déjà ou nouvellement sensible reste sensible et n’est jamais rétrogradée par une copie ultérieure.

Dans cette spécification, `1 Mo = 1 048 576 octets`. `storedByteSize` compte les octets du manifest, des payloads, miniatures et fichiers attribuables à l’entrée ; seul l’overhead global des pages SQLite est exclu. La limite de 25 Mo s’applique aussi aux épinglés. Le repository réserve l’espace temporaire avant lecture, refuse proprement un manque d’espace, puis purge par `lastCopiedAt` les plus anciens non épinglés jusqu’au respect simultané des limites de 500 entrées et 250 Mo. L’entrée qui vient d’être copiée est conservée ; les épinglés ne sont jamais choisis par la purge.

### 4.4 Contenus sensibles

Une entrée est marquée sensible lorsque :

- `ClipDescription.EXTRA_IS_SENSITIVE` ou sa clé littérale compatible est vraie ;
- elle est capturée pendant une session d’éditeur mot de passe, mot de passe web/visible, PIN ou `IME_FLAG_NO_PERSONALIZED_LEARNING` ;
- le système ou l’action utilisateur `Marquer comme sensible` la classe explicitement ainsi ;
- au moins un item d’un groupe est sensible, auquel cas le groupe entier l’est.

Une entrée sensible :

- est persistée dans le même stockage privé que les autres ;
- est affichée sous la forme `Contenu sensible ••••••` avec type, taille et heure, sans extrait, nom de fichier ni miniature ;
- n’est jamais indexée dans la recherche ;
- n’est jamais proposée dans les suggestions lexicales ;
- ne produit jamais de contenu dans les logs, erreurs, toasts, descriptions TalkBack ou métriques ;
- n’est jamais traduite ou transcrite automatiquement ;
- est collée en clair seulement après un appui volontaire sur sa tuile masquée.

Lorsqu’un doublon clair devient sensible, le repository effectue la promotion avant de le republier : il supprime miniature et aperçu public, invalide résultats de recherche et caches mémoire, et remplace toute proposition active par le libellé générique. L’action manuelle est volontairement irréversible ; une erreur se corrige en supprimant l’entrée puis en la recopiant. Une source qui omet le flag Android et n’est pas copiée pendant un champ privé ne peut pas être reconnue avec certitude : cette limite est expliquée, et l’action manuelle reste disponible.

Le flag Android sensible est un indice de présentation, pas un mécanisme de chiffrement ni un contrôle d’accès. [ClipDescription.EXTRA_IS_SENSITIVE](https://developer.android.com/reference/android/content/ClipDescription#EXTRA_IS_SENSITIVE)

## 5. Architecture

### 5.1 Frontières de composants

#### `SystemClipboardGateway`

Adaptateur Android minimal autour de `ClipboardManager` :

- lit un instantané défensif du `primaryClip` ;
- acquiert d’abord le marqueur source dans un wrapper défensif dès la description obtenue, puis fige le flag sensible et chaque unité UTF-16 de texte/libellé avant toute validation ou copie ultérieure ;
- fournit sur chaque résultat une observation source typée non nulle, indépendante de l’acceptation du contenu : `NoPrimaryClip` ou `Observed(marker)` ; dans le marqueur, timestamp API 31+ différent = changement prouvé, timestamp égal = collision possible, `TimestampUnavailable` = inconnu ;
- enregistre et retire le listener ;
- transforme les exceptions de sécurité ou de fournisseur en résultats typés ;
- ne persiste rien et ne dépend pas de l’interface.

#### `ClipboardIngestPolicy`

Composant pur qui décide :

- type supporté ou non ;
- sensibilité ;
- taille et quota applicables ;
- canonicalisation nécessaire à la déduplication ;
- libellé public autorisé ;
- raison d’un rejet présentable sans contenu privé.

#### `ClipboardPrivateStore`

- conserve les données structurées dans une base Room du stockage interne de l’application ;
- conserve les médias et fichiers dans `noBackupFilesDir/clipboard`, sous des identifiants aléatoires qui ne reprennent jamais un nom fournisseur ;
- n’ajoute ni chiffrement applicatif, ni Android Keystore, ni format cryptographique propriétaire ;
- utilise des fichiers temporaires privés, `fsync` et renommage atomique avant publication ;
- supprime les fragments et fichiers orphelins lors de la réconciliation ;
- ne place jamais de payload dans le cache externe, le stockage partagé ou un répertoire sauvegardable.

#### `ClipboardHistoryRepository`

Interface de stockage indépendante de Room. Elle expose des flux d’état et des opérations logiquement atomiques pour l’appelant :

- observer les entrées triées ;
- ajouter ou remonter un doublon ;
- épingler/désépingler ;
- supprimer une entrée ;
- effacer tout ;
- rechercher les seules entrées non sensibles ;
- appliquer les quotas ;
- réconcilier base et fichiers au démarrage.

#### `RoomClipboardHistoryRepository`

Implémentation recommandée :

- Room conserve l’identifiant, la date, le type générique, `storedByteSize`, l’état épinglé, le marqueur sensible et l’empreinte SHA-256 de déduplication ;
- Room conserve aussi les états techniques `STAGING`, `READY`, `PROMOTING`, `REVOKING` et `DELETING` pour permettre la reprise après interruption ;
- les contenus textuels et petits manifests sont stockés directement dans la base privée ;
- les médias et fichiers sont stockés sous des noms aléatoires dans `noBackupFilesDir/clipboard` ;
- Room protège les transitions de métadonnées par transaction, tandis que les fichiers suivent un protocole en deux phases avec renommage dans le même répertoire ;
- une entrée n’est observable qu’en état `READY` ; `STAGING`, `PROMOTING`, `REVOKING` et `DELETING` sont internes et la réconciliation finit ou annule ces transitions ;
- les opérations disque s’exécutent hors du thread principal.

#### `ClipboardHistoryController`

Orchestre gateway, policy, repository et cycle de vie IME :

- place les instantanés de copie dans une FIFO sérialisée limitée à 32 travaux au total, travail actif compris, distincte de l’état visuel ;
- une file saturée refuse explicitement le nouvel instantané et affiche une erreur sûre, sans perte silencieuse ;
- utilise génération/version uniquement pour ignorer les résultats UI ou de session obsolètes, jamais pour jeter une ingestion déjà acceptée ;
- appartient au composant applicatif unique : la destruction/recréation du service IME ne perd pas une ingestion acceptée ; seules une barrière d’effacement/désactivation, la mort du processus ou le shutdown de test annulent la FIFO ;
- expose un état `Disabled`, `Loading`, `Ready`, `Empty` ou `Error` ;
- publie la proposition de collage temporaire ;
- ne dépend d’aucun client réseau.

#### `ClipboardContentProvider`

Fournisseur non exporté globalement, avec `grantUriPermissions=true` :

- reçoit un jeton aléatoire temporaire lié à une entrée et au type MIME ;
- lie aussi ce jeton à la session d’entrée et à l’UID non usurpable fourni par `InputBinding` pour l’application destinataire, puis vérifie que le package éditeur correspond à ce même UID ;
- ouvre un pipe en lecture seule ;
- diffuse le payload privé en flux, sans copie temporaire dans un emplacement partagé ;
- autorise au plus trois ouvertures par le bon UID pendant une fenêtre de 60 secondes ; après la troisième autorisation, aucune nouvelle ouverture ni metadata n’est acceptée, mais cette troisième lecture et les précédentes peuvent finir ;
- annule et ferme les lectures actives dès la première des conditions suivantes : fenêtre écoulée, session changée, entrée supprimée ou révocation explicite ;
- refuse un jeton inconnu, expiré, supprimé ou d’un MIME différent.

#### `KeyboardRootView`

Conteneur d’IME qui sépare :

- le rail d’état ;
- les touches existantes ;
- la mosaïque du presse-papiers ;
- la feuille d’actions épingler/supprimer ;
- le mode de recherche interne.

La mosaïque utilise des vues Android standards recyclées pour le défilement, l’accessibilité et la gestion de centaines d’éléments. `KeyboardView` conserve le rendu des touches et n’absorbe pas la base, le stockage ou le défilement.

### 5.2 Modèle de données

`ClipboardEntryEntity` contient au minimum :

- `id` aléatoire stable ;
- `createdAt` et `lastCopiedAt` ;
- `kind` générique (`TEXT`, `LINK`, `HTML`, `IMAGE`, `VIDEO`, `AUDIO`, `FILE`, `GROUP`) ;
- `isPinned` ;
- `isSensitive` ;
- `storedByteSize` ;
- `fingerprintSha256` ;
- `storageState` (`STAGING`, `READY`, `PROMOTING`, `REVOKING`, `DELETING`) ;
- `manifest` privé ;
- référence optionnelle vers un conteneur privé immuable par entrée.

Le manifest privé contient les MIME, replis texte, noms, ordre des éléments et informations nécessaires au collage. Les valeurs sensibles ne sont jamais ajoutées à un index de recherche ni exposées dans une vue de métadonnées. L’empreinte SHA-256 porte sur le type, les MIME, l’ordre et les octets exacts du payload, mais pas sur date, épinglage ou sensibilité ; deux copies identiques peuvent ainsi fusionner puis appliquer la règle de sensibilité monotone sans modifier le contenu collé.

### 5.3 Flux d’ingestion

1. Le listener signale un changement.
2. Le gateway prend immédiatement un instantané du clip courant dans un bloc défensif et le place dans la file FIFO.
3. La policy détermine type, sensibilité et limites.
4. Les URI sont ouvertes dès que possible, lues une seule fois et copiées en flux dans le conteneur privé préalloué de l’entrée ; une révocation de permission produit un échec sûr.
5. Le manifest privé et l’empreinte SHA-256 de déduplication sont calculés.
6. Le repository crée l’état `STAGING`, finalise les fichiers par renommage local, passe l’entrée à `READY`, puis purge les plus anciens non épinglés. Un crash entre ces étapes est réparé au démarrage sans exposer d’entrée incomplète.
7. Le contrôleur publie la nouvelle liste et une proposition de collage pendant 20 secondes réellement visibles.
8. Toute annulation ou erreur supprime les fragments temporaires.

Le bouton `Tout effacer` écrit d’abord un état durable `CLEARING_ENABLED`, pose une barrière sur l’ingestion, retire temporairement l’écoute, annule et joint le travail actif ainsi que la file déjà acceptée, puis capture le clip courant **avant** de persister son observation source et de purger. Cette observation est orthogonale à l’acceptation : `Captured`, `Failure` borné et `Empty` borné conservent `Observed(marker)` lorsqu’une description a été vue, sans rendre les métadonnées dans l’erreur ; l’absence réelle de clip produit `NoPrimaryClip`. Le contrôleur écrit alors respectivement `Suppressed(marker)` ou `NotSuppressed`. Un crash reprend ce clear avant toute écoute. Sur API 31+, le listener capture d’abord le clip et ne lève `Suppressed` que si son timestamp source non nul diffère du timestamp persisté ; égal est `SAME_OR_COLLIDING`, indisponible est `UNKNOWN`, et tous deux restent supprimés. Sur API 24–30, le prochain callback listener est la preuve legacy du changement. Le démarrage ne retire jamais le marqueur ; ainsi, aucune copie antérieure à la confirmation ne peut réapparaître après l’effacement.

### 5.4 Flux de collage

Pour le texte et les liens :

- calcul de la taille UTF-8 depuis le manifest appartenant à la même entrée `READY` ;
- vérification de la connexion et de la session courantes ;
- jusqu’à 128 Kio UTF-8, lecture bornée en mémoire puis `commitText` unique ;
- au-delà, transfert exact par URI temporaire `text/plain` et un unique `commitContent` si l’éditeur annonce ce MIME ;
- sinon refus avant mutation avec `Cette application ne peut pas recevoir ce texte volumineux` ;
- en cas de retour `false`, Jefe Keyboard arrête l’action et ne tente aucune seconde mutation.

Cette borne directe évite de dépasser le tampon Binder partagé d’Android ; elle ne réduit pas la limite de sauvegarde de 25 Mo définie ici en octets binaires. Un lien est collé comme sa chaîne exacte, sans résolution réseau. Pour le HTML, le clavier propose le type `text/html` par `commitContent` uniquement si l’éditeur l’annonce ; sinon son repli texte exact suit la même règle 128 Kio/`text/plain`. Le refus d’un `commitContent` révoque immédiatement l’URI et ne déclenche aucun second essai.

Pour un contenu riche :

- vérification des MIME acceptés par l’éditeur ;
- création d’une URI temporaire du provider ;
- collage via `InputConnectionCompat.commitContent` avec autorisation de lecture ;
- révocation et nettoyage au changement de session ou à expiration ;
- si l’éditeur refuse le type, aucune mutation et message `Cette application n’accepte pas ce contenu`.

Android ne fournit pas d’opération IME atomique pour plusieurs contenus riches. Un groupe ouvre donc une feuille ordonnée dans laquelle chaque élément peut être collé séparément. Pour un groupe entièrement textuel, l’action explicite `Coller tout` assemble les éléments avec des sauts de ligne annoncés dans l’interface : un unique `commitText` sous 128 Kio UTF-8, ou un unique payload provider `text/plain` diffusé en flux au-delà si l’éditeur l’accepte. Sans compatibilité `text/plain`, l’action échoue avant mutation avec le message de texte volumineux. L’action `Coller tout` n’est pas proposée pour plusieurs fichiers ou médias : tous restent sauvegardés et collables individuellement, sans prétendre pouvoir annuler un premier collage accepté si un second était refusé.

La prévalidation garantit que Jefe Keyboard n’appelle pas l’éditeur pour un MIME annoncé incompatible. Une `InputConnection` appartient toutefois à l’application cible : si une application défectueuse modifie son texte puis retourne `false`, Android n’offre aucun rollback au clavier. Les tests vérifient donc l’absence de seconde mutation après refus et non une atomicité que la plateforme ne garantit pas.

## 6. Rail intelligent

### 6.1 États

Le rail conserve une hauteur fixe et un onglet presse-papiers à gauche. Le reste affiche exactement un état :

1. `TranslationFeedback` (`Loading`, `Success` ou `Error`) ;
2. `ClipboardPrompt` ;
3. `Suggestions` ;
4. `Empty`.

Cette priorité empêche des messages concurrents et rend le comportement prévisible.

### 6.2 Suggestions

- aucun fond, contour ou contrôle vide lorsque la liste est vide ;
- une, deux ou trois suggestions produisent exactement une, deux ou trois zones accessibles et interactives ;
- le rail est plat, séparé par de fins diviseurs et souligné par le trait Bleu d’encre ;
- `onStartInput` et `onStartInputView` commencent toujours sans suggestion, même si l’éditeur contient déjà du texte ;
- les suggestions ne deviennent éligibles qu’après une modification de texte réussie déclenchée par ce clavier dans la session courante : caractère, espace, suppression, suggestion acceptée ou collage non sensible ;
- champ vide, espaces seuls, ponctuation seule, sélection non vide, contexte illisible ou champ privé produisent zéro suggestion ;
- un préfixe contenant au moins une lettre peut produire des complétions ; après un mot suivi d’un espace, les suggestions contextuelles sont autorisées seulement si le prédicteur possède réellement ce contexte ; une ponctuation terminale n’en produit pas ;
- supprimer jusqu’au contexte vide, déplacer la sélection depuis l’extérieur sans mutation locale correspondante ou démarrer une nouvelle session efface immédiatement les suggestions et leur snapshot.

### 6.3 Proposition de collage

Après une ingestion réussie, le rail affiche pendant 20 secondes :

- texte : `Coller · <début tronqué>… · Texte` ;
- lien : `Coller · <début du lien>… · Lien` ;
- HTML : `Coller · <début du repli texte>… · HTML` ;
- image : `Coller · Capture d’écran · Image` si le nom sûr l’indique, sinon `Coller · Image copiée · Image` ;
- audio : `Coller · <nom sûr ou Audio copié> · Audio` ;
- vidéo : `Coller · <nom sûr ou Vidéo copiée> · Vidéo` ;
- document ou fichier : `Coller · <nom sûr tronqué> · <PDF, Document ou Fichier>` ;
- groupe : `Coller · <nombre> éléments · Groupe` ;
- sensible : `Coller · Contenu sensible •••••• · Texte` (ou le type générique sûr correspondant).

La proposition disparaît après collage, frappe, fermeture, changement de session, expiration ou action de fermeture. Elle n’efface pas l’entrée de l’historique. Les 20 secondes comptent uniquement lorsque le clavier est visible et que `ClipboardPrompt` est réellement l’état prioritaire du rail ; une traduction met le compteur en pause, puis le prompt reprend avec le temps restant. Une nouvelle copie remplace le prompt courant et repart de 20 secondes, sans supprimer aucune entrée. Un texte long est ellipsé visuellement ; l’action `Coller` et le type restent toujours visibles. Son libellé accessible reste borné et le contenu complet n’est jamais injecté dans une description d’accessibilité.

### 6.4 Traduction

- l’état `Traduction en cours…` apparaît avant le lancement réseau et persiste jusqu’à terminaison ;
- le bouton traduction devient actif visuellement et non cliquable ;
- un second appui ne lance aucune requête ;
- changement de sélection, connexion, session, fermeture de l’IME ou destruction annule le job ;
- un succès n’est affiché qu’après `commitText` accepté : `Traduit ✓` pendant environ 1,2 seconde ;
- un échec affiche pendant trois secondes `Traduction impossible · Réessayer`, tandis qu’un toast fournit la raison utile ;
- après échec, le bouton est réactivé et `Réessayer` relance seulement si la même sélection et la même session sont encore valides ;
- une annulation retire immédiatement le feedback et révèle l’état suivant du rail ;
- un résultat obsolète ne peut jamais restaurer un état de succès ;
- les champs mot de passe et privés désactivent traduction et dictée réseau.

## 7. Interface du presse-papiers

### 7.1 Accès

- petit onglet presse-papiers permanent à gauche du rail ;
- aucun ajout de touche dans la rangée basse ;
- un appui ouvre la mosaïque ;
- retour ferme la mosaïque et rend le clavier sans modifier l’éditeur.

### 7.2 Mosaïque intelligente

La vue approuvée présente :

- barre `Presse-papiers` avec retour, compteur et action d’effacement ;
- champ de recherche interne ;
- section `Épinglés` ;
- section `Récents` en grille de deux colonnes ;
- aperçus texte bornés ;
- miniatures privées pour les médias non sensibles ;
- tuiles de type et nom pour les fichiers ;
- tuile générique verrouillée pour les sensibles ;
- tuile de groupe ouvrant la liste ordonnée de ses éléments et leurs actions de collage compatibles ;
- appui simple sur une entrée simple pour coller ; l’appui sur un groupe ouvre sa liste ;
- menu accessible pour épingler/désépingler, marquer définitivement comme sensible ou supprimer ;
- `Tout effacer` avec confirmation explicite incluant les épinglés.

### 7.3 Recherche interne

Le panneau ne s’appuie pas sur un `EditText` qui déclencherait récursivement l’IME. Le bouton recherche active un mode interne :

- les touches alphabétiques existantes alimentent une requête locale au lieu de l’éditeur ;
- retour arrière modifie la requête ;
- fermer la recherche rend les touches à l’éditeur ;
- seuls les textes et noms non sensibles chargés pendant la session du panneau sont comparés ;
- recherche annulable sur un dispatcher disque, avec résultats versionnés pour qu’une ancienne requête ne remplace jamais la nouvelle ;
- aucune requête ou index de recherche utilisateur n’est persisté en clair.

### 7.4 Thèmes et accessibilité

Le thème suit `uiMode` Android sans réglage manuel supplémentaire.

Palette claire approuvée :

- papier `#F4F6F5` ;
- encre `#142934` ;
- brume `#D7E0E0` ;
- ardoise `#68808A` ;
- texte secondaire `#49616B` ;
- bleu plume `#2E5C9A` ;
- enregistrement `#C84B48`.

Palette sombre correspondante :

- fond `#101719` ;
- surface `#1A252A` ;
- surface élevée `#223038` ;
- texte `#EAF0EF` ;
- secondaire `#AAB8B7` ;
- bleu plume `#7DA9E8` ;
- enregistrement `#FF8A86` ;
- séparation `#314249`.

Exigences :

- contrôles tactiles d’au moins 44 dp ;
- contraste texte normal au moins 4,5:1 ;
- l’ardoise claire `#68808A` est réservée aux icônes larges, séparateurs et éléments non textuels ; tout texte secondaire normal emploie `#49616B` afin de dépasser 4,5:1 sur papier ;
- couleur jamais seule porteuse d’un état ;
- libellés TalkBack bornés et non sensibles ;
- ordre de focus cohérent ;
- le thème sombre couvre clavier, rail, panneau, états pressés, feuilles, confirmations, consentement et réglages.

## 8. Confidentialité et sécurité

### 8.1 Garanties

- stockage app-private dans la zone normale protégée par le profil utilisateur Android ;
- aucune couche cryptographique applicative ni clé Android Keystore : ce choix explicite privilégie la simplicité, la fiabilité et la vitesse de développement ;
- aucune sauvegarde cloud ni transfert appareil-à-appareil ;
- exclusions explicites des domaines `database`, `file` et `sharedpref` dans les règles legacy et modernes ;
- aucune télémétrie ni journalisation du contenu ;
- aucune dépendance du module presse-papiers vers Whisper ou Translate ;
- aucun payload copié dans le stockage partagé, externe ou sauvegardable ;
- autorisations provider minimales, temporaires et en lecture seule ;
- aucune exécution ou prévisualisation active du HTML, des `Intent` ou des fichiers enregistrés ;
- références de texte relâchées à la fermeture du panneau et à la destruction de session ; aucun contenu n’est conservé dans un cache longue durée distinct de l’historique choisi par l’utilisateur.

Un contenu du presse-papiers n’est jamais envoyé automatiquement à un service distant. Après collage, un contenu non sensible devient du texte de l’éditeur ; une traduction ultérieure n’est permise que par une nouvelle sélection et une action explicite dans un champ non privé.

Le service maintient une politique de provenance conservatrice pour les sensibles : après le collage d’une entrée sensible par l’historique, la traduction et les suggestions sont désactivées jusqu’au prochain `onStartInput` valide. Les champs mot de passe, PIN et `IME_FLAG_NO_PERSONALIZED_LEARNING` désactivent suggestions, traduction et dictée distante. Une fois le texte modifié dans une application tierce, collé par un autre chemin ou repris dans une nouvelle session, Android ne fournit aucun marquage de provenance fiable permettant au clavier de reconnaître toutes ses sous-parties ; cette limite est annoncée honnêtement et aucune comparaison persistante de secrets n’est ajoutée.

### 8.2 Limites honnêtes

- Android 7 à 9 autorise d’autres applications en arrière-plan à lire le presse-papiers système ; Jefe Keyboard ne peut pas corriger cette exposition avant ingestion. [Secure Clipboard Handling](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling)
- Le bac à sable et le chiffrement du téléphone protègent contre une application Android ordinaire, mais pas contre un appareil rooté et déverrouillé, un processus IME compromis ou une extraction disposant déjà des clés du profil utilisateur. Ce risque est accepté en contrepartie d’un stockage plus simple et plus fiable.
- Le mode sans service permanent peut manquer des copies lorsque le processus est absent.
- Les fournisseurs de contenus peuvent refuser ou révoquer une URI avant que sa copie privée soit terminée.
- Les API IME Android ne permettent pas un collage atomique de plusieurs fichiers riches ; c’est pourquoi l’interface les colle individuellement au lieu de simuler une atomicité impossible.
- La JVM ne permet pas de remettre à zéro de façon garantie toutes les copies immuables d’une `String` ; la protection mémoire repose sur des durées de vie courtes, des buffers mutables quand possible et l’absence de cache plaintext persistant.

## 9. Gestion des erreurs

Toutes les erreurs sont typées et ne contiennent jamais le payload utilisateur.

- clip vide : aucune action ;
- contenu inaccessible : `Contenu non enregistré : accès refusé` ;
- taille dépassée : `Contenu non enregistré : limite de 25 Mo` ;
- stockage plein à cause des épinglés : `Espace du presse-papiers insuffisant · Gérer les épinglés` ;
- trop d’items ou trop de copies simultanées : `Contenu non enregistré : presse-papiers saturé` ;
- fournisseur trop lent : `Contenu non enregistré : délai dépassé` ;
- format de collage refusé : `Cette application n’accepte pas ce contenu` ;
- gros texte refusé par l’éditeur : `Cette application ne peut pas recevoir ce texte volumineux`, avant toute mutation ;
- base momentanément indisponible : état réessayable sans bloquer la saisie ;
- entrée corrompue ou fichier manquant : isolé et marqué indisponible, sans crash ni collage partiel ;
- suppression interrompue : reprise/réconciliation au démarrage ;
- résultat asynchrone obsolète : ignoré par génération de session.

## 10. Réglages

Une nouvelle catégorie `Presse-papiers` expose :

- état activé/désactivé ;
- résumé `Sans expiration · 500 éléments · 250 Mo` ;
- action `Ouvrir l’historique` ;
- action `Tout effacer` avec confirmation ;
- action `Désactiver et effacer` avec confirmation ;
- compteur d’éléments et taille actuelle sans révéler de contenu.

Les quotas validés ne sont pas configurables dans la première version afin d’éviter des combinaisons non testées et une interface de réglages inutilement complexe.

## 11. Stratégie de test

Le développement suivra RED → GREEN → REFACTOR pour chaque tranche.

### 11.1 Tests purs

- policy par type, MIME, sensibilité et taille ;
- groupes multi-items, groupe mixte et limite de 32 items ;
- déduplication, conservation de l’épinglage, promotion sensible irréversible et purge des aperçus ;
- ordre de purge nombre/volume ;
- comptage exact des octets, limite de 25 Mo sur épinglés et non épinglés ;
- absence d’expiration temporelle ;
- priorité des états du rail ;
- règles de libellé et de troncature sans fuite sensible.

### 11.2 Stockage privé et sauvegardes

- base Room créée uniquement dans le stockage interne de l’application ;
- médias et documents uniquement dans `noBackupFilesDir/clipboard` ;
- aucun fichier dans le stockage partagé ou externe ;
- règles legacy et modernes excluant base, préférences d’activation et fichiers du backup cloud et du transfert appareil-à-appareil ;
- écriture temporaire privée, publication atomique et aucun fragment résiduel après succès, erreur, annulation ou redémarrage ;
- permissions de fichiers et provider limitées au processus ou au grant temporaire prévu.

### 11.3 Repository et migrations

- DAO Room et ordre épinglés/récents ;
- transactions ajout, doublon, suppression et purge ;
- quotas 500/250 Mo avec épinglés exclus ;
- réconciliation fichiers manquants/orphelins ;
- migration de schéma testée dès toute version ultérieure.

### 11.4 Service et Android

- listener enregistré seulement après consentement ;
- relance et récupération du primary clip, sauf marqueur durable après `Tout effacer` jusqu’à une nouvelle copie prouvée ;
- exception `ClipboardManager`/`ContentResolver` absorbée ;
- deux copies rapides dont un média lent sont ingérées dans l’ordre sans abandon par génération ;
- saturation de file et timeout fournisseur produisent un échec visible sans fragment résiduel ;
- collage texte accepté/refusé, frontière directe 128 Kio, transfert `text/plain` volumineux et refus précoce si l’éditeur ne le supporte pas ;
- collage lien exact, HTML riche avec repli, groupe textuel en un commit direct ou provider et groupe riche item par item ;
- collage riche MIME compatible/incompatible, sans seconde mutation après un refus ;
- grant provider temporaire testé depuis un second APK/UID, mauvais UID/MIME/session refusé, troisième lecture complète, quatrième refusée, expiration et suppression ;
- changement de session pendant ingestion ou collage ;
- champ mot de passe ou privé : historique disponible, entrées sensibles masquées, suggestions/traduction/dictée distante désactivées ;
- collage sensible : traduction et suggestions bloquées pour la session contrôlée par l’IME ;
- désactivation : retrait immédiat du listener, aucune capture ultérieure, grants révoqués, purge intégrale et redémarrage vide.

### 11.5 Interface

- zéro contrôle suggestion pour une liste vide ;
- une/deux/trois suggestions exactes ;
- zéro suggestion au démarrage avec texte préexistant, champ vide, espaces, ponctuation, sélection ou champ privé ;
- suggestions après saisie locale, contexte après espace, puis effacement après suppression jusqu’au vide ou déplacement externe ;
- rail stable sans saut de hauteur ;
- proposition tronquée pour texte, lien, HTML, image, audio, vidéo, fichier, groupe et sensible ;
- type toujours visible, compteur de 20 secondes réellement affichées, pause sous traduction, remplacement par nouvelle copie et fermeture ;
- traduction persistante, anti-double-appui, succès après commit, échec et annulation ;
- mosaïque vide, chargement, contenu, erreur et recherche ;
- actions épingler/supprimer/effacer ;
- rendu natif clair et sombre inspecté ;
- cibles 44 dp, contrastes et libellés TalkBack.

### 11.6 Gate final

- `testDebugUnitTest` ;
- `connectedDebugAndroidTest` sur émulateur API 24 et API 34 pour Room, stockage privé, provider, grants URI et cycle de vie réel ;
- `lintDebug` sans erreur ;
- `assembleDebug` ;
- inspection de l’APK, du manifest, des règles de sauvegarde et de la signature ;
- inspection visuelle des captures : clavier vide, suggestions, proposition de collage, traduction, historique clair et historique sombre ;
- non-régression de Whisper, Translate, saisie, suppression Unicode, accents, actions Enter et cycle de vie micro.

## 12. Critères d’acceptation

La fonctionnalité est terminée lorsque :

1. aucun rail vide ne dessine de capsules ;
2. la traduction affiche un état persistant et ne peut pas être lancée deux fois ;
3. le presse-papiers est opt-in et fonctionne sans notification permanente ;
4. tous les contenus collables prévus sont copiés dans le stockage privé Android ou échouent avec une raison sûre ;
5. les sensibles sont masqués partout mais collables en clair sur appui ;
6. la proposition de collage apparaît 20 secondes avec début/type approprié ;
7. les quotas, épinglés, doublons, recherche, suppression et effacement respectent les règles validées ;
8. aucun contenu ne fuit vers logs, sauvegardes, recherche sensible ou réseau automatique ;
9. chaque type possède un chemin de collage défini ; les gros textes n’empruntent jamais une transaction Binder risquée, les incompatibilités connues échouent avant appel éditeur, un refus arrête toute mutation suivante et aucun collage multi-fichier faussement atomique n’est proposé ;
10. les thèmes clair/sombre suivent Android et passent les exigences d’accessibilité ;
11. tous les tests, lint et assemblage APK réussissent.

## 13. Hors périmètre

- synchronisation cloud ou entre appareils ;
- export/import de l’historique ;
- service permanent avec notification ;
- édition du contenu d’une entrée ;
- OCR ou classification automatique ;
- exécution d’`Intent` copiés ;
- quotas configurables ;
- thème manuel indépendant du système ;
- envoi direct d’une entrée à Whisper ou Translate ;
- garantie de capturer les copies produites pendant que le processus IME est absent.
