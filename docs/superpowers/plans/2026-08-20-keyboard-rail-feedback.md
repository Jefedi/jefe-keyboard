# Plan d’implémentation du rail intelligent et du retour de traduction

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remplacer les trois capsules vides par un rail Bleu d’encre à hauteur fixe, afficher la traduction pendant tout son cycle et ne proposer des mots qu’après une saisie locale valide.

**Architecture:** Extraire le rail du Canvas dans un `KeyboardRailView` natif et réunir rail + touches dans `KeyboardRootView`. Un réducteur pur choisit traduction > proposition de collage > suggestions > vide ; le service possède les entrées du réducteur, la confidentialité de l’éditeur, la session de suggestions et l’unique job de traduction.

**Tech Stack:** Kotlin 2.0.21, Android SDK 24–34, vues Android natives, Canvas pour les seules touches, coroutines 1.8.1, Robolectric 4.16.1, JUnit 4, Material 3 DayNight.

**Spec:** `docs/superpowers/specs/2026-08-20-keyboard-feedback-clipboard-design.md`

## Global Constraints

- Exécuter ce plan avant `docs/superpowers/plans/2026-08-20-secure-clipboard-history.md`, qui consomme les types produits ici.
- Conserver `minSdk = 24`, `targetSdk = 34`, Java 17 et le comportement HTTPS/cancellable existant.
- Le rail mesure 48 dp, garde un onglet presse-papiers de 48 × 48 dp et ne change jamais la hauteur totale du clavier.
- Priorité stricte : traduction (`Loading`, `Success`, `Error`) > proposition de collage > suggestions > vide.
- Le rail utilise des vues Android natives : aucun nœud tactile ou TalkBack n’existe pour une suggestion absente.
- Une session commence sans suggestion ; une suggestion exige une mutation locale acceptée et un contexte non privé valide.
- Mot de passe texte/web/visible, mot de passe numérique/PIN et `IME_FLAG_NO_PERSONALIZED_LEARNING` désactivent suggestions, traduction et dictée distante.
- Le thème suit uniquement `uiMode` Android ; contraste normal ≥ 4,5:1 en clair et sombre.
- Chaque tâche suit RED → GREEN → REFACTOR et se termine par son propre commit.

---

## Carte des responsabilités

| Fichier | Responsabilité unique |
|---|---|
| `EditorPrivacyPolicy.kt` | Classer la session éditeur sans dépendre des vues ni du réseau. |
| `TopRailState.kt` | Modèles du rail et priorité pure entre traduction, prompt et suggestions. |
| `KeyboardRailView.kt` | Contrôles natifs visibles, tactiles et TalkBack du rail. |
| `KeyboardRootView.kt` | Hauteur stable et composition rail + touches. |
| `SuggestionPolicy.kt` | Éligibilité textuelle et gate de mutation locale. |
| `KeyboardView.kt` | Dessin/gestes des seules touches. |
| `JefeKeyboardService.kt` | Propriétaire des sessions, jobs, snapshots et entrées du réducteur. |

---

### Task 1: Politique partagée de confidentialité de l’éditeur

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/EditorPrivacyPolicy.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/EditorPrivacyPolicyTest.kt`

**Interfaces:**
- Produces: `EditorPrivacyPolicy.evaluate(info: EditorInfo?): EditorPrivacyState` and its pure integer overload.
- Produces for clipboard plan: `isPrivate`, `allowSuggestions`, `allowTranslation`, `allowDictation`, `forceSensitiveClipboard`.

- [ ] **Step 1: Écrire les tests RED de toutes les variantes privées**

```kotlin
package ovh.jefe.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorPrivacyPolicyTest {
    @Test
    fun `password pin and no learning are private`() {
        val inputTypes = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        inputTypes.forEach { inputType ->
            assertPrivate(EditorPrivacyPolicy.evaluate(inputType, 0))
        }
        assertPrivate(
            EditorPrivacyPolicy.evaluate(
                InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            ),
        )
    }

    @Test
    fun `ordinary text allows local and explicit remote actions`() {
        val state = EditorPrivacyPolicy.evaluate(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL,
            EditorInfo.IME_ACTION_SEND,
        )

        assertFalse(state.isPrivate)
        assertTrue(state.allowSuggestions)
        assertTrue(state.allowTranslation)
        assertTrue(state.allowDictation)
        assertFalse(state.forceSensitiveClipboard)
    }

    @Test
    fun `missing editor info and type null fail closed`() {
        assertPrivate(EditorPrivacyPolicy.evaluate(null))
        assertPrivate(EditorPrivacyPolicy.evaluate(InputType.TYPE_NULL, 0))
    }

    private fun assertPrivate(state: EditorPrivacyState) {
        assertTrue(state.isPrivate)
        assertFalse(state.allowSuggestions)
        assertFalse(state.allowTranslation)
        assertFalse(state.allowDictation)
        assertTrue(state.forceSensitiveClipboard)
    }
}
```

- [ ] **Step 2: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*EditorPrivacyPolicyTest' --no-daemon`

Expected: FAIL à la compilation sur `EditorPrivacyPolicy` et `EditorPrivacyState`.

- [ ] **Step 3: Implémenter la policy pure**

```kotlin
package ovh.jefe.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

internal data class EditorPrivacyState(
    val isPrivate: Boolean,
    val allowSuggestions: Boolean,
    val allowTranslation: Boolean,
    val allowDictation: Boolean,
    val forceSensitiveClipboard: Boolean,
)

internal object EditorPrivacyPolicy {
    fun evaluate(info: EditorInfo?): EditorPrivacyState =
        if (info == null) privateState() else evaluate(info.inputType, info.imeOptions)

    fun evaluate(inputType: Int, imeOptions: Int): EditorPrivacyState {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val password = when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            )
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        val noLearning = imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        val privateField = inputClass == InputType.TYPE_NULL || password || noLearning
        return if (privateField) privateState() else EditorPrivacyState(
            isPrivate = false,
            allowSuggestions = true,
            allowTranslation = true,
            allowDictation = true,
            forceSensitiveClipboard = false,
        )
    }

    private fun privateState() = EditorPrivacyState(
        isPrivate = true,
        allowSuggestions = false,
        allowTranslation = false,
        allowDictation = false,
        forceSensitiveClipboard = true,
    )
}
```

- [ ] **Step 4: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*EditorPrivacyPolicyTest' --no-daemon`

Expected: PASS, 3 tests.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/EditorPrivacyPolicy.kt app/src/test/java/ovh/jefe/keyboard/EditorPrivacyPolicyTest.kt
git commit -m "feat: classify private editor sessions"
```

### Task 2: Modèle pur et priorité du rail

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/TopRailState.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/TopRailStateTest.kt`

**Interfaces:**
- Produces: `TranslationFeedback`, `ClipboardPromptUi`, `TopRailInputs`, `TopRailState`, `TopRailResolver.resolve(inputs)`.
- `ClipboardPromptUi` sépare aperçu ellipsable et type non réductible ; la vue remasque encore toute entrée sensible pour échouer fermée.

- [ ] **Step 1: Écrire les tests RED de priorité et de filtrage exact**

```kotlin
package ovh.jefe.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TopRailStateTest {
    private val prompt = ClipboardPromptUi(
        entryId = "entry-1",
        preview = "bonjour…",
        typeLabel = "Texte",
        contentDescription = "Coller le texte bonjour",
        isSensitive = false,
    )

    @Test
    fun `translation wins over clipboard and suggestions`() {
        assertEquals(
            TopRailState.Translation(TranslationFeedback.Loading),
            TopRailResolver.resolve(
                TopRailInputs(
                    translation = TranslationFeedback.Loading,
                    clipboardPrompt = prompt,
                    suggestions = listOf("bonjour"),
                ),
            ),
        )
    }

    @Test
    fun `clipboard wins over suggestions`() {
        assertEquals(
            TopRailState.ClipboardPrompt(prompt),
            TopRailResolver.resolve(
                TopRailInputs(clipboardPrompt = prompt, suggestions = listOf("bonjour")),
            ),
        )
    }

    @Test
    fun `suggestions remove blanks preserve order and cap at three`() {
        assertEquals(
            TopRailState.Suggestions(listOf("un", "deux", "trois")),
            TopRailResolver.resolve(
                TopRailInputs(suggestions = listOf("un", " ", "deux", "trois", "quatre")),
            ),
        )
    }

    @Test
    fun `idle inputs are empty`() {
        assertTrue(TopRailResolver.resolve(TopRailInputs()) is TopRailState.Empty)
    }
}
```

- [ ] **Step 2: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*TopRailStateTest' --no-daemon`

Expected: FAIL à la compilation sur `TopRailState`.

- [ ] **Step 3: Ajouter les types et le réducteur**

