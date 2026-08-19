# Jefe Keyboard Operational Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a reliable, polished French Android keyboard and a verified installable debug APK.

**Architecture:** Keep Android framework code at the service/view boundaries, derive typing context through a pure parser, and bind every background operation to an input-session generation. Replace the nested settings layout with one preference list under a compact header, and make build/test tooling reproducible from a clean checkout.

**Tech Stack:** Kotlin 2.0.21, Android Views, AndroidX Preference, Material 3, coroutines 1.8.1, OkHttp 4.12.0, JUnit 4.13.2, Robolectric 4.16.1, AndroidX Test JUnit 1.3.0, MockWebServer 4.12.0, Gradle 8.7, JDK 17.

**Spec:** `docs/superpowers/specs/2026-08-19-keyboard-reliability-design.md`

## Global Constraints

- Support Android API 24 through target API 34.
- Accept only absolute HTTPS service URLs; do not globally enable cleartext HTTP.
- Never expose API-key values in preference summaries or backups.
- A background result may edit text only in the input session and connection that launched it.
- Preserve French QWERTY, number/symbol modes, accents, suggestions, dictation, and translation.
- Use the palette and typography in the spec; every key touch target is at least 44dp.
- Write each regression test first, run it, and confirm the expected failure before implementation.
- Do not rewrite unrelated code or normalize unrelated worktree files solely for formatting.

---

### Task 1: Reproducible build and test harness

**Files:**
- Modify: `.gitignore`
- Modify: `.github/workflows/build.yml`
- Modify: `README.md`
- Modify: `build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `app/src/test/java/ovh/jefe/keyboard/FrenchPredictorTest.kt`

**Interfaces:**
- Consumes: existing single-module Android application.
- Produces: `./gradlew testDebugUnitTest`, `./gradlew lintDebug`, and `./gradlew assembleDebug` entry points used by every later task.

- [ ] **Step 1: Add the first behavior test**

```kotlin
package ovh.jefe.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class FrenchPredictorTest {
    @Test fun `context suggestions follow a completed French pronoun`() {
        assertEquals(listOf("suis", "vais", "veux"), FrenchPredictor().suggest("", "je"))
    }

    @Test fun `prefix suggestions are unique and limited to three`() {
        val result = FrenchPredictor().suggest("bo")
        assertEquals(result.distinct(), result)
        assertEquals(true, result.size <= 3)
        assertEquals(true, result.all { it.startsWith("bo") })
    }
}
```

- [ ] **Step 2: Run the test command and capture the expected infrastructure failure**

Run: `./gradlew testDebugUnitTest --no-daemon`

Expected: FAIL before tests start because `gradlew` is absent.

- [ ] **Step 3: Generate and commit the official Gradle 8.7 wrapper**

Generate the wrapper with Gradle 8.7, retain the official scripts/JAR, remove `gradle-wrapper.jar` from `.gitignore`, and add the official Gradle 8.7 binary distribution checksum to `gradle-wrapper.properties`.

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip
distributionSha256Sum=544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d
```

- [ ] **Step 4: Configure compatible unit-test dependencies**

```kotlin
testOptions { unitTests.isIncludeAndroidResources = true }

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test.ext:junit:1.3.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

Update the Kotlin Android plugin to `2.0.21`. Make CI call the committed wrapper directly and update README build instructions to `./gradlew assembleDebug` (Windows: `gradlew.bat assembleDebug`).

- [ ] **Step 5: Verify the harness is green**

Run: `./gradlew testDebugUnitTest --no-daemon`

Expected: PASS with both `FrenchPredictorTest` tests executed.

- [ ] **Step 6: Commit**

```bash
git add .gitignore .github/workflows/build.yml README.md build.gradle.kts app/build.gradle.kts gradlew gradlew.bat gradle/wrapper app/src/test/java/ovh/jefe/keyboard/FrenchPredictorTest.kt
git commit -m "build: make Android build reproducible"
```

### Task 2: Safe endpoints, HTTP clients, and private settings

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/RemoteResult.kt`
- Create: `app/src/main/java/ovh/jefe/keyboard/ServiceEndpoint.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/WhisperClient.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/TranslateClient.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/SettingsActivity.kt`
- Modify: `app/src/main/res/xml/preferences.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/test/java/ovh/jefe/keyboard/ServiceEndpointTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/TranslateClientTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/WhisperClientTest.kt`

