# Jefe Keyboard — Reliability and Visual Polish Design

## Goal

Deliver an installable Android keyboard that starts reliably, edits the active field safely, handles dictation and translation without leaking across input sessions, guides setup clearly, and has a coherent visual identity.

## Supported product

- Android 7.0 and newer (`minSdk 24`).
- French QWERTY typing with numbers, symbols, accents, suggestions, voice dictation, and selection translation.
- Self-hosted Whisper and LibreTranslate services over HTTPS.
- A reproducible debug APK for direct installation. Release signing and Play Store publication are outside this delivery because no signing identity was provided.

## Architecture

`JefeKeyboardService` remains the Android IME boundary but no longer trusts cached text as editor truth. A small pure `TextContextParser` derives the current and preceding French tokens from text immediately before the cursor. Every asynchronous operation is tied to an input-session generation and the originating `InputConnection`; results are discarded after the user changes fields or apps.

The HTTP clients return an explicit success/failure result and validate request construction inside their error boundary. The settings UI validates HTTPS endpoints before saving, masks API keys, and uses one scrolling owner. Build tooling is committed so a new checkout can run the same commands as CI.

## Required behavior

### Input lifecycle and editing

- `onStartInput` must be safe before `onCreateInputView`.
- Starting or finishing an editor session resets suggestions and cancels work from the prior session.
- Suggestions are derived from the live cursor context, not from text cached in another field.
- Accepting a suggestion replaces only the live token immediately before the cursor.
- Backspace removes a selection first; otherwise it removes one Unicode code point before the cursor.
- Enter supports Go, Search, Send, Previous, Next, Done, and newline fallback.
- A shifted accented character consumes one-shot Shift.

### Dictation and translation

- A recorder is released on successful stop, failed start, failed stop, hidden keyboard, and service destruction.
- Temporary audio files are deleted in all terminal paths.
- Dictation and translation results may commit only to the same input session and connection that launched them.
- Network/configuration failures show an actionable French message and do not crash the IME.

### Settings and privacy

- Whisper and LibreTranslate URLs must be absolute HTTPS URLs.
- API keys are masked while editing and never displayed in preference summaries.
- Application backup is disabled because ordinary shared preferences contain secrets.
- Setup status refreshes after permission, IME, and preference changes.
- The page uses a single preference list below a compact branded header; no fragment-owned list is nested inside a `ScrollView`.

### Visual direction

Subject: a private, self-hosted French keyboard for people who want useful AI features without sending text to a third-party cloud.

- **Ink** `#102235`: primary structure and toolbar.
- **Signal blue** `#2563EB`: enter/action state and setup progress.
- **Private teal** `#14B8A6`: microphone and successful configuration.
- **Porcelain** `#F4F7FA`: light keyboard surface.
- **Slate** `#64748B`: secondary copy.
- **Night** `#0B1220`: dark keyboard surface.

Typography uses Android's `sans-serif-medium` for setup headings and action keys, `sans-serif` for body text, and `sans-serif-condensed` for compact key labels. The signature element is the suggestion rail: three quiet rounded capsules above the keys, with the microphone capsule turning teal/red during voice input.

The keyboard must remain legible in light and dark themes, use at least 44dp touch targets, provide pressed/active states, cancel a key when the finger slides away, and call accessibility click/haptic hooks.

## Build and verification

- Commit `gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar` for Gradle 8.7; do not ignore the wrapper JAR.
- Use JDK 17, compile/target SDK 34, and keep `minSdk 24`.
- Add JUnit 4.13.2, Robolectric 4.16.1, AndroidX Test JUnit 1.3.0, and MockWebServer 4.12.0 tests.
- Provide a legacy launcher icon for API 24–25 alongside the adaptive API 26 icon.
- The final gate is: unit tests, Android lint, debug assembly, APK existence, and an independent code review.

## Non-goals

- Play Store publishing or release-key management.
- Cloud-hosted speech/translation accounts.
- A language-model predictor or full autocorrect engine.
- A new UI framework or Compose migration.