```kotlin
package ovh.jefe.keyboard

internal sealed interface TranslationFeedback {
    data object Idle : TranslationFeedback
    data object Loading : TranslationFeedback
    data object Success : TranslationFeedback
    data object Error : TranslationFeedback
}

internal class ClipboardPromptUi(
    val entryId: String,
    val preview: String,
    val typeLabel: String,
    val contentDescription: String,
    val isSensitive: Boolean,
) {
    override fun equals(other: Any?): Boolean = other is ClipboardPromptUi &&
        entryId == other.entryId && preview == other.preview && typeLabel == other.typeLabel &&
        contentDescription == other.contentDescription && isSensitive == other.isSensitive
    override fun hashCode(): Int = 31 * entryId.hashCode() + isSensitive.hashCode()
    override fun toString(): String =
        "ClipboardPromptUi(entryId=$entryId, sensitive=$isSensitive, redacted=true)"
}

internal class TopRailInputs(
    val translation: TranslationFeedback = TranslationFeedback.Idle,
    val clipboardPrompt: ClipboardPromptUi? = null,
    val suggestions: List<String> = emptyList(),
) {
    fun copy(
        translation: TranslationFeedback = this.translation,
        clipboardPrompt: ClipboardPromptUi? = this.clipboardPrompt,
        suggestions: List<String> = this.suggestions,
    ): TopRailInputs = TopRailInputs(translation, clipboardPrompt, suggestions)

    override fun toString(): String = "TopRailInputs(redacted=true)"
}

internal sealed interface TopRailState {
    data object Empty : TopRailState
    data class Translation(val feedback: TranslationFeedback) : TopRailState
    data class ClipboardPrompt(val prompt: ClipboardPromptUi) : TopRailState
    class Suggestions(val values: List<String>) : TopRailState {
        override fun equals(other: Any?): Boolean = other is Suggestions && values == other.values
        override fun hashCode(): Int = values.size
        override fun toString(): String = "Suggestions(count=${values.size}, redacted=true)"
    }
}

internal object TopRailResolver {
    fun resolve(inputs: TopRailInputs): TopRailState {
        if (inputs.translation != TranslationFeedback.Idle) {
            return TopRailState.Translation(inputs.translation)
        }
        inputs.clipboardPrompt?.let { return TopRailState.ClipboardPrompt(it) }
        val values = inputs.suggestions.filter(String::isNotBlank).take(3)
        return if (values.isEmpty()) TopRailState.Empty else TopRailState.Suggestions(values)
    }
}
```

Les modèles qui transportent aperçu ou suggestions ne sont pas des `data class` et leur `toString()` est expurgé. `TopRailStateTest` ajoute une sentinelle `secret-ne-jamais-journaliser` et exige son absence dans `ClipboardPromptUi`, `TopRailInputs` et `Suggestions` convertis en chaîne.

- [ ] **Step 4: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*TopRailStateTest' --no-daemon`

Expected: PASS, 4 tests.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/TopRailState.kt app/src/test/java/ovh/jefe/keyboard/TopRailStateTest.kt
git commit -m "feat: define intelligent rail states"
```

### Task 3: Extraire le rail natif et créer la racine du clavier

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/KeyboardRailView.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/KeyboardRootView.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt`
- Create: `app/src/main/res/drawable/ic_clipboard.xml`
- Create: `app/src/main/res/drawable/ic_close.xml`
- Create: `app/src/main/res/drawable/bg_rail_control.xml`
- Create: `app/src/main/res/drawable/rail_divider.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/ovh/jefe/keyboard/KeyboardRailViewTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/KeyboardRootViewTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/KeyboardViewTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt`

**Interfaces:**
- Consumes: `TopRailState` de Task 2.
- Produces: `KeyboardRailView.render(state)`, `KeyboardRootView.railView`, `KeyboardRootView.keyboardView`, `KeyboardRootView.renderRail(state)`.
- Produces callbacks: `onClipboardTabClick`, `onSuggestionClick`, `onTranslationRetryClick`, `onClipboardPromptClick`, `onClipboardPromptDismiss`.
- Removes: toute suggestion, capsule ou `KeyAction.SUGGESTION` du Canvas `KeyboardView`.

- [ ] **Step 1: Écrire les tests RED de structure, compte exact et cibles tactiles**

```kotlin
@RunWith(RobolectricTestRunner::class)
class KeyboardRailViewTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `empty rail contains clipboard tab and no suggestion`() {
        val rail = KeyboardRailView(context)
        rail.render(TopRailState.Empty)

        assertEquals(1, rail.touchControls().size)
        assertEquals("Presse-papiers", rail.touchControls().single().contentDescription)
        assertTrue(rail.suggestionViews().isEmpty())
    }

    @Test
    fun `one two and three suggestions create the exact native views`() {
        val rail = KeyboardRailView(context)
        (1..3).forEach { count ->
            val values = listOf("un", "deux", "trois").take(count)
            rail.render(TopRailState.Suggestions(values))
            assertEquals(values, rail.suggestionViews().map { it.text.toString() })
        }
    }

    @Test
    fun `all controls keep a forty four dp target`() {
        val rail = KeyboardRailView(context)
        rail.render(TopRailState.Suggestions(listOf("un", "deux", "trois")))
        measureAndLayout(rail, 1080, 48.dp(context))
        val minimum = 44.dp(context)
        assertTrue(rail.touchControls().all { it.height >= minimum })
    }

    @Test
    fun `narrow prompt ellipsizes only preview and keeps action and type`() {
        val rail = KeyboardRailView(context)
        rail.render(
            TopRailState.ClipboardPrompt(
                ClipboardPromptUi("1", "rapport annuel très long", "PDF", "Coller le PDF", false),
            ),
        )
        measureAndLayout(rail, 240.dp(context), 48.dp(context))

        assertTrue(rail.visibleTexts().contains("Coller"))
        assertTrue(rail.visibleTexts().contains("PDF"))
    }

    private fun Int.dp(context: Context): Int =
        (this * context.resources.displayMetrics.density).roundToInt()

    private fun measureAndLayout(view: View, width: Int, height: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }
}
```

Dans `KeyboardRootViewTest`, affirmer que `railView` précède `keyboardView`, que le rail reste 48 dp dans les quatre états et que la hauteur racine ne change pas. Dans `JefeKeyboardServiceTest`, remplacer le cast direct par `KeyboardRootView` et récupérer `root.keyboardView`.

- [ ] **Step 2: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*KeyboardRailViewTest' --tests '*KeyboardRootViewTest' --tests '*KeyboardViewTest' --tests '*JefeKeyboardServiceTest' --no-daemon`

Expected: FAIL à la compilation sur les deux nouvelles vues.

- [ ] **Step 3: Créer `KeyboardRailView` avec des vues natives réelles**