**Interfaces:**
- Produces: `ServiceEndpoint.parse(raw: String): RemoteResult<HttpUrl>` and `RemoteResult<T>` (`Success(value)` / `Failure(message, cause)`).
- Produces: both HTTP clients return `RemoteResult<String>` and accept an optional `OkHttpClient` constructor argument for real MockWebServer tests.

- [ ] **Step 1: Write endpoint-validation tests**

```kotlin
class ServiceEndpointTest {
    @Test fun `accepts an absolute https base URL`() {
        val result = ServiceEndpoint.parse(" https://voice.example.test/base/ ")
        assertTrue(result is RemoteResult.Success)
        assertEquals("https://voice.example.test/base/", (result as RemoteResult.Success).value.toString())
    }

    @Test fun `rejects missing scheme malformed and cleartext URLs`() {
        listOf("voice.local", "not a url", "http://192.168.1.4:8080").forEach {
            assertTrue(ServiceEndpoint.parse(it) is RemoteResult.Failure)
        }
    }
}
```

- [ ] **Step 2: Run and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*ServiceEndpointTest' --no-daemon`

Expected: FAIL because `ServiceEndpoint` and `RemoteResult` do not exist.

- [ ] **Step 3: Implement endpoint parsing and typed results**

```kotlin
sealed interface RemoteResult<out T> {
    data class Success<T>(val value: T) : RemoteResult<T>
    data class Failure(val message: String, val cause: Throwable? = null) : RemoteResult<Nothing>
}

object ServiceEndpoint {
    fun parse(raw: String): RemoteResult<HttpUrl> {
        val parsed = raw.trim().toHttpUrlOrNull()
            ?: return RemoteResult.Failure("Adresse invalide. Utilisez une URL HTTPS complète.")
        return if (parsed.isHttps) RemoteResult.Success(parsed)
        else RemoteResult.Failure("Connexion non sécurisée refusée. Utilisez HTTPS.")
    }
}
```

- [ ] **Step 4: Write real HTTP-boundary tests**

Use `MockWebServer` to assert that translation posts `/translate` with literal JSON fields and Whisper posts `/v1/audio/transcriptions` as multipart. Add a malformed URL case asserting `RemoteResult.Failure` rather than an exception. Do not assert mock invocation counts; assert the recorded real HTTP request and returned client result.

- [ ] **Step 5: Run and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*ClientTest' --no-daemon`

Expected: FAIL because current clients return nullable strings and construct invalid requests outside their `try` blocks.

- [ ] **Step 6: Implement safe clients and settings validation**

Build endpoints with `HttpUrl.newBuilder().addPathSegments(...)`, wrap construction/execution/parsing in one `try`, and return actionable French failures. In `SettingsFragment`, reject invalid URL preference changes with a toast. For API-key preferences, remove `useSimpleSummaryProvider`, return `"Configurée"` or `"Non configurée"`, and bind password input/transformation. Set `android:allowBackup="false"`.

- [ ] **Step 7: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*ServiceEndpointTest' --tests '*ClientTest' --no-daemon`

Expected: PASS.

```bash
git add app/src/main app/src/test/java/ovh/jefe/keyboard/ServiceEndpointTest.kt app/src/test/java/ovh/jefe/keyboard/TranslateClientTest.kt app/src/test/java/ovh/jefe/keyboard/WhisperClientTest.kt
git commit -m "fix: validate private service connections"
```

### Task 3: Correct IME lifecycle, editing, and asynchronous ownership

**Files:**
- Create: `app/src/main/java/ovh/jefe/keyboard/TextContextParser.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/JefeKeyboardService.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/TextContextParserTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt`

**Interfaces:**
- Consumes: `RemoteResult<String>` from Task 2.
- Produces: `TextContextParser.parse(textBeforeCursor: CharSequence): TextContext(currentWord, lastWord)`.
- Produces: session-owned background jobs and safe input-connection commits.

- [ ] **Step 1: Write parser regression tests**