```kotlin
internal class KeyboardRailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    var onClipboardTabClick: (() -> Unit)? = null
    var onSuggestionClick: ((String) -> Unit)? = null
    var onTranslationRetryClick: (() -> Unit)? = null
    var onClipboardPromptClick: ((String) -> Unit)? = null
    var onClipboardPromptDismiss: (() -> Unit)? = null

    internal var state: TopRailState = TopRailState.Empty
        private set

    private val clipboard = ImageButton(context).apply {
        setImageResource(R.drawable.ic_clipboard)
        imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.suggestion_text))
        contentDescription = context.getString(R.string.clipboard_open)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        setOnClickListener { onClipboardTabClick?.invoke() }
    }
    private val content = LinearLayout(context).apply {
        orientation = HORIZONTAL
        showDividers = SHOW_DIVIDER_MIDDLE
        dividerDrawable = AppCompatResources.getDrawable(context, R.drawable.rail_divider)
    }

    init {
        orientation = HORIZONTAL
        minimumHeight = dp(48)
        addView(clipboard, LayoutParams(dp(48), dp(48)))
        addView(content, LayoutParams(0, dp(48), 1f))
        render(TopRailState.Empty)
    }

    fun render(state: TopRailState) {
        this.state = state
        content.removeAllViews()
        when (state) {
            TopRailState.Empty -> Unit
            is TopRailState.Suggestions -> state.values.forEach(::addSuggestion)
            is TopRailState.ClipboardPrompt -> addPrompt(state.prompt)
            is TopRailState.Translation -> addTranslation(state.feedback)
        }
    }


    private fun addSuggestion(value: String) {
        content.addView(
            TextView(context).apply {
                text = value
                tag = SUGGESTION_TAG
                gravity = Gravity.CENTER
                minHeight = dp(44)
                isClickable = true
                isFocusable = true
                contentDescription = context.getString(R.string.suggestion_insert, value)
                setBackgroundResource(R.drawable.bg_rail_control)
                setOnClickListener { onSuggestionClick?.invoke(value) }
            },
            LayoutParams(0, dp(48), 1f),
        )
    }

    private fun addPrompt(prompt: ClipboardPromptUi) {
        val safePreview = if (prompt.isSensitive) {
            context.getString(R.string.clipboard_sensitive_preview)
        } else {
            prompt.preview
        }
        // typeLabel vient uniquement du mapping d'enum ClipboardKind, jamais du fournisseur.
        val safeType = prompt.typeLabel
        val promptButton = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            contentDescription = if (prompt.isSensitive) {
                context.getString(R.string.clipboard_sensitive_paste_description)
            } else {
                prompt.contentDescription
            }
            setBackgroundResource(R.drawable.bg_rail_control)
            setOnClickListener { onClipboardPromptClick?.invoke(prompt.entryId) }
            addView(TextView(context).apply {
                setText(R.string.clipboard_paste_action)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply { marginStart = dp(12) })
            addView(TextView(context).apply {
                text = safePreview
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
            if (safeType.isNotEmpty()) addView(TextView(context).apply {
                text = safeType
                maxLines = 1
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            })
        }
        content.addView(promptButton, LayoutParams(0, dp(48), 1f))
        content.addView(
            ImageButton(context).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.suggestion_text))
                contentDescription = context.getString(R.string.clipboard_close_prompt)
                minimumWidth = dp(48)
                minimumHeight = dp(48)
                setOnClickListener { onClipboardPromptDismiss?.invoke() }
            },
            LayoutParams(dp(48), dp(48)),
        )
    }

    private fun addTranslation(feedback: TranslationFeedback) {
        when (feedback) {
            TranslationFeedback.Loading -> content.addView(
                statusView(R.string.rail_translation_loading, includeProgress = true),
                LayoutParams(LayoutParams.MATCH_PARENT, dp(48)),
            )
            TranslationFeedback.Success -> content.addView(
                statusView(R.string.rail_translation_success),
                LayoutParams(LayoutParams.MATCH_PARENT, dp(48)),
            )
            TranslationFeedback.Error -> content.addView(
                Button(context).apply {
                    tag = RETRY_TAG
                    setText(R.string.rail_translation_error)
                    minHeight = dp(44)
                    setOnClickListener { onTranslationRetryClick?.invoke() }
                },
                LayoutParams(LayoutParams.MATCH_PARENT, dp(48)),
            )
            TranslationFeedback.Idle -> Unit
        }
    }

    private fun statusView(@StringRes label: Int, includeProgress: Boolean = false): View =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            if (includeProgress) {
                addView(
                    ProgressBar(context).apply { isIndeterminate = true },
                    LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(8) },
                )
            }
            addView(TextView(context).apply { setText(label) })
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    internal fun suggestionViews(): List<TextView> =
        content.children.filterIsInstance<TextView>().filter { it.tag == SUGGESTION_TAG }.toList()

    internal fun retryButton(): Button =
        content.children.filterIsInstance<Button>().single { it.tag == RETRY_TAG }

    internal fun touchControls(): List<View> = buildList {
        fun collect(view: View) {
            if (view.isClickable) add(view)
            if (view is ViewGroup) view.children.forEach(::collect)
        }
        collect(this@KeyboardRailView)
    }.distinct()

    internal fun visibleTexts(): List<String> = buildList {
        fun collect(view: View) {
            if (view is TextView && view.visibility == VISIBLE) add(view.text.toString())
            if (view is ViewGroup) view.children.forEach(::collect)
        }
        collect(this@KeyboardRailView)
    }

    private companion object {
        const val SUGGESTION_TAG = "keyboard-rail-suggestion"
        const val RETRY_TAG = "keyboard-rail-retry"
    }
}
```

Les helpers de test `touchControls()` et `suggestionViews()` restent `internal` et dérivent de la hiérarchie réelle ; ils ne créent jamais de contrôles synthétiques.

- [ ] **Step 4: Créer `KeyboardRootView` sans modifier la hauteur totale**

```kotlin
internal class KeyboardRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    val railView = KeyboardRailView(context)
    val keyboardView = KeyboardView(context)

    init {
        orientation = VERTICAL
        addView(railView, LayoutParams(LayoutParams.MATCH_PARENT, dp(48)))
        addView(keyboardView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun renderRail(state: TopRailState) = railView.render(state)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
```

Supprimer `suggestionHeight`, `computedSuggestions`, le dessin/hit-test/accessors des suggestions et leur hauteur de `KeyboardView.onMeasure`. La hauteur retirée du Canvas est exactement remplacée par le rail racine.

Dans le même commit, migrer **tous** les appels existants du service avant suppression : chaque `keyboardView?.suggestions = values/emptyList()` devient `setSuggestions(values/emptyList())`, chaque ancien callback `KeyboardView.onSuggestionClick` est retiré et routé par `rail.onSuggestionClick`, et les tests qui lisaient `renderedSuggestions()` lisent la hiérarchie native du rail. L’algorithme historique peut encore calculer ses valeurs jusqu’à Task 4, mais aucune référence au contrat Canvas supprimé ne subsiste au GREEN de Task 3 ; `rg -n "\.suggestions|onSuggestionClick|renderedSuggestions" app/src` ne doit retourner que le nouveau callback rail ou zéro ancien usage.

Ajouter dès cette tâche à `KeyboardView` la propriété neutre `var remoteActionsEnabled = true` avec `invalidate()` dans son setter ; Task 4 branche son effet sur hit-test et rendu.

- [ ] **Step 5: Faire retourner la racine par le service et router les callbacks**

Le service conserve `rootView` et `keyboardView`. `onCreateInputView()` construit `KeyboardRootView(this)`, branche les callbacks des touches sur `root.keyboardView`, les suggestions sur `root.railView`, puis retourne la racine.

```kotlin
override fun onCreateInputView(): View = KeyboardRootView(this).also { root ->
    rootView = root
    keyboardView = root.keyboardView
    setupKeyboardCallbacks(root.keyboardView)
    setupRailCallbacks(root.railView)
    root.keyboardView.enterAction = pendingEnterAction
    root.keyboardView.isRecording = recordingMode
    root.keyboardView.remoteActionsEnabled = editorPrivacy.allowTranslation || editorPrivacy.allowDictation
    setSuggestions(emptyList())
}
```

```kotlin
private fun setupRailCallbacks(rail: KeyboardRailView) {
    rail.onSuggestionClick = { word -> acceptSuggestion(word) }
    rail.onClipboardTabClick = { onClipboardRequested() }
    rail.onTranslationRetryClick = { retryTranslation() }
    rail.onClipboardPromptClick = { id -> onClipboardPromptRequested(id) }
    rail.onClipboardPromptDismiss = { dismissClipboardPrompt() }
}

private var railInputs = TopRailInputs()
private var editorPrivacy = EditorPrivacyPolicy.evaluate(null)

private fun renderRail() {
    rootView?.renderRail(TopRailResolver.resolve(railInputs))
}

private fun setSuggestions(values: List<String>) {
    railInputs = railInputs.copy(suggestions = values)
    renderRail()
}
```

Créer les quatre méthodes sans effet suivantes ; Task 5 et le plan presse-papiers remplacent ensuite leurs corps :

```kotlin
private fun onClipboardRequested() = Unit
private fun onClipboardPromptRequested(entryId: String) = Unit
private fun dismissClipboardPrompt() = Unit
private fun retryTranslation() = Unit
```

Adapter la fixture service dès cette tâche afin que toutes les suivantes emploient la même racine :

```kotlin
private fun startRootService(
    connection: EditableInputConnection,
    action: Int = EditorInfo.IME_ACTION_UNSPECIFIED,
): Pair<TestJefeKeyboardService, KeyboardRootView> {
    val service = testService(connection, action)
    return service to createRootAndStart(service, action)
}

private fun createRootAndStart(
    service: TestJefeKeyboardService,
    action: Int = service.testEditorInfo?.imeOptions ?: EditorInfo.IME_ACTION_UNSPECIFIED,
    info: EditorInfo = editorInfo(action),
): KeyboardRootView {
    service.testEditorInfo = info
    service.onStartInput(info, false)
    val root = service.onCreateInputView() as KeyboardRootView
    service.onStartInputView(info, false)
    return root
}

private fun editorInfo(
    action: Int = EditorInfo.IME_ACTION_UNSPECIFIED,
    inputType: Int = InputType.TYPE_CLASS_TEXT,
): EditorInfo = EditorInfo().apply {
    imeOptions = action
    this.inputType = inputType
}

private fun drainMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle()
}
```

Remplacer chaque variable `view` des tests existants par `root.keyboardView`; les assertions de suggestions passent par `root.railView.suggestionViews()`.

- [ ] **Step 6: Ajouter les ressources exactes et icônes locales**

```xml
<string name="clipboard_open">Presse-papiers</string>
<string name="clipboard_close_prompt">Masquer la proposition de collage</string>
<string name="clipboard_paste_action">Coller</string>
<string name="clipboard_sensitive_preview">Contenu sensible ••••••</string>
<string name="clipboard_sensitive_paste_description">Coller le contenu sensible</string>
<string name="suggestion_insert">Insérer %1$s</string>
<string name="rail_translation_loading">Traduction en cours…</string>
<string name="rail_translation_success">Traduit ✓</string>
<string name="rail_translation_error">Traduction impossible · Réessayer</string>
```

Créer les deux vecteurs 24 dp monochromes suivants ; leur couleur réelle vient du tint du thème :

```xml
<!-- res/drawable/ic_clipboard.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF000000"
        android:pathData="M19,3h-4.18C14.4,1.84 13.3,1 12,1S9.6,1.84 9.18,3H5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM12,3c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,19H5V5h2v3h10V5h2v14z" />
</vector>
```

Créer dès cette tâche les deux fonds consommés par le rail afin que son gate isolé compile :

```xml
<!-- res/drawable/bg_rail_control.xml -->
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_pressed="true">
        <shape><solid android:color="@color/suggestion_pressed" /></shape>
    </item>
    <item>
        <shape><solid android:color="@android:color/transparent" /></shape>
    </item>
</selector>
```

```xml
<!-- res/drawable/rail_divider.xml -->
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <size android:width="1dp" />
    <solid android:color="@color/suggestion_outline" />
</shape>
```

```xml
<!-- res/drawable/ic_close.xml -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FF000000"
        android:pathData="M18.3,5.71 12,12l6.3,6.29 -1.41,1.42L10.59,13.41 4.29,19.71 2.88,18.29 9.17,12 2.88,5.71 4.29,4.29 10.59,10.59 16.89,4.29z" />
</vector>
```

- [ ] **Step 7: Vérifier le GREEN de la racine et des anciens gestes**

Run: `./gradlew testDebugUnitTest --tests '*KeyboardRailViewTest' --tests '*KeyboardRootViewTest' --tests '*KeyboardViewTest' --tests '*JefeKeyboardServiceTest' --no-daemon`

Expected: PASS ; zéro vue de suggestion en état vide, 1/2/3 vues exactes et tous les tests accents/touches/Enter restent verts.

- [ ] **Step 8: Contrôler et committer**

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/KeyboardRailView.kt app/src/main/java/ovh/jefe/keyboard/KeyboardRootView.kt app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt app/src/main/res/drawable/ic_clipboard.xml app/src/main/res/drawable/ic_close.xml app/src/main/res/drawable/bg_rail_control.xml app/src/main/res/drawable/rail_divider.xml app/src/main/res/values/strings.xml app/src/test/java/ovh/jefe/keyboard/KeyboardRailViewTest.kt app/src/test/java/ovh/jefe/keyboard/KeyboardRootViewTest.kt app/src/test/java/ovh/jefe/keyboard/KeyboardViewTest.kt app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt
git commit -m "feat: extract an accessible keyboard rail"
```

### Task 4: Gate pur de suggestions et mutations locales acceptées

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/SuggestionPolicy.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/SuggestionPolicyTest.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt`

**Interfaces:**
- Produces: `SuggestionSessionGate`, `SuggestionMutation`, `SuggestionPolicy.contextOrNull(input)`.
- Consumes: `EditorPrivacyState` et `KeyboardRootView.renderRail`.
- Contract for clipboard plan: `SuggestionMutation.NON_SENSITIVE_PASTE` rend éligible ; `SuggestionSessionGate.taintForSession()` est obligatoire après collage sensible et ne se réinitialise qu’au prochain `onStartInput`.

- [ ] **Step 1: Écrire les tests RED de policy et de gate**

```kotlin
@Test
fun `policy accepts prefix and context only after a local mutation`() {
    val prefix = SuggestionPolicy.contextOrNull(
        SuggestionPolicyInput("bo", true, true, true),
    )
    val context = SuggestionPolicy.contextOrNull(
        SuggestionPolicyInput("je ", true, true, true),
    )

    assertEquals("bo", prefix?.currentWord)
    assertEquals("je", context?.lastWord)
}

@Test
fun `policy rejects startup blank punctuation selection and private session`() {
    listOf(
        SuggestionPolicyInput("bo", true, false, true),
        SuggestionPolicyInput("   ", true, true, true),
        SuggestionPolicyInput("bonjour.", true, true, true),
        SuggestionPolicyInput("je. ", true, true, true),
        SuggestionPolicyInput("je\n", true, true, true),
        SuggestionPolicyInput("je\t", true, true, true),
        SuggestionPolicyInput("bo", false, true, true),
        SuggestionPolicyInput("bo", true, true, false),
    ).forEach { assertNull(SuggestionPolicy.contextOrNull(it)) }
}

@Test
fun `external selection invalidates a previously accepted edit`() {
    val gate = SuggestionSessionGate()
    gate.recordSuccessfulMutation(SuggestionMutation.CHARACTER, EditorSelectionRange(2, 2))
    assertTrue(gate.recordSelectionUpdate(EditorSelectionRange(2, 2)))
    assertFalse(gate.recordSelectionUpdate(EditorSelectionRange(0, 0)))
    assertFalse(gate.allowsSuggestionsAt(EditorSelectionRange(0, 0)))
}

@Test
fun `two fast edits survive delayed ordered selection callbacks`() {
    val gate = SuggestionSessionGate()
    gate.recordSuccessfulMutation(SuggestionMutation.CHARACTER, EditorSelectionRange(1, 1))
    gate.recordSuccessfulMutation(SuggestionMutation.CHARACTER, EditorSelectionRange(2, 2))

    assertTrue(gate.recordSelectionUpdate(EditorSelectionRange(1, 1)))
    assertTrue(gate.recordSelectionUpdate(EditorSelectionRange(2, 2)))
    assertTrue(gate.allowsSuggestionsAt(EditorSelectionRange(2, 2)))
}
```

- [ ] **Step 2: Ajouter les tests service RED avec des corps complets**

```kotlin
@Test
fun `existing editor text shows no suggestions before a local edit`() {
    val connection = EditableInputConnection(context(), "bo", 2)
    val (service, root) = startRootService(connection)
    assertTrue(root.railView.suggestionViews().isEmpty())
    service.onDestroy()
}

@Test
fun `typing a letter enables prefix suggestions`() {
    val connection = EditableInputConnection(context(), "b", 1)
    val (service, root) = startRootService(connection)
    root.keyboardView.onKeyChar?.invoke("o")
    assertTrue(root.railView.suggestionViews().map { it.text.toString() }.contains("bon"))
    service.onDestroy()
}

@Test
fun `rejected character does not enable suggestions`() {
    val connection = RejectingCommitInputConnection(context(), "b", 1)
    val (service, root) = startRootService(connection)
    root.keyboardView.onKeyChar?.invoke("o")
    assertTrue(root.railView.suggestionViews().isEmpty())
    service.onDestroy()
}

@Test
fun `accepted space enables context but newline and rejected space do not`() {
    val accepted = EditableInputConnection(context(), "je", 2)
    val (service, root) = startRootService(accepted)
    root.keyboardView.onKeySpace?.invoke()
    assertEquals(listOf("suis", "vais", "veux"), root.railView.suggestionViews().map { it.text.toString() })
    service.onDestroy()

    val rejected = RejectingCommitInputConnection(context(), "je", 2)
    val (rejectedService, rejectedRoot) = startRootService(rejected)
    rejectedRoot.keyboardView.onKeySpace?.invoke()
    assertTrue(rejectedRoot.railView.suggestionViews().isEmpty())
    rejectedService.onDestroy()
}

@Test
fun `deleting to empty and reopening input view clear suggestions`() {
    val connection = EditableInputConnection(context(), "b", 1)
    val (service, root) = startRootService(connection)
    root.keyboardView.onKeyChar?.invoke("o")
    assertTrue(root.railView.suggestionViews().isNotEmpty())
    root.keyboardView.onKeyDelete?.invoke()
    root.keyboardView.onKeyDelete?.invoke()
    assertTrue(root.railView.suggestionViews().isEmpty())

    connection.replaceAll("bo", 2)
    root.keyboardView.onKeyChar?.invoke("n")
    service.onStartInputView(editorInfo(), true)
    assertTrue(root.railView.suggestionViews().isEmpty())
    service.onDestroy()
}

@Test
fun `private field and external selection keep the rail empty`() {
    val connection = EditableInputConnection(context(), "b", 1)
    val service = testService(connection).apply {
        testEditorInfo = editorInfo(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
    }
    val root = createRootAndStart(service, info = requireNotNull(service.testEditorInfo))
    root.keyboardView.onKeyChar?.invoke("o")
    assertTrue(root.railView.suggestionViews().isEmpty())

    connection.select(0)
    service.onUpdateSelection(2, 2, 0, 0, -1, -1)
    assertTrue(root.railView.suggestionViews().isEmpty())
    service.onDestroy()
}
```

Renommer l’ancien test `external selection changes clear and refresh suggestions from live text` en `external selection clears suggestions until the next local edit` ; son assertion finale devient `assertTrue(root.railView.suggestionViews().isEmpty())`. Les tests candidat démarrent avec `b`, invoquent `onKeyChar("o")`, puis cliquent la vue native `bon`.