```kotlin
class TextContextParserTest {
    @Test fun `parses active and previous French words`() {
        assertEquals(TextContext("bon", "je"), TextContextParser.parse("je bon"))
    }

    @Test fun `ignores punctuation around the completed word`() {
        assertEquals(TextContext("", "bonjour"), TextContextParser.parse("bonjour, "))
    }

    @Test fun `keeps apostrophes and accented letters inside a token`() {
        assertEquals(TextContext("l'été", null), TextContextParser.parse("l'été"))
    }
}
```

- [ ] **Step 2: Run and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*TextContextParserTest' --no-daemon`

Expected: FAIL because the parser does not exist.

- [ ] **Step 3: Implement the pure parser**

Token characters are Unicode letters plus straight or typographic apostrophes. The active token exists only when the cursor immediately follows a token character. The preceding completed token is the nearest earlier token after punctuation and whitespace are ignored.

- [ ] **Step 4: Write service lifecycle/editing tests**

With Robolectric, cover these externally visible breaks:

```kotlin
@Test fun `starting input before creating the keyboard view does not crash`() {
    val service = Robolectric.buildService(JefeKeyboardService::class.java).create().get()
    service.onStartInput(EditorInfo(), false)
}
```

Add cases using a real/fake `BaseInputConnection` editable for: selected text is removed by Backspace; a candidate replaces only the live token; switching session invalidates a delayed result; `IME_ACTION_PREVIOUS` calls the editor action rather than inserting newline. Test the service behavior, not private fields.

- [ ] **Step 5: Run and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*JefeKeyboardServiceTest' --no-daemon`

Expected: FAIL on the cold-start crash and editing/session cases.

- [ ] **Step 6: Implement lifecycle and editing fixes**

- Store pending enter action in `onStartInput`; update the view only when initialized and from `onStartInputView`.
- Increment a session generation and cancel prior child jobs on start/finish.
- Derive suggestions and replacement length from `getTextBeforeCursor`, using `TextContextParser`.
- Delete a non-empty selection with `commitText("", 1)`; otherwise call `deleteSurroundingTextInCodePoints(1, 0)`.
- Capture generation and connection before network work; commit only if both still match, and revalidate translation selection.
- Own a `SupervisorJob`; cancel it in `onDestroy`.
- Release `MediaRecorder` in `finally` and delete the audio file in every failure/completion path.
- Support `IME_ACTION_PREVIOUS`.

- [ ] **Step 7: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*TextContextParserTest' --tests '*JefeKeyboardServiceTest' --no-daemon`

Expected: PASS.

```bash
git add app/src/main/java/ovh/jefe/keyboard app/src/test/java/ovh/jefe/keyboard/TextContextParserTest.kt app/src/test/java/ovh/jefe/keyboard/JefeKeyboardServiceTest.kt
git commit -m "fix: make input sessions safe and predictable"
```

### Task 4: Polish keyboard and onboarding UI

**Files:**
- Modify: `app/src/main/java/ovh/jefe/keyboard/KeyboardView.kt`
- Modify: `app/src/main/java/ovh/jefe/keyboard/SettingsActivity.kt`
- Modify: `app/src/main/res/layout/settings_activity.xml`
- Create: `app/src/main/res/layout/settings_header.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values-night/colors.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/xml/preferences.xml`
- Create: `app/src/main/res/drawable/bg_settings_header.xml`
- Create: `app/src/main/res/drawable/ic_launcher_legacy.xml`
- Create: `app/src/main/res/mipmap/ic_launcher.xml`
- Create: `app/src/test/java/ovh/jefe/keyboard/KeyboardViewTest.kt`
- Create: `app/src/test/java/ovh/jefe/keyboard/SettingsActivityTest.kt`

**Interfaces:**
- Consumes: safe service callbacks and validated preferences from Tasks 2–3.
- Produces: accessible key interactions, dynamic enter labels, one scrolling settings surface, and API 24 launcher fallback.

- [ ] **Step 1: Write view behavior tests**

Use Robolectric MotionEvents to verify: `enterAction` changes the rendered key definition to `Préc.`, `Suiv.`, `Envoyer`, or `OK`; a shifted long-press accent clears Shift; moving outside the pressed key before release emits no character; `performClick()` is reached for successful taps.

- [ ] **Step 2: Run and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*KeyboardViewTest' --no-daemon`