- [ ] **Step 3: Vérifier le RED ciblé**

Run: `./gradlew testDebugUnitTest --tests '*SuggestionPolicyTest' --tests '*JefeKeyboardServiceTest' --no-daemon`

Expected: FAIL à la compilation sur les nouveaux types et échecs des comportements de démarrage actuels.

- [ ] **Step 4: Implémenter la policy et le gate purs**

```kotlin
internal data class EditorSelectionRange(val start: Int, val end: Int) {
    val isCollapsed: Boolean get() = start == end
}

internal enum class SuggestionMutation { CHARACTER, SPACE, DELETE, SUGGESTION, NON_SENSITIVE_PASTE }

internal class SuggestionSessionGate {
    private var eligible = false
    private var sensitivePasteTaint = false
    private val expectedSelections = ArrayDeque<EditorSelectionRange>()

    fun startSession() {
        sensitivePasteTaint = false
        invalidate()
    }

    fun invalidate() { eligible = false; expectedSelections.clear() }
    fun taintForSession() { sensitivePasteTaint = true; invalidate() }

    fun recordSuccessfulMutation(mutation: SuggestionMutation, selection: EditorSelectionRange?) {
        val supported = when (mutation) {
            SuggestionMutation.CHARACTER,
            SuggestionMutation.SPACE,
            SuggestionMutation.DELETE,
            SuggestionMutation.SUGGESTION,
            SuggestionMutation.NON_SENSITIVE_PASTE,
            -> true
        }
        eligible = !sensitivePasteTaint && supported && selection?.isCollapsed == true
        if (selection == null || expectedSelections.size == MAX_PENDING_SELECTIONS) {
            invalidate()
            return
        }
        expectedSelections.addLast(selection)
    }

    fun recordSelectionUpdate(selection: EditorSelectionRange): Boolean {
        val matchIndex = expectedSelections.indexOf(selection)
        if (matchIndex < 0) {
            invalidate()
            return false
        }
        repeat(matchIndex + 1) { expectedSelections.removeFirst() }
        return true
    }

    fun allowsSuggestionsAt(selection: EditorSelectionRange): Boolean = eligible && selection.isCollapsed

    private companion object { const val MAX_PENDING_SELECTIONS = 64 }
}

internal data class SuggestionPolicyInput(
    val textBeforeCursor: String?,
    val selectionCollapsed: Boolean,
    val localMutationEligible: Boolean,
    val allowSuggestions: Boolean,
)

internal object SuggestionPolicy {
    fun contextOrNull(input: SuggestionPolicyInput): TextContext? {
        val text = input.textBeforeCursor ?: return null
        if (!input.selectionCollapsed || !input.localMutationEligible || !input.allowSuggestions) return null
        val lastNonWhitespace = text.indexOfLast { !it.isWhitespace() }
        if (lastNonWhitespace < 0) return null
        val terminal = text[lastNonWhitespace]
        if (!terminal.isLetter() && terminal != '\'' && terminal != '’') return null
        val context = TextContextParser.parse(text)
        val hasPrefix = context.currentWord.any(Char::isLetter)
        val trailing = text.substring(lastNonWhitespace + 1)
        val hasContext = trailing.isNotEmpty() && trailing.all { it == ' ' } &&
            !context.lastWord.isNullOrBlank()
        return context.takeIf { hasPrefix || hasContext }
    }
}
```

- [ ] **Step 5: N’enregistrer que les mutations acceptées par l’éditeur**

Ajouter les helpers exacts suivants au service :

```kotlin
private val suggestionGate = SuggestionSessionGate()

private fun currentRange(connection: InputConnection): EditorSelectionRange? {
    val extracted = captureExtractedSelection(connection) ?: return null
    return EditorSelectionRange(
        extracted.absoluteSelectionStart,
        extracted.absoluteSelectionEnd,
    )
}

private fun recordSuccessfulLocalMutation(
    connection: InputConnection,
    mutation: SuggestionMutation,
) {
    suggestionGate.recordSuccessfulMutation(mutation, currentRange(connection))
    updateSuggestions()
}

private fun invalidateSuggestions() {
    suggestionGate.invalidate()
    suggestionSnapshot = null
    setSuggestions(emptyList())
}

private fun updateSuggestions() {
    val connection = currentInputConnection ?: return invalidateSuggestions()
    val selection = currentRange(connection) ?: return invalidateSuggestions()
    val textBeforeCursor = connection.getTextBeforeCursor(MAX_TEXT_CONTEXT, 0)?.toString()
    val context = SuggestionPolicy.contextOrNull(
        SuggestionPolicyInput(
            textBeforeCursor = textBeforeCursor,
            selectionCollapsed = selection.isCollapsed,
            localMutationEligible = suggestionGate.allowsSuggestionsAt(selection),
            allowSuggestions = editorPrivacy.allowSuggestions,
        ),
    ) ?: return invalidateSuggestions()
    val values = predictor.suggest(context.currentWord, context.lastWord)
    val absoluteCursor = captureCandidateCursor(connection, context.currentWord)
    suggestionSnapshot = if (values.isEmpty() || absoluteCursor == null) null else SuggestionSnapshot(
        sessionGeneration,
        connection,
        requireNotNull(textBeforeCursor),
        absoluteCursor,
        values,
    )
    setSuggestions(if (suggestionSnapshot == null) emptyList() else values)
}
```

Les branches d’édition deviennent :

```kotlin
private fun handleChar(char: String) {
    val connection = currentInputConnection ?: return
    if (connection.commitText(char, 1)) {
        recordSuccessfulLocalMutation(connection, SuggestionMutation.CHARACTER)
    } else invalidateSuggestions()
}

private fun handleSpace() {
    val connection = currentInputConnection ?: return
    if (connection.commitText(" ", 1)) {
        recordSuccessfulLocalMutation(connection, SuggestionMutation.SPACE)
    } else invalidateSuggestions()
}

private fun handleDelete() {
    val connection = currentInputConnection ?: return
    val success = if (!connection.getSelectedText(0).isNullOrEmpty()) {
        connection.commitText("", 1)
    } else {
        connection.deleteSurroundingTextInCodePoints(1, 0)
    }
    if (success) recordSuccessfulLocalMutation(connection, SuggestionMutation.DELETE)
    else invalidateSuggestions()
}
```

Après le `commitText("$word ", 1)` accepté du candidat, appeler `recordSuccessfulLocalMutation(..., SUGGESTION)` ; en cas de refus, restaurer la sélection puis `invalidateSuggestions()`. Enter textuel, dictée et traduction acceptées appellent `invalidateSuggestions()`.

```kotlin
override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
    super.onStartInput(info, restarting)
    stopRecording(launchTranscription = false)
    resetSession()
    pendingEnterAction = resolveEnterAction(info?.imeOptions)
    editorPrivacy = EditorPrivacyPolicy.evaluate(info)
    suggestionGate.startSession()
    keyboardView?.let { view ->
        view.enterAction = pendingEnterAction
        view.isRecording = false
        view.remoteActionsEnabled = editorPrivacy.allowTranslation || editorPrivacy.allowDictation
    }
    invalidateSuggestions()
}

override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
    super.onStartInputView(info, restarting)
    keyboardView?.enterAction = pendingEnterAction
    invalidateSuggestions()
}

override fun onUpdateSelection(
    oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
    candidatesStart: Int, candidatesEnd: Int,
) {
    super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    val range = EditorSelectionRange(newSelStart, newSelEnd)
    if (!suggestionGate.recordSelectionUpdate(range)) invalidateSuggestions()
}
```

- [ ] **Step 6: Rendre micro et traduction réellement inertes en privé**

Compléter la propriété `KeyboardView.remoteActionsEnabled` introduite en Task 3 avec le filtre :

```kotlin
var remoteActionsEnabled: Boolean = true
    set(value) { field = value; invalidate() }

private fun remoteAllowed(action: KeyAction): Boolean =
    remoteActionsEnabled || (action != KeyAction.MIC && action != KeyAction.TRANSLATE)
```

`hitTest` filtre avec `remoteAllowed`; `handleKey` retourne `false` avant callback pour MIC/TRANSLATE lorsque faux ; `drawKey` applique alpha `0.38f` à ces deux glyphes. Le service revalide `editorPrivacy.allowDictation` dans `startRecording` et `editorPrivacy.allowTranslation` dans `translateSelection` avant permission ou réseau.

```kotlin
@Test
fun `private editor never starts translation or dictation`() {
    val calls = AtomicInteger()
    val connection = EditableInputConnection(context(), "secret", 0, 6)
    val service = testService(connection).apply {
        translation = { calls.incrementAndGet(); RemoteResult.Success("x") }
        transcription = { calls.incrementAndGet(); RemoteResult.Success("x") }
    }
    val info = editorInfo(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    val root = createRootAndStart(service, info = info)
    root.keyboardView.onTranslateClick?.invoke()
    root.keyboardView.onMicClick?.invoke()
    drainMainLooper()
    assertEquals(0, calls.get())
    service.onDestroy()
}
```

- [ ] **Step 7: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*SuggestionPolicyTest' --tests '*EditorPrivacyPolicyTest' --tests '*JefeKeyboardServiceTest' --tests '*KeyboardViewTest' --no-daemon`

Expected: PASS ; `je ` ne propose rien au démarrage mais `je` + espace local produit `suis`, `vais`, `veux`.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/SuggestionPolicy.kt app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt app/src/test/java/ovh/jefe/keyboard/SuggestionPolicyTest.kt app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt
git commit -m "fix: gate suggestions on safe local edits"
```

### Task 5: Cycle persistant, anti-double-appui et retry de traduction

**Files:**
- Modify: `app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/KeyboardViewTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/KeyboardRailViewTest.kt`

**Interfaces:**
- Consumes: `TranslationFeedback`, `TopRailInputs`, `EditorPrivacyState`, `KeyboardRootView`.
- Produces: `translationJob`, `translationFeedbackJob`, `failedTranslationSelection`, `cancelTranslation()` et le cycle visible `Loading -> Success/Error -> Idle`.
- Contract for clipboard plan: `railInputs.clipboardPrompt` reste mémorisé lorsque la traduction prend la priorité.

- [ ] **Step 1: Écrire le test RED du Loading immédiat et du verrou**

```kotlin
@Test
fun `translation stays visible ignores duplicate taps and succeeds only after commit`() {
    val connection = EditableInputConnection(context(), "bonjour", 0, 7)
    val delayed = DelayedRemoteResult("hello")
    val calls = AtomicInteger()
    val service = testService(connection).apply {
        translation = {
            calls.incrementAndGet()
            delayed.complete(it)
        }
    }
    val root = createRootAndStart(service)

    root.keyboardView.onTranslateClick?.invoke()
    assertEquals(TopRailState.Translation(TranslationFeedback.Loading), root.railView.state)
    assertTrue(delayed.started.await(5, TimeUnit.SECONDS))
    root.keyboardView.onTranslateClick?.invoke()
    assertEquals(1, calls.get())

    delayed.release.countDown()
    idleMainLooperUntil { connection.text() == "hello" }
    assertEquals(TopRailState.Translation(TranslationFeedback.Success), root.railView.state)
    service.onUpdateSelection(0, 7, 5, 5, -1, -1)
    assertEquals(TopRailState.Translation(TranslationFeedback.Success), root.railView.state)
    shadowOf(Looper.getMainLooper()).idleFor(1_200, TimeUnit.MILLISECONDS)
    assertFalse(root.railView.state is TopRailState.Translation)
    service.onDestroy()
}
```

- [ ] **Step 2: Ajouter les tests RED d’annulation, erreur et retry**

```kotlin
@Test
fun `selection move cancels loading and a stale result cannot commit`() {
    val connection = EditableInputConnection(context(), "bonjour monde", 0, 7)
    val delayed = DelayedRemoteResult("hello")
    val service = testService(connection).apply { translation = delayed::complete }
    val root = createRootAndStart(service)

    root.keyboardView.onTranslateClick?.invoke()
    assertTrue(delayed.started.await(5, TimeUnit.SECONDS))
    connection.select(8, 13)
    service.onUpdateSelection(0, 7, 8, 13, -1, -1)
    assertFalse(root.railView.state is TopRailState.Translation)

    delayed.release.countDown()
    drainMainLooper()
    assertEquals("bonjour monde", connection.text())
    assertFalse(root.railView.state is TopRailState.Translation)
    service.onDestroy()
}

@Test
fun `remote failure stays visible retries once and then succeeds`() {
    val connection = EditableInputConnection(context(), "bonjour", 0, 7)
    val replies = ArrayDeque<RemoteResult<String>>().apply {
        add(RemoteResult.Failure("serveur indisponible"))
        add(RemoteResult.Success("hello"))
    }
    val calls = AtomicInteger()
    val service = testService(connection).apply {
        translation = { calls.incrementAndGet(); replies.removeFirst() }
    }
    val root = createRootAndStart(service)

    root.keyboardView.onTranslateClick?.invoke()
    idleMainLooperUntil { root.railView.state == TopRailState.Translation(TranslationFeedback.Error) }
    assertTrue(ShadowToast.getTextOfLatestToast().contains("serveur indisponible"))
    root.railView.retryButton().performClick()
    idleMainLooperUntil { connection.text() == "hello" }

    assertEquals(2, calls.get())
    assertEquals(TopRailState.Translation(TranslationFeedback.Success), root.railView.state)
    service.onDestroy()
}

@Test
fun `retry is discarded when the failed selection moved`() {
    val connection = EditableInputConnection(context(), "bonjour monde", 0, 7)
    val calls = AtomicInteger()
    val service = testService(connection).apply {
        translation = { calls.incrementAndGet(); RemoteResult.Failure("indisponible") }
    }
    val root = createRootAndStart(service)
    root.keyboardView.onTranslateClick?.invoke()
    idleMainLooperUntil { root.railView.state == TopRailState.Translation(TranslationFeedback.Error) }
    val retry = root.railView.retryButton()

    connection.select(8, 13)
    service.onUpdateSelection(0, 7, 8, 13, -1, -1)
    retry.performClick()
    drainMainLooper()

    assertEquals(1, calls.get())
    assertFalse(root.railView.state is TopRailState.Translation)
    service.onDestroy()
}

@Test
fun `editor rejection shows error and never success`() {
    val connection = RejectingCommitInputConnection(context(), "bonjour", 0, 7)
    val service = testService(connection).apply {
        translation = { RemoteResult.Success("hello") }
    }
    val root = createRootAndStart(service)

    root.keyboardView.onTranslateClick?.invoke()
    idleMainLooperUntil { connection.commitAttempts.isNotEmpty() }

    assertEquals("bonjour", connection.text())
    assertEquals(TopRailState.Translation(TranslationFeedback.Error), root.railView.state)
    assertTrue(ShadowToast.getTextOfLatestToast().contains("éditeur", ignoreCase = true))
    service.onDestroy()
}

@Test
fun `unexpected remote exception becomes a retryable error`() {
    val connection = EditableInputConnection(context(), "bonjour", 0, 7)
    val service = testService(connection).apply {
        translation = { throw IOException("private backend detail") }
    }
    val root = createRootAndStart(service)
    root.keyboardView.onTranslateClick?.invoke()
    idleMainLooperUntil { root.railView.state == TopRailState.Translation(TranslationFeedback.Error) }
    assertFalse(ShadowToast.getTextOfLatestToast().contains("private backend detail"))
    assertTrue(root.railView.retryButton().isEnabled)
    service.onDestroy()
}

@Test
fun `retry cancels the prior error timer before showing a new loading`() {
    val connection = EditableInputConnection(context(), "bonjour", 0, 7)
    val delayed = DelayedRemoteResult("hello")
    val calls = AtomicInteger()
    val service = testService(connection).apply {
        translation = {
            if (calls.getAndIncrement() == 0) RemoteResult.Failure("indisponible")
            else delayed.complete(it)
        }
    }
    val root = createRootAndStart(service)
    root.keyboardView.onTranslateClick?.invoke()
    idleMainLooperUntil { root.railView.state == TopRailState.Translation(TranslationFeedback.Error) }
    root.railView.retryButton().performClick()
    assertTrue(delayed.started.await(5, TimeUnit.SECONDS))
    shadowOf(Looper.getMainLooper()).idleFor(3_000, TimeUnit.MILLISECONDS)
    assertEquals(TopRailState.Translation(TranslationFeedback.Loading), root.railView.state)
    delayed.release.countDown()
    service.onDestroy()
}

@Test
fun `every service boundary cancels a pending translation`() {
    listOf<(TestJefeKeyboardService) -> Unit>(
        { it.hideWindow() },
        { it.onFinishInputView(false) },
        { it.onStartInput(editorInfo(), false) },
        { it.onFinishInput() },
        { it.onDestroy() },
    ).forEach(::assertPendingTranslationCancelled)
}