Expected: FAIL because action labels are hidden by the icon, accent Shift sticks, and move cancellation is absent.

- [ ] **Step 3: Implement the keyboard visual system**

Apply the spec palette. Draw the keyboard on Porcelain/Night, suggestions as three rounded capsules, ordinary keys with a quiet surface, special keys in a tinted surface, microphone in Private teal, recording in red, and action Enter in Signal blue with a white text label. Use `sans-serif-condensed` key labels, minimum 44dp rows, `performHapticFeedback`, `performClick`, and move-out cancellation. Invalidate/recompute when `enterAction` changes. Clear one-shot Shift after accent input.

- [ ] **Step 4: Write settings layout tests**

```kotlin
private fun countScrollViews(view: View): Int {
    val own = if (view is ScrollView) 1 else 0
    if (view !is ViewGroup) return own
    return own + (0 until view.childCount).sumOf { countScrollViews(view.getChildAt(it)) }
}

@Test fun `settings screen has one scrolling preference surface and setup header`() {
    val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
    assertNotNull(activity.findViewById<View>(R.id.setup_header))
    assertNotNull(activity.supportFragmentManager.findFragmentById(R.id.settings_container))
    assertEquals(0, countScrollViews(activity.window.decorView))
}
```

Also assert that the setup completion text updates after preferences change and that the API-key preference summary never equals the stored key.

- [ ] **Step 5: Run and confirm RED**

Run: `./gradlew testDebugUnitTest --tests '*SettingsActivityTest' --no-daemon`

Expected: FAIL against the nested `ScrollView` layout and visible simple API-key summaries.

- [ ] **Step 6: Implement the settings redesign and legacy icon**

Use a vertical root with an Ink toolbar, compact rounded setup header, and a `FragmentContainerView`/`FrameLayout` filling remaining space. The preference fragment owns the only scrolling list. Move setup actions/status into preferences and refresh on resume and shared-preference changes. Add a compatible unqualified launcher vector at `res/mipmap/ic_launcher.xml`, retaining the adaptive icon at `mipmap-anydpi-v26`.

- [ ] **Step 7: Verify and commit**

Run: `./gradlew testDebugUnitTest --tests '*KeyboardViewTest' --tests '*SettingsActivityTest' --no-daemon`

Expected: PASS.

```bash
git add app/src/main app/src/test/java/ovh/jefe/keyboard/KeyboardViewTest.kt app/src/test/java/ovh/jefe/keyboard/SettingsActivityTest.kt
git commit -m "feat: polish keyboard and setup experience"
```

### Task 5: Full verification and APK delivery

**Files:**
- Modify: `README.md`
- Modify only if verification reveals a defect: files already in Tasks 1–4.
- Produce: `app/build/outputs/apk/debug/app-debug.apk`

**Interfaces:**
- Consumes: all previous task outputs.
- Produces: verified APK and user-facing setup instructions.

- [ ] **Step 1: Run the complete automated suite**

Run: `./gradlew testDebugUnitTest --no-daemon`

Expected: PASS with no failing tests.

- [ ] **Step 2: Run Android lint**

Run: `./gradlew lintDebug --no-daemon`

Expected: PASS with no errors. Fix any correctness/accessibility errors through a failing regression test where behavior is involved.

- [ ] **Step 3: Build the installable APK**

Run: `./gradlew assembleDebug --no-daemon`

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists and is non-empty.

- [ ] **Step 4: Inspect packaged metadata**

Use `apkanalyzer manifest print app/build/outputs/apk/debug/app-debug.apk` (or `aapt dump badging`) and confirm package `ovh.jefe.keyboard`, `minSdkVersion 24`, target SDK 34, launcher activity, and input-method service are present.

- [ ] **Step 5: Finish documentation**

Document build, installation, activation, HTTPS endpoint configuration, microphone permission, and the exact APK path. State clearly that this artifact is debug-signed for direct installation.

- [ ] **Step 6: Commit**

```bash
git add README.md
git commit -m "docs: document installation and private services"
```

- [ ] **Step 7: Independent final review**

Review the complete branch diff against the spec, resolve all Critical/Important findings, rerun Steps 1–4, and only then report the APK as operational.