private fun assertPendingTranslationCancelled(stop: (TestJefeKeyboardService) -> Unit) {
    val connection = EditableInputConnection(context(), "bonjour", 0, 7)
    val delayed = DelayedRemoteResult("hello")
    val service = testService(connection).apply { translation = delayed::complete }
    val root = createRootAndStart(service)
    root.keyboardView.onTranslateClick?.invoke()
    assertTrue(delayed.started.await(5, TimeUnit.SECONDS))

    stop(service)
    delayed.release.countDown()
    drainMainLooper()

    assertEquals("bonjour", connection.text())
    assertFalse(root.railView.state is TopRailState.Translation)
    service.onDestroy()
}
```

Ajouter à `KeyboardRailViewTest` seulement `retryButton()` et les assertions de structure/callback de l’état Error. Le délai appartient au service : ajouter à `JefeKeyboardServiceTest` un test qui produit Error, avance le looper de 2 999 ms (Error reste), puis 1 ms (retour à l’état suivant du rail).

- [ ] **Step 3: Vérifier le RED ciblé**

Run: `./gradlew testDebugUnitTest --tests '*JefeKeyboardServiceTest' --tests '*KeyboardRailViewTest' --tests '*KeyboardViewTest' --no-daemon`

Expected: FAIL car l’ancien service n’expose aucun état persistant et lance plusieurs jobs.

- [ ] **Step 4: Centraliser les entrées du rail dans le service**

```kotlin
private var translationJob: Job? = null
private var translationFeedbackJob: Job? = null
private var failedTranslationSelection: SelectionSnapshot? = null
private var expectedTranslationSelectionUpdate: EditorSelectionRange? = null
private var translationAttemptId = 0L

private fun setTranslationFeedback(feedback: TranslationFeedback) {
    railInputs = railInputs.copy(translation = feedback)
    keyboardView?.isTranslating = feedback == TranslationFeedback.Loading
    renderRail()
}
```

Réutiliser `railInputs`, `renderRail()` et `setSuggestions()` introduits en Task 3 ; ne pas créer une seconde source d’état.

- [ ] **Step 5: Créer le job en LAZY avant son démarrage**

```kotlin
private fun launchTranslation(
    connection: InputConnection,
    selection: SelectionSnapshot,
) {
    if (translationJob?.isActive == true || !editorPrivacy.allowTranslation) return
    translationFeedbackJob?.cancel()
    translationFeedbackJob = null
    val generation = sessionGeneration
    val attemptId = ++translationAttemptId
    failedTranslationSelection = selection
    setTranslationFeedback(TranslationFeedback.Loading)
    val job = sessionScope.launch(start = CoroutineStart.LAZY) {
        val result = try {
            translateText(selection.selectedText)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            RemoteResult.Failure("La traduction a échoué. Réessayez.")
        }
        applyTranslationResult(attemptId, generation, connection, selection, result)
    }
    translationJob = job
    job.start()
}
```

`translateSelection()` conserve les validations actuelles puis appelle `launchTranslation`. Le job est assigné avant tout code réseau ; un second appui voit donc toujours le verrou.

- [ ] **Step 6: Appliquer le résultat uniquement à la tentative vivante**

```kotlin
private fun applyTranslationResult(
    attemptId: Long,
    generation: Long,
    connection: InputConnection,
    selection: SelectionSnapshot,
    result: RemoteResult<String>,
) {
    if (attemptId != translationAttemptId) return
    translationJob = null
    val current = captureSelection(connection, connection.getSelectedText(0)?.toString())
    if (!isCurrentSession(generation, connection) || current != selection) {
        clearTranslationFeedback()
        return
    }
    when (result) {
        is RemoteResult.Success -> when {
            result.value.isBlank() -> showTranslationError(
                "Réponse de traduction vide. Vérifiez la compatibilité du serveur.",
            )
            connection.commitText(result.value, 1) -> {
                failedTranslationSelection = null
                expectedTranslationSelectionUpdate = currentRange(connection)
                suggestionGate.invalidate()
                setSuggestions(emptyList())
                setTranslationFeedback(TranslationFeedback.Success)
                scheduleTranslationClear(1_200L)
            }
            else -> {
                showEditorFailure()
                showTranslationError("L’éditeur a refusé la traduction.")
            }
        }
        is RemoteResult.Failure -> showTranslationError(result.message)
    }
}

private fun showTranslationError(message: String) {
    setTranslationFeedback(TranslationFeedback.Error)
    showRemoteFailure(message)
    scheduleTranslationClear(3_000L)
}

private fun scheduleTranslationClear(delayMillis: Long) {
    translationFeedbackJob?.cancel()
    translationFeedbackJob = sessionScope.launch {
        delay(delayMillis)
        clearTranslationFeedback()
    }
}

private fun clearTranslationFeedback() {
    translationFeedbackJob?.cancel()
    translationFeedbackJob = null
    failedTranslationSelection = null
    expectedTranslationSelectionUpdate = null
    setTranslationFeedback(TranslationFeedback.Idle)
}
```

- [ ] **Step 7: Implémenter retry et annulation exhaustive**

```kotlin
private fun retryTranslation() {
    val expected = failedTranslationSelection ?: return
    val connection = currentInputConnection ?: return clearTranslationFeedback()
    val current = captureSelection(connection, connection.getSelectedText(0)?.toString())
    if (current != expected) return clearTranslationFeedback()
    translationFeedbackJob?.cancel()
    setTranslationFeedback(TranslationFeedback.Idle)
    launchTranslation(connection, expected)
}

private fun cancelTranslation() {
    translationAttemptId += 1
    translationJob?.cancel()
    translationJob = null
    clearTranslationFeedback()
}
```

`clearTranslationFeedback()` annule le timer, vide la sélection retry, met `Idle` et rend le bouton. Ajouter `cancelTranslation()` comme première instruction de `resetSession`, `hideWindow`, `onFinishInputView`, `onFinishInput` et `onDestroy`. Dans `onUpdateSelection`, comparer d’abord la plage au `expectedTranslationSelectionUpdate` produit par le commit réussi : une correspondance est consommée sans effacer `Success`; toute autre mise à jour pendant Loading/Error/Success appelle `cancelTranslation()`. Les overrides conservent ensuite leurs appels existants à `stopRecording` et `super`. Ne jamais convertir `CancellationException` en erreur UI.

- [ ] **Step 8: Représenter l’activité sans accepter de clic supplémentaire**

Ajouter `KeyboardView.isTranslating`. En Loading, le bouton traduction est Bleu plume et décrit `Traduction en cours`, mais `hitTest`/`handleKey` retournent faux pour `TRANSLATE`. `KeyboardRailView.Error` reste un bouton retry natif ; `Loading` et `Success` ne sont pas cliquables.

- [ ] **Step 9: Vérifier le GREEN et committer**

Run: `./gradlew testDebugUnitTest --tests '*JefeKeyboardServiceTest' --tests '*TranslateClientTest' --tests '*KeyboardRailViewTest' --tests '*KeyboardViewTest' --no-daemon`

Expected: PASS ; sélection absolue, espaces exacts, annulation de session et refus éditeur restent verts.

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt app/src/test/java/ovh/jefe/keyboard/KeyboardViewTest.kt app/src/test/java/ovh/jefe/keyboard/KeyboardRailViewTest.kt
git commit -m "feat: show persistent translation feedback"
```

### Task 6: Thème Bleu d’encre clair/sombre et gate visuel

**Files:**
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values-night/colors.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/drawable/bg_keyboard_rail.xml`
- Modify: `app/src/main/res/drawable/bg_rail_control.xml`
- Modify: `app/src/main/res/drawable/rail_divider.xml`
- Modify: `app/src/main/res/drawable/bg_settings_header.xml`
- Modify: `app/src/main/res/layout/settings_activity.xml`
- Modify: `app/src/main/res/layout/settings_header.xml`
- Modify: `app/src/main/java/ovh/jefe/keyboard/KeyboardRailView.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/SettingsActivity.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/InkThemeTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/KeyboardRailViewTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/KeyboardRootViewTest.kt`
- Modify: `app/src/test/java/ovh/jefe/keyboard/SettingsActivityTest.kt`

**Interfaces:**
- Produces: tokens réutilisés par le presse-papiers : `paper`, `ink`, `mist`, `slate`, `secondary_text`, `pen_blue`, `recording_red`, `elevated_surface`, `divider`, `on_accent`.

- [ ] **Step 1: Écrire les assertions RED de palette et contraste**

```kotlin
@Test
fun `approved light and dark palettes meet text contrast`() {
    val lightPaper = color(lightContext, R.color.paper)
    val lightInk = color(lightContext, R.color.ink)
    val lightSecondary = color(lightContext, R.color.secondary_text)
    assertEquals("#F4F6F5", lightPaper.toHexRgb())
    assertEquals("#142934", lightInk.toHexRgb())
    assertTrue(ColorUtils.calculateContrast(lightInk, lightPaper) >= 4.5)
    assertTrue(ColorUtils.calculateContrast(lightSecondary, lightPaper) >= 4.5)

    val darkSurface = color(darkContext, R.color.keyboard_surface)
    val darkText = color(darkContext, R.color.key_text)
    val darkRecording = color(darkContext, R.color.recording_red)
    assertEquals("#101719", darkSurface.toHexRgb())
    assertEquals("#101719", color(darkContext, R.color.paper).toHexRgb())
    assertEquals("#EAF0EF", color(darkContext, R.color.ink).toHexRgb())
    assertTrue(ColorUtils.calculateContrast(darkText, darkSurface) >= 4.5)
    assertTrue(ColorUtils.calculateContrast(darkRecording, darkSurface) >= 4.5)
}
```

`InkThemeTest` construit les contextes `UI_MODE_NIGHT_NO/YES`, fournit `color()` et `toHexRgb()` sans dépendre d’un screenshot. Ajouter aux tests de rail les contrastes de texte, pressed, Loading/Error et prompt sensible.

- [ ] **Step 2: Vérifier le RED**

Run: `./gradlew testDebugUnitTest --tests '*InkThemeTest' --tests '*KeyboardRailViewTest' --tests '*SettingsActivityTest' --no-daemon`

Expected: FAIL sur les ressources absentes et les anciennes couleurs.

- [ ] **Step 3: Définir les tokens clairs exacts**

```xml
<color name="paper">#F4F6F5</color>
<color name="ink">#142934</color>
<color name="mist">#D7E0E0</color>
<color name="slate">#68808A</color>
<color name="secondary_text">#49616B</color>
<color name="pen_blue">#2E5C9A</color>
<color name="recording_red">#C84B48</color>
<color name="elevated_surface">#E8EEED</color>
<color name="divider">#C9D5D4</color>
<color name="on_accent">#FFFFFF</color>
<color name="on_recording">#FFFFFF</color>
<color name="keyboard_surface">@color/paper</color>
<color name="key_bg">#EEF2F1</color>
<color name="key_bg_special">#E3EAE9</color>
<color name="key_text">@color/ink</color>
<color name="key_pressed">@color/mist</color>
<color name="key_outline">@color/divider</color>
<color name="suggestion_bg">@android:color/transparent</color>
<color name="suggestion_pressed">@color/mist</color>
<color name="suggestion_text">@color/ink</color>
<color name="suggestion_outline">@color/divider</color>
<color name="action_pressed">@color/pen_blue</color>
<color name="on_action">@color/on_accent</color>
<color name="mic_icon">@color/ink</color>
<color name="mic_pressed_icon">@color/on_recording</color>
<color name="settings_surface">@color/paper</color>
<color name="settings_toolbar_surface">@color/ink</color>
<color name="settings_toolbar_text">#FFFFFF</color>
<color name="settings_header_surface">@color/elevated_surface</color>
<color name="settings_header_outline">@color/divider</color>
<color name="settings_success">@color/pen_blue</color>
<color name="settings_pending">@color/secondary_text</color>
```

Les alias existants deviennent exactement `signal_blue=@color/pen_blue`, `primary=@color/pen_blue`, `private_teal=@color/pen_blue`, `porcelain=@color/paper`, `night=#101719` et `settings_on_ink=@color/settings_toolbar_text`.

- [ ] **Step 4: Définir les tokens sombres exacts**

```xml
<color name="paper">#101719</color>
<color name="ink">#EAF0EF</color>
<color name="mist">#314249</color>
<color name="slate">#829596</color>
<color name="keyboard_surface">@color/paper</color>
<color name="key_bg">#1A252A</color>
<color name="key_bg_special">#223038</color>
<color name="key_text">@color/ink</color>
<color name="secondary_text">#AAB8B7</color>
<color name="pen_blue">#7DA9E8</color>
<color name="recording_red">#FF8A86</color>
<color name="elevated_surface">#223038</color>
<color name="divider">#314249</color>
<color name="key_pressed">#314249</color>
<color name="on_accent">#101719</color>
<color name="on_recording">#101719</color>
<color name="key_outline">@color/divider</color>
<color name="suggestion_bg">@android:color/transparent</color>
<color name="suggestion_pressed">@color/mist</color>
<color name="suggestion_text">@color/ink</color>
<color name="suggestion_outline">@color/divider</color>
<color name="action_pressed">@color/pen_blue</color>
<color name="on_action">@color/on_accent</color>
<color name="mic_icon">@color/ink</color>
<color name="mic_pressed_icon">@color/on_recording</color>
<color name="settings_surface">@color/paper</color>
<color name="settings_toolbar_surface">#1A252A</color>
<color name="settings_toolbar_text">@color/ink</color>
<color name="settings_header_surface">@color/elevated_surface</color>
<color name="settings_header_outline">@color/divider</color>
<color name="settings_success">@color/pen_blue</color>
<color name="settings_pending">@color/secondary_text</color>
```

`Theme.JefeKeyboard` reste `Theme.Material3.DayNight.NoActionBar`. Remplacer toolbar/status/navigation/window par `settings_toolbar_surface`, `settings_toolbar_text` et `settings_surface`; aucun réglage de thème manuel n’est ajouté.

- [ ] **Step 5: Appliquer les rôles aux touches, rail et réglages**

Créer et appliquer le fond exact :

```xml
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <item><shape><solid android:color="@color/keyboard_surface" /></shape></item>
    <item android:gravity="bottom" android:height="1dp">
        <shape><solid android:color="@color/pen_blue" /></shape>
    </item>
</layer-list>
```

Dans `KeyboardRailView.init`, appeler `setBackgroundResource(R.drawable.bg_keyboard_rail)`. Teinter les deux `ImageButton` avec `suggestion_text`; suggestions/status utilisent `key_text`, aperçus `secondary_text`, action/type `pen_blue`. `bg_rail_control` pressed utilise `suggestion_pressed`; `rail_divider` utilise `divider`. Dans `KeyboardView`, remplacer les résolutions d’anciennes couleurs par les alias ci-dessus et employer `on_recording` sur le micro actif.

Dans `settings_activity.xml`, toolbar background devient `settings_toolbar_surface` et title `settings_toolbar_text`. Dans `settings_header.xml`, les deux textes 12/13sp passent de `slate` à `secondary_text`. `bg_settings_header` utilise `settings_header_surface` + `settings_header_outline`. `SettingsActivity.renderSetup` utilise `settings_pending` ou `settings_success`, jamais `slate` pour le texte normal.

- [ ] **Step 6: Générer et inspecter les captures natives**

Run:

```bash
VISUAL_OUTPUT_DIR=/tmp/jefe-keyboard-rail ./gradlew testDebugUnitTest --tests '*KeyboardRootViewTest' --tests '*SettingsActivityTest' --no-daemon
```

Produire puis inspecter avec l’outil d’image local :

- `/tmp/jefe-keyboard-rail/keyboard-empty-light.png`
- `/tmp/jefe-keyboard-rail/keyboard-suggestions-light.png`
- `/tmp/jefe-keyboard-rail/keyboard-translation-light.png`
- `/tmp/jefe-keyboard-rail/keyboard-empty-dark.png`
- `/tmp/jefe-keyboard-rail/keyboard-translation-dark.png`
- `/tmp/jefe-keyboard-rail/settings-light.png`
- `/tmp/jefe-keyboard-rail/settings-dark.png`

Rejeter toute capsule fantôme, texte tronqué, cible <44 dp, rendu blanc en sombre ou contraste insuffisant.

Ajouter à `KeyboardRootViewTest` deux tests `@GraphicsMode(NATIVE)`, l’un `@Config(qualifiers = "notnight")`, l’autre `@Config(qualifiers = "night")`; chacun parcourt `Empty/Suggestions/Loading`, appelle le helper `render(target, output.resolve(fileName))` déjà utilisé dans `KeyboardViewTest`, puis affirme `file.isFile && file.length() > 0`. Ajouter de même à `SettingsActivityTest` deux méthodes séparées `@Config(qualifiers = "notnight")` et `@Config(qualifiers = "night")` qui construisent l’activité **après** application du qualifier et écrivent explicitement `settings-light.png` et `settings-dark.png`. Aucun `createConfigurationContext` n’est utilisé pour construire une Activity ; le qualifier de chaque test est isolé automatiquement par Robolectric.

- [ ] **Step 7: Exécuter le gate complet propre**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon`

Expected: tests verts, lint 0 erreur, APK debug existant et non vide.

- [ ] **Step 8: Contrôler et committer**

```bash
git diff --check
git add app/src/main/java/ovh/jefe/keyboard/KeyboardRailView.kt app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt app/src/main/java/ovh/jefe/keyboard/SettingsActivity.kt app/src/main/res/values/colors.xml app/src/main/res/values-night/colors.xml app/src/main/res/values/themes.xml app/src/main/res/drawable/bg_keyboard_rail.xml app/src/main/res/drawable/bg_rail_control.xml app/src/main/res/drawable/rail_divider.xml app/src/main/res/drawable/bg_settings_header.xml app/src/main/res/layout/settings_activity.xml app/src/main/res/layout/settings_header.xml app/src/test/java/ovh/jefe/keyboard/InkThemeTest.kt app/src/test/java/ovh/jefe/keyboard/KeyboardRailViewTest.kt app/src/test/java/ovh/jefe/keyboard/KeyboardRootViewTest.kt app/src/test/java/ovh/jefe/keyboard/SettingsActivityTest.kt
git commit -m "style: apply the ink blue system theme"
```

Après le commit, relancer `git status --short` et `git log -6 --oneline`. Le worktree doit être propre avant le plan presse-papiers.
