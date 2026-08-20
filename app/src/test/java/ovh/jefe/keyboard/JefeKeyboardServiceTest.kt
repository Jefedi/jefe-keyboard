package ovh.jefe.keyboard

import android.content.Context
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class JefeKeyboardServiceTest {
    @Test
    fun `starting input before creating the keyboard view does not crash`() {
        val service = Robolectric.buildService(JefeKeyboardService::class.java).create().get()

        service.onStartInput(EditorInfo(), false)
    }

    @Test
    fun `pending enter action is applied when the view is initialized`() {
        val service = Robolectric.buildService(JefeKeyboardService::class.java).create().get()
        val info = editorInfo(EditorInfo.IME_ACTION_PREVIOUS)

        service.onStartInput(info, false)
        val root = service.onCreateInputView() as KeyboardRootView
        service.onStartInputView(info, false)

        assertEquals(EditorInfo.IME_ACTION_PREVIOUS, root.keyboardView.enterAction)
    }

    @Test
    fun `replacing the input root retires every action owned by the old root`() {
        val oldConnection = EditableInputConnection(context(), "old", 3)
        val newConnection = EditableInputConnection(context(), "fresh", 5)
        val translationCalls = AtomicInteger()
        val recorderCreations = AtomicInteger()
        val service = testService(oldConnection).apply {
            translation = {
                translationCalls.incrementAndGet()
                RemoteResult.Failure("indisponible")
            }
            recorderFactory = {
                recorderCreations.incrementAndGet()
                FakeAudioRecorder()
            }
        }
        val oldRoot = createRootAndStart(service)
        val oldCharacter = requireNotNull(oldRoot.keyboardView.onKeyChar)
        val oldDelete = requireNotNull(oldRoot.keyboardView.onKeyDelete)
        val oldEnter = requireNotNull(oldRoot.keyboardView.onKeyEnter)
        val oldSpace = requireNotNull(oldRoot.keyboardView.onKeySpace)
        val oldMic = requireNotNull(oldRoot.keyboardView.onMicClick)
        val oldTranslate = requireNotNull(oldRoot.keyboardView.onTranslateClick)
        val oldSuggestion = requireNotNull(oldRoot.railView.onSuggestionClick)
        val oldClipboard = requireNotNull(oldRoot.railView.onClipboardTabClick)
        val oldRetry = requireNotNull(oldRoot.railView.onTranslationRetryClick)
        val oldClipboardPrompt = requireNotNull(oldRoot.railView.onClipboardPromptClick)
        val oldClipboardDismiss = requireNotNull(oldRoot.railView.onClipboardPromptDismiss)

        service.testConnection = newConnection
        val newRoot = createRootAndStart(service)

        fun textAfterOldAction(action: () -> Unit): String {
            newConnection.replaceAll("fresh", 5)
            action()
            return newConnection.text()
        }
        val staleEditResults = listOf(
            textAfterOldAction { oldCharacter("x") },
            textAfterOldAction { oldDelete() },
            textAfterOldAction { oldEnter() },
            textAfterOldAction { oldSpace() },
        )

        newConnection.replaceAll("b", 1)
        requireNotNull(newRoot.keyboardView.onKeyChar).invoke("o")
        oldSuggestion("bon")
        val textAfterOldSuggestion = newConnection.text()

        newConnection.replaceAll("secret", 6)
        newConnection.select(0, 6)
        requireNotNull(newRoot.keyboardView.onTranslateClick).invoke()
        drainMainLooper()
        oldRetry()
        drainMainLooper()
        oldTranslate()
        drainMainLooper()

        grantMicrophonePermission()
        oldMic()
        oldClipboard()
        oldClipboardPrompt("entry")
        oldClipboardDismiss()

        assertEquals(List(4) { "fresh" }, staleEditResults)
        assertEquals("bo", textAfterOldSuggestion)
        assertEquals(1, translationCalls.get())
        assertEquals(0, recorderCreations.get())
        assertRootCallbacksDetached(oldRoot)
        service.onDestroy()
    }

    @Test
    fun `destroying the service detaches every root callback and cached edits are inert`() {
        val connection = EditableInputConnection(context(), "fresh", 5)
        val (service, root) = startRootService(connection)
        val cachedCharacter = requireNotNull(root.keyboardView.onKeyChar)

        service.onDestroy()

        cachedCharacter("x")
        assertEquals("fresh", connection.text())
        assertRootCallbacksDetached(root)
    }

    @Test
    fun `backspace removes the selection and leaves surrounding text`() {
        val connection = EditableInputConnection(context(), "avant milieu après", 6, 12)
        val (service, root) = startRootService(connection)

        root.keyboardView.onKeyDelete?.invoke()

        assertEquals("avant  après", connection.text())
        service.onDestroy()
    }

    @Test
    fun `backspace removes one Unicode code point`() {
        val connection = EditableInputConnection(context(), "A😀", 3)
        val (service, root) = startRootService(connection)

        root.keyboardView.onKeyDelete?.invoke()

        assertEquals("A", connection.text())
        service.onDestroy()
    }

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
    fun `accepted character from a retired session cannot enable the new session`() {
        val oldConnection = SynchronousCommitCallbackInputConnection(context(), "b", 1)
        val newConnection = EditableInputConnection(context(), "bo", 2)
        val (service, root) = startRootService(oldConnection)
        oldConnection.onAcceptedCommit = {
            oldConnection.onAcceptedCommit = null
            service.testConnection = newConnection
            service.onStartInput(editorInfo(), false)
        }

        requireNotNull(root.keyboardView.onKeyChar).invoke("o")

        assertEquals("bo", oldConnection.text())
        assertEquals("bo", newConnection.text())
        assertTrue(root.railView.suggestionViews().isEmpty())
        service.onDestroy()
    }

    @Test
    fun `accepted character from a retired root cannot update its replacement root`() {
        val connection = SynchronousCommitCallbackInputConnection(context(), "b", 1)
        val (service, oldRoot) = startRootService(connection)
        lateinit var replacementRoot: KeyboardRootView
        connection.onAcceptedCommit = {
            connection.onAcceptedCommit = null
            replacementRoot = service.onCreateInputView() as KeyboardRootView
        }

        requireNotNull(oldRoot.keyboardView.onKeyChar).invoke("o")

        assertEquals("bo", connection.text())
        assertRootCallbacksDetached(oldRoot)
        assertTrue(replacementRoot.railView.suggestionViews().isEmpty())
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
        assertEquals(
            listOf("suis", "vais", "veux"),
            root.railView.suggestionViews().map { it.text.toString() },
        )
        service.onDestroy()

        val newline = EditableInputConnection(context(), "je", 2)
        val (newlineService, newlineRoot) = startRootService(newline)
        newlineRoot.keyboardView.onKeyEnter?.invoke()
        assertTrue(newlineRoot.railView.suggestionViews().isEmpty())
        newlineService.onDestroy()

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
    fun `private field keeps the rail empty`() {
        val connection = EditableInputConnection(context(), "b", 1)
        val service = testService(connection).apply {
            testEditorInfo = editorInfo(
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            )
        }
        val root = createRootAndStart(service, info = requireNotNull(service.testEditorInfo))

        root.keyboardView.onKeyChar?.invoke("o")

        assertTrue(root.railView.suggestionViews().isEmpty())
        service.onDestroy()
    }

    @Test
    fun `candidate replaces the actual live token`() {
        val connection = EditableInputConnection(context(), "b", 1)
        val (service, root) = startRootService(connection)
        root.keyboardView.onKeyChar?.invoke("o")
        assertTrue(root.railView.suggestionViews().any { it.text == "bon" })

        root.railView.suggestionViews().first { it.text == "bon" }.performClick()

        assertEquals("bon ", connection.text())
        service.onDestroy()
    }

    @Test
    fun `candidate selection and commit callbacks preserve the new context`() {
        val connection = SelectionReportingInputConnection(context(), "", 0)
        val (service, root) = startRootService(connection)
        root.keyboardView.onKeyChar?.invoke("j")
        connection.dispatchSelectionUpdates(service)
        val candidate = root.railView.suggestionViews().first { it.text == "je" }

        candidate.performClick()
        assertEquals("je ", connection.text())
        assertEquals(
            listOf("suis", "vais", "veux"),
            root.railView.suggestionViews().map { it.text.toString() },
        )

        connection.dispatchSelectionUpdates(service)

        assertEquals(
            listOf("suis", "vais", "veux"),
            root.railView.suggestionViews().map { it.text.toString() },
        )
        service.onDestroy()
    }

    @Test
    fun `candidate aborts when its intermediate selection exceeds the callback bound`() {
        val connection = EditableInputConnection(context(), "b", 1)
        val (service, root) = startRootService(connection)
        repeat(32) {
            root.keyboardView.onKeyChar?.invoke("o")
            root.keyboardView.onKeyDelete?.invoke()
        }
        assertEquals("b", connection.text())
        val candidate = root.railView.suggestionViews().first { it.text == "bon" }

        candidate.performClick()

        assertEquals("b", connection.text())
        assertEquals(1, connection.selectionStart())
        assertEquals(1, connection.selectionEnd())
        assertTrue(root.railView.suggestionViews().isEmpty())

        root.keyboardView.onKeyChar?.invoke("o")

        assertEquals("bo", connection.text())
        assertTrue(root.railView.suggestionViews().any { it.text == "bon" })
        service.onDestroy()
    }

    @Test
    fun `candidate commit failure leaves the original token intact with one atomic replacement`() {
        val connection = RejectingSuggestionInputConnection(context(), "b", 1)
        val (service, root) = startRootService(connection)
        root.keyboardView.onKeyChar?.invoke("o")
        assertTrue(root.railView.suggestionViews().any { it.text == "bon" })

        root.railView.suggestionViews().first { it.text == "bon" }.performClick()

        assertEquals("bo", connection.text())
        assertEquals(listOf("bon "), connection.commitAttempts)
        assertEquals(connection.selectionStart(), connection.selectionEnd())
        service.onDestroy()
    }

    @Test
    fun `candidate does not commit when editor rejects selecting the live token`() {
        val connection = RejectingSelectionInputConnection(context(), "b", 1)
        val (service, root) = startRootService(connection)
        root.keyboardView.onKeyChar?.invoke("o")
        assertTrue(root.railView.suggestionViews().any { it.text == "bon" })
        connection.rejectSelections = true

        root.railView.suggestionViews().first { it.text == "bon" }.performClick()

        assertEquals("bo", connection.text())
        assertTrue(connection.commitAttempts.isEmpty())
        service.onDestroy()
    }

    @Test
    fun `candidate fails closed when extracted cursor provenance is unavailable or invalid`() {
        listOf(ExtractedTextMode.NULL, ExtractedTextMode.INVALID_SELECTION).forEach { mode ->
            val connection = EditableInputConnection(
                context(),
                "b",
                1,
            )
            val (service, root) = startRootService(connection)

            root.keyboardView.onKeyChar?.invoke("o")
            val candidate = root.railView.suggestionViews().first { it.text == "bon" }
            connection.extractedTextMode = mode
            candidate.performClick()

            assertEquals("bo", connection.text())
            service.onDestroy()
        }
    }

    @Test
    fun `candidate never replaces unrelated text after a cursor move`() {
        val connection = EditableInputConnection(context(), "b ici", 1)
        val (service, root) = startRootService(connection)
        root.keyboardView.onKeyChar?.invoke("o")
        val candidate = root.railView.suggestionViews().first { it.text == "bon" }
        connection.select(6)
        service.onUpdateSelection(2, 2, 6, 6, -1, -1)

        candidate.performClick()

        assertEquals("bo ici", connection.text())
        service.onDestroy()
    }

    @Test
    fun `candidate from a prior session never edits the new connection`() {
        val first = EditableInputConnection(context(), "b", 1)
        val (service, root) = startRootService(first)
        root.keyboardView.onKeyChar?.invoke("o")
        val candidate = root.railView.suggestionViews().first { it.text == "bon" }
        val second = EditableInputConnection(context(), "secret", 6)
        service.testConnection = second

        service.onStartInput(editorInfo(), false)
        candidate.performClick()

        assertEquals("bo", first.text())
        assertEquals("secret", second.text())
        assertTrue(root.railView.suggestionViews().isEmpty())
        service.onDestroy()
    }

    @Test
    fun `session replacement during candidate preflight cannot edit the retired connection`() {
        val oldConnection = SynchronousSelectedTextCallbackInputConnection(context(), "b", 1)
        val newConnection = EditableInputConnection(context(), "new", 3)
        val (service, root) = startRootService(oldConnection)
        requireNotNull(root.keyboardView.onKeyChar).invoke("o")
        val candidate = root.railView.suggestionViews().first { it.text == "bon" }
        oldConnection.onSelectedTextRead = {
            oldConnection.onSelectedTextRead = null
            service.testConnection = newConnection
            service.onStartInput(editorInfo(), false)
        }

        candidate.performClick()

        assertEquals("bo", oldConnection.text())
        assertEquals("new", newConnection.text())
        assertTrue(root.railView.suggestionViews().isEmpty())
        service.onDestroy()
    }

    @Test
    fun `previous enter action is sent to the editor without a newline`() {
        val connection = EditableInputConnection(context(), "texte", 5)
        val (service, root) = startRootService(connection, EditorInfo.IME_ACTION_PREVIOUS)

        root.keyboardView.onKeyEnter?.invoke()

        assertEquals(listOf(EditorInfo.IME_ACTION_PREVIOUS), connection.editorActions)
        assertEquals("texte", connection.text())
        service.onDestroy()
    }

    @Test
    fun `no enter action flag renders and commits the default newline action`() {
        val connection = EditableInputConnection(context(), "texte", 5)
        val actionWithFlag = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        val (service, root) = startRootService(connection, actionWithFlag)
        val view = root.keyboardView

        view.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, 1080, view.measuredHeight)
        val enter = view.renderedKeys().single { it.action == KeyboardView.KeyAction.ENTER }
        view.onKeyEnter?.invoke()

        assertEquals("Retour", enter.label)
        assertTrue(connection.editorActions.isEmpty())
        assertEquals("texte\n", connection.text())
        service.onDestroy()
    }

    @Test
    fun `external selection clears suggestions until the next local edit`() {
        val connection = EditableInputConnection(context(), "je", 2)
        val (service, root) = startRootService(connection)
        root.keyboardView.onKeySpace?.invoke()
        assertEquals(
            listOf("suis", "vais", "veux"),
            root.railView.suggestionViews().map { it.text.toString() },
        )

        connection.select(0, 2)
        service.onUpdateSelection(3, 3, 0, 2, -1, -1)
        assertTrue(root.railView.suggestionViews().isEmpty())

        connection.replaceAll("bo", 2)
        service.onUpdateSelection(0, 2, 2, 2, -1, -1)
        assertTrue(root.railView.suggestionViews().isEmpty())
        service.onDestroy()
    }

    @Test
    fun `private editor never starts translation or dictation`() {
        val calls = AtomicInteger()
        val connection = EditableInputConnection(context(), "secret", 0, 6)
        val service = testService(connection).apply {
            translation = { calls.incrementAndGet(); RemoteResult.Success("x") }
            transcription = { calls.incrementAndGet(); RemoteResult.Success("x") }
            recorderFactory = {
                calls.incrementAndGet()
                FakeAudioRecorder()
            }
        }
        val info = editorInfo(
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val root = createRootAndStart(service, info = info)
        grantMicrophonePermission()

        root.keyboardView.onTranslateClick?.invoke()
        root.keyboardView.onMicClick?.invoke()
        drainMainLooper()

        assertEquals(0, calls.get())
        service.onDestroy()
    }

    @Test
    fun `session replacement during translation preflight never sends stale editor text`() {
        val oldConnection = SynchronousSelectedTextCallbackInputConnection(
            context(),
            "bonjour",
            0,
            7,
        )
        val newConnection = EditableInputConnection(context(), "nouveau", 0, 7)
        val calls = AtomicInteger()
        lateinit var service: TestJefeKeyboardService
        service = testService(oldConnection).apply {
            translation = {
                calls.incrementAndGet()
                RemoteResult.Success("hello")
            }
        }
        val root = createRootAndStart(service)
        oldConnection.onSelectedTextRead = {
            oldConnection.onSelectedTextRead = null
            service.testConnection = newConnection
            service.onStartInput(editorInfo(), false)
        }

        root.keyboardView.onTranslateClick?.invoke()
        drainMainLooper()

        assertEquals(0, calls.get())
        assertEquals("bonjour", oldConnection.text())
        assertEquals("nouveau", newConnection.text())
        assertFalse(root.railView.state is TopRailState.Translation)
        service.onDestroy()
    }

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
        shadowOf(Looper.getMainLooper()).idleFor(1_199, TimeUnit.MILLISECONDS)
        assertEquals(TopRailState.Translation(TranslationFeedback.Success), root.railView.state)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)
        assertFalse(root.railView.state is TopRailState.Translation)
        service.onDestroy()
    }

    @Test
    fun `translation installs loading and its lock before synchronous remote code`() {
        val connection = EditableInputConnection(context(), "bonjour", 0, 7)
        val calls = AtomicInteger()
        val stateAtFirstCall = AtomicReference<TopRailState>()
        val service = testService(connection)
        lateinit var root: KeyboardRootView
        service.translation = {
            val call = calls.incrementAndGet()
            if (call == 1) {
                stateAtFirstCall.set(root.railView.state)
                root.keyboardView.onTranslateClick?.invoke()
            }
            RemoteResult.Success("hello")
        }
        root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        drainMainLooper()

        assertEquals(TopRailState.Translation(TranslationFeedback.Loading), stateAtFirstCall.get())
        assertEquals(1, calls.get())
        assertEquals("hello", connection.text())
        assertEquals(TopRailState.Translation(TranslationFeedback.Success), root.railView.state)
        service.onDestroy()
    }

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
    fun `cancelled attempt cannot clear or commit over a newer loading attempt`() {
        val connection = EditableInputConnection(context(), "bonjour monde", 0, 7)
        val delayed = DelayedRemoteResult("fresh")
        val calls = AtomicInteger()
        lateinit var service: TestJefeKeyboardService
        lateinit var root: KeyboardRootView
        service = testService(connection).apply {
            translation = {
                if (calls.incrementAndGet() == 1) {
                    connection.select(8, 13)
                    service.onUpdateSelection(0, 7, 8, 13, -1, -1)
                    connection.select(0, 7)
                    root.keyboardView.onTranslateClick?.invoke()
                    RemoteResult.Success("stale")
                } else {
                    delayed.complete(it)
                }
            }
        }
        root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        assertTrue(delayed.started.await(5, TimeUnit.SECONDS))

        assertEquals(2, calls.get())
        assertEquals("bonjour monde", connection.text())
        assertEquals(TopRailState.Translation(TranslationFeedback.Loading), root.railView.state)

        delayed.release.countDown()
        idleMainLooperUntil { connection.text() == "fresh monde" }
        assertEquals(TopRailState.Translation(TranslationFeedback.Success), root.railView.state)
        service.onDestroy()
    }

    @Test
    fun `accepted commit callback cannot overwrite a newer loading attempt`() {
        val connection = SynchronousCommitCallbackInputConnection(
            context(),
            "bonjour monde",
            0,
            7,
        )
        val delayed = DelayedRemoteResult("world")
        val calls = AtomicInteger()
        lateinit var service: TestJefeKeyboardService
        lateinit var root: KeyboardRootView
        service = testService(connection).apply {
            translation = {
                if (calls.incrementAndGet() == 1) {
                    RemoteResult.Success("hello")
                } else {
                    delayed.complete(it)
                }
            }
        }
        root = createRootAndStart(service)
        connection.onAcceptedCommit = {
            connection.onAcceptedCommit = null
            service.onUpdateSelection(0, 7, 5, 5, -1, -1)
            connection.select(6, 11)
            service.onUpdateSelection(5, 5, 6, 11, -1, -1)
            root.keyboardView.onTranslateClick?.invoke()
            assertEquals(
                TopRailState.Translation(TranslationFeedback.Loading),
                root.railView.state,
            )
        }

        root.keyboardView.onTranslateClick?.invoke()
        assertTrue(delayed.started.await(5, TimeUnit.SECONDS))

        assertEquals("hello monde", connection.text())
        assertEquals(TopRailState.Translation(TranslationFeedback.Loading), root.railView.state)
        shadowOf(Looper.getMainLooper()).idleFor(1_200, TimeUnit.MILLISECONDS)
        assertEquals(TopRailState.Translation(TranslationFeedback.Loading), root.railView.state)

        delayed.release.countDown()
        idleMainLooperUntil { connection.text() == "hello world" }
        assertEquals(2, calls.get())
        assertEquals(TopRailState.Translation(TranslationFeedback.Success), root.railView.state)
        service.onDestroy()
    }

    @Test
    fun `accepted commit callback without a newer attempt never claims stale success`() {
        val connection = SynchronousCommitCallbackInputConnection(
            context(),
            "bonjour",
            0,
            7,
        )
        val service = testService(connection).apply {
            translation = { RemoteResult.Success("hello") }
        }
        val root = createRootAndStart(service)
        connection.onAcceptedCommit = {
            connection.onAcceptedCommit = null
            service.onUpdateSelection(0, 7, 5, 5, -1, -1)
        }

        root.keyboardView.onTranslateClick?.invoke()
        drainMainLooper()

        assertEquals("hello", connection.text())
        assertFalse(root.railView.state is TopRailState.Translation)
        shadowOf(Looper.getMainLooper()).idleFor(1_200, TimeUnit.MILLISECONDS)
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
            translation = {
                calls.incrementAndGet()
                replies.removeFirst()
            }
        }
        val root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        idleMainLooperUntil {
            root.railView.state == TopRailState.Translation(TranslationFeedback.Error)
        }
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
            translation = {
                calls.incrementAndGet()
                RemoteResult.Failure("indisponible")
            }
        }
        val root = createRootAndStart(service)
        root.keyboardView.onTranslateClick?.invoke()
        idleMainLooperUntil {
            root.railView.state == TopRailState.Translation(TranslationFeedback.Error)
        }
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
    fun `retry never transfers a failed attempt to an identical replacement connection`() {
        val failedConnection = EditableInputConnection(context(), "bonjour", 0, 7)
        val replacementConnection = EditableInputConnection(context(), "bonjour", 0, 7)
        val calls = AtomicInteger()
        val service = testService(failedConnection).apply {
            translation = {
                if (calls.incrementAndGet() == 1) {
                    RemoteResult.Failure("indisponible")
                } else {
                    RemoteResult.Success("hello")
                }
            }
        }
        val root = createRootAndStart(service)
        root.keyboardView.onTranslateClick?.invoke()
        idleMainLooperUntil {
            root.railView.state == TopRailState.Translation(TranslationFeedback.Error)
        }

        service.testConnection = replacementConnection
        root.railView.retryButton().performClick()
        drainMainLooper()

        assertEquals(1, calls.get())
        assertEquals("bonjour", failedConnection.text())
        assertEquals("bonjour", replacementConnection.text())
        service.onDestroy()
    }

    @Test
    fun `retry with no current connection clears the obsolete error immediately`() {
        val connection = EditableInputConnection(context(), "bonjour", 0, 7)
        val calls = AtomicInteger()
        val service = testService(connection).apply {
            translation = {
                calls.incrementAndGet()
                RemoteResult.Failure("indisponible")
            }
        }
        val root = createRootAndStart(service)
        root.keyboardView.onTranslateClick?.invoke()
        idleMainLooperUntil {
            root.railView.state == TopRailState.Translation(TranslationFeedback.Error)
        }

        service.testConnection = null
        root.railView.retryButton().performClick()

        assertEquals(1, calls.get())
        assertFalse(root.railView.state is TopRailState.Translation)
        service.onDestroy()
    }

    @Test
    fun `stale retry read cannot clear a newer loading attempt`() {
        val connection = SynchronousSelectedTextCallbackInputConnection(
            context(),
            "bonjour",
            0,
            7,
        )
        val delayed = DelayedRemoteResult("hello")
        val calls = AtomicInteger()
        lateinit var service: TestJefeKeyboardService
        lateinit var root: KeyboardRootView
        service = testService(connection).apply {
            translation = {
                if (calls.incrementAndGet() == 1) {
                    RemoteResult.Failure("indisponible")
                } else {
                    delayed.complete(it)
                }
            }
        }
        root = createRootAndStart(service)
        root.keyboardView.onTranslateClick?.invoke()
        idleMainLooperUntil {
            root.railView.state == TopRailState.Translation(TranslationFeedback.Error)
        }
        connection.onSelectedTextRead = {
            connection.onSelectedTextRead = null
            service.onUpdateSelection(0, 7, 0, 7, -1, -1)
            root.keyboardView.onTranslateClick?.invoke()
            assertEquals(
                TopRailState.Translation(TranslationFeedback.Loading),
                root.railView.state,
            )
        }

        root.railView.retryButton().performClick()
        assertTrue(delayed.started.await(5, TimeUnit.SECONDS))

        assertEquals(2, calls.get())
        assertEquals(TopRailState.Translation(TranslationFeedback.Loading), root.railView.state)
        shadowOf(Looper.getMainLooper()).idleFor(3_000, TimeUnit.MILLISECONDS)
        assertEquals(TopRailState.Translation(TranslationFeedback.Loading), root.railView.state)

        delayed.release.countDown()
        idleMainLooperUntil { connection.text() == "hello" }
        assertEquals(TopRailState.Translation(TranslationFeedback.Success), root.railView.state)
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
        idleMainLooperUntil {
            root.railView.state == TopRailState.Translation(TranslationFeedback.Error)
        }

        assertFalse(ShadowToast.getTextOfLatestToast().contains("private backend detail"))
        assertTrue(root.railView.retryButton().isEnabled)
        service.onDestroy()
    }

    @Test
    fun `translation cancellation removes feedback without becoming a retryable error`() {
        val connection = EditableInputConnection(context(), "bonjour", 0, 7)
        val service = testService(connection).apply {
            translation = { throw CancellationException("private cancellation detail") }
        }
        val root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        drainMainLooper()

        assertFalse(root.railView.state is TopRailState.Translation)
        assertFalse(ShadowToast.getTextOfLatestToast().orEmpty().contains("private cancellation detail"))
        service.onDestroy()
    }

    @Test
    fun `retry cancels the prior error timer before showing a new loading`() {
        val connection = EditableInputConnection(context(), "bonjour", 0, 7)
        val delayed = DelayedRemoteResult("hello")
        val calls = AtomicInteger()
        val service = testService(connection).apply {
            translation = {
                if (calls.getAndIncrement() == 0) {
                    RemoteResult.Failure("indisponible")
                } else {
                    delayed.complete(it)
                }
            }
        }
        val root = createRootAndStart(service)
        root.keyboardView.onTranslateClick?.invoke()
        idleMainLooperUntil {
            root.railView.state == TopRailState.Translation(TranslationFeedback.Error)
        }

        root.railView.retryButton().performClick()
        assertTrue(delayed.started.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idleFor(3_000, TimeUnit.MILLISECONDS)

        assertEquals(TopRailState.Translation(TranslationFeedback.Loading), root.railView.state)
        delayed.release.countDown()
        service.onDestroy()
    }

    @Test
    fun `translation error clears after exactly three seconds`() {
        val connection = EditableInputConnection(context(), "bonjour", 0, 7)
        val service = testService(connection).apply {
            translation = { RemoteResult.Failure("indisponible") }
        }
        val root = createRootAndStart(service)
        root.keyboardView.onTranslateClick?.invoke()
        idleMainLooperUntil {
            root.railView.state == TopRailState.Translation(TranslationFeedback.Error)
        }

        shadowOf(Looper.getMainLooper()).idleFor(2_999, TimeUnit.MILLISECONDS)
        assertEquals(TopRailState.Translation(TranslationFeedback.Error), root.railView.state)
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS)

        assertFalse(root.railView.state is TopRailState.Translation)
        service.onDestroy()
    }

    @Test
    fun `every service boundary cancels a pending translation`() {
        listOf<Pair<Boolean, (TestJefeKeyboardService) -> Unit>>(
            false to { it.hideWindow() },
            false to { it.onFinishInputView(false) },
            false to { it.onStartInput(editorInfo(), false) },
            false to { it.onFinishInput() },
            true to { it.onDestroy() },
        ).forEach { (destroyedByStop, stop) ->
            assertPendingTranslationCancelled(stop, destroyedByStop)
        }
    }

    @Test
    fun `translation replaces the originally selected text when selection is unchanged`() {
        val connection = EditableInputConnection(context(), "bonjour monde", 0, 7)
        val service = testService(connection)
        service.translation = { RemoteResult.Success("hello") }
        val root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        idleMainLooperUntil { connection.text() == "hello monde" }

        assertEquals("hello monde", connection.text())
        service.onDestroy()
    }

    @Test
    fun `translation preserves successful leading and trailing whitespace in the editor`() {
        val connection = EditableInputConnection(context(), "bonjour monde", 0, 7)
        val service = testService(connection)
        service.translation = { RemoteResult.Success(" traduction ") }
        val root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        idleMainLooperUntil { connection.text() != "bonjour monde" }

        assertEquals(" traduction  monde", connection.text())
        service.onDestroy()
    }

    @Test
    fun `translation whitespace success is rejected without replacing the selection`() {
        val connection = EditableInputConnection(context(), "bonjour monde", 0, 7)
        val service = testService(connection)
        service.translation = { RemoteResult.Success("  \n\t  ") }
        val root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        drainMainLooper()

        assertEquals("bonjour monde", connection.text())
        assertEquals(0, connection.selectionStart())
        assertEquals(7, connection.selectionEnd())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("vide", ignoreCase = true))
        service.onDestroy()
    }

    @Test
    fun `translation commit rejection keeps selection and reports editor failure`() {
        val connection = RejectingCommitInputConnection(context(), "bonjour monde", 0, 7)
        val service = testService(connection)
        service.translation = { RemoteResult.Success("hello") }
        val root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        idleMainLooperUntil { connection.commitAttempts.isNotEmpty() }

        assertEquals(listOf("hello"), connection.commitAttempts)
        assertEquals("bonjour monde", connection.text())
        assertEquals(0, connection.selectionStart())
        assertEquals(7, connection.selectionEnd())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("éditeur", ignoreCase = true))
        service.onDestroy()
    }

    @Test
    fun `translation leaves text unchanged after the selection moves`() {
        val connection = EditableInputConnection(context(), "bonjour monde", 0, 7)
        val delayed = DelayedRemoteResult("hello")
        val service = testService(connection)
        service.translation = delayed::complete
        val root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        assertTrue(delayed.started.await(5, TimeUnit.SECONDS))
        connection.select(8, 13)
        service.onUpdateSelection(0, 7, 8, 13, -1, -1)
        delayed.release.countDown()
        assertTrue(delayed.returned.await(5, TimeUnit.SECONDS))
        drainMainLooper()

        assertEquals("bonjour monde", connection.text())
        service.onDestroy()
    }

    @Test
    fun `translation rejects the same selected text at a different absolute offset`() {
        val connection = EditableInputConnection(
            context = context(),
            text = "same|same",
            selectionStart = 0,
            selectionEnd = 4,
            documentStartOffset = 400,
            fixedTextBeforeCursor = "identical context before",
            fixedTextAfterCursor = "identical context after",
        )
        val delayed = DelayedRemoteResult("translated")
        val service = testService(connection)
        service.translation = delayed::complete
        val root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        assertTrue(delayed.started.await(5, TimeUnit.SECONDS))
        connection.select(5, 9)
        service.onUpdateSelection(0, 4, 5, 9, -1, -1)
        delayed.release.countDown()
        assertTrue(delayed.returned.await(5, TimeUnit.SECONDS))
        drainMainLooper()

        assertEquals("same|same", connection.text())
        service.onDestroy()
    }

    @Test
    fun `translation fails closed when extracted selection provenance is unavailable or invalid`() {
        listOf(ExtractedTextMode.NULL, ExtractedTextMode.INVALID_SELECTION).forEach { mode ->
            val connection = EditableInputConnection(
                context(),
                "bonjour",
                0,
                7,
                extractedTextMode = mode,
            )
            val translationCalls = AtomicInteger()
            val service = testService(connection)
            service.translation = {
                translationCalls.incrementAndGet()
                RemoteResult.Success("hello")
            }
            val root = createRootAndStart(service)

            root.keyboardView.onTranslateClick?.invoke()
            drainMainLooper()

            assertEquals("bonjour", connection.text())
            assertEquals(0, translationCalls.get())
            service.onDestroy()
        }
    }

    @Test
    fun `new session invalidates a delayed translation result`() {
        val first = EditableInputConnection(context(), "bonjour", 0, 7)
        val second = EditableInputConnection(context(), "privé", 5)
        val delayed = DelayedRemoteResult("hello")
        val service = testService(first)
        service.translation = delayed::complete
        val root = createRootAndStart(service)

        root.keyboardView.onTranslateClick?.invoke()
        assertTrue(delayed.started.await(5, TimeUnit.SECONDS))
        service.testConnection = second
        service.onStartInput(editorInfo(), false)
        delayed.release.countDown()
        assertTrue(delayed.returned.await(5, TimeUnit.SECONDS))
        drainMainLooper()

        assertEquals("bonjour", first.text())
        assertEquals("privé", second.text())
        service.onDestroy()
    }

    @Test
    fun `new session invalidates a delayed dictation result`() {
        val first = EditableInputConnection(context(), "avant ", 6)
        val second = EditableInputConnection(context(), "privé", 5)
        val recorder = FakeAudioRecorder()
        val delayed = DelayedRemoteResult("dictée")
        val service = testService(first)
        service.recorderFactory = { recorder }
        service.transcription = delayed::completeFile
        val root = createRootAndStart(service)
        grantMicrophonePermission()

        root.keyboardView.onMicClick?.invoke()
        root.keyboardView.onMicClick?.invoke()
        assertTrue(delayed.started.await(5, TimeUnit.SECONDS))
        service.testConnection = second
        service.onStartInput(editorInfo(), false)
        delayed.release.countDown()
        assertTrue(delayed.returned.await(5, TimeUnit.SECONDS))
        drainMainLooper()

        assertEquals("avant ", first.text())
        assertEquals("privé", second.text())
        assertEquals(1, recorder.releaseCount)
        assertFalse(recorder.outputFile!!.exists())
        service.onDestroy()
    }

    @Test
    fun `dictation commit rejection reports editor failure without claiming a commit`() {
        val connection = RejectingCommitInputConnection(context(), "avant ", 6)
        val recorder = FakeAudioRecorder()
        val service = testService(connection)
        service.recorderFactory = { recorder }
        service.transcription = { RemoteResult.Success("dictée") }
        val root = createRootAndStart(service)
        grantMicrophonePermission()

        root.keyboardView.onMicClick?.invoke()
        root.keyboardView.onMicClick?.invoke()
        idleMainLooperUntil { connection.commitAttempts.isNotEmpty() }

        assertEquals(listOf("dictée"), connection.commitAttempts)
        assertEquals("avant ", connection.text())
        assertTrue(ShadowToast.getTextOfLatestToast().contains("éditeur", ignoreCase = true))
        assertEquals(1, recorder.releaseCount)
        service.onDestroy()
    }

    @Test
    fun `failed recorder start releases recorder and deletes audio`() {
        val connection = EditableInputConnection(context(), "", 0)
        val recorder = FakeAudioRecorder(failStart = true)
        val service = testService(connection)
        service.recorderFactory = { recorder }
        val root = createRootAndStart(service)
        grantMicrophonePermission()

        root.keyboardView.onMicClick?.invoke()

        assertEquals(1, recorder.releaseCount)
        assertNotNull(recorder.outputFile)
        assertFalse(recorder.outputFile!!.exists())
        service.onDestroy()
    }

    @Test
    fun `recorder factory failure leaves clean UI deletes temp audio and shows an actionable error`() {
        val connection = EditableInputConnection(context(), "", 0)
        val service = testService(connection)
        service.recorderFactory = { error("factory failed") }
        val root = createRootAndStart(service)
        grantMicrophonePermission()
        val existingAudio = service.cacheDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("dictation_") }
            .map(File::getName)
            .toSet()

        root.keyboardView.onMicClick?.invoke()

        val remainingAudio = service.cacheDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("dictation_") }
            .map(File::getName)
            .toSet()
        assertFalse(root.keyboardView.isRecording)
        assertEquals(existingAudio, remainingAudio)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Erreur micro"))
        service.onDestroy()
    }

    @Test
    fun `failed recorder stop releases recorder deletes audio and skips transcription`() {
        val connection = EditableInputConnection(context(), "", 0)
        val recorder = FakeAudioRecorder(failStop = true)
        val transcriptionCalls = AtomicInteger()
        val service = testService(connection)
        service.recorderFactory = { recorder }
        service.transcription = {
            transcriptionCalls.incrementAndGet()
            RemoteResult.Success("unexpected")
        }
        val root = createRootAndStart(service)
        grantMicrophonePermission()

        root.keyboardView.onMicClick?.invoke()
        root.keyboardView.onMicClick?.invoke()

        assertEquals(1, recorder.releaseCount)
        assertFalse(recorder.outputFile!!.exists())
        assertEquals(0, transcriptionCalls.get())
        service.onDestroy()
    }

    @Test
    fun `hiding finishing and destroying release recording without transcription`() {
        listOf<(TestJefeKeyboardService) -> Unit>(
            { it.hideWindow() },
            { it.onFinishInput() },
            { it.onDestroy() },
        ).forEach { finish ->
            val connection = EditableInputConnection(context(), "", 0)
            val recorder = FakeAudioRecorder()
            val transcriptionCalls = AtomicInteger()
            val service = testService(connection)
            service.recorderFactory = { recorder }
            service.transcription = {
                transcriptionCalls.incrementAndGet()
                RemoteResult.Success("unexpected")
            }
            val root = createRootAndStart(service)
            grantMicrophonePermission()
            root.keyboardView.onMicClick?.invoke()

            finish(service)

            assertEquals(1, recorder.releaseCount)
            assertFalse(recorder.outputFile!!.exists())
            assertEquals(0, transcriptionCalls.get())
        }
    }

    private fun startRootService(
        connection: EditableInputConnection,
        action: Int = EditorInfo.IME_ACTION_UNSPECIFIED,
    ): Pair<TestJefeKeyboardService, KeyboardRootView> {
        val service = testService(connection, action)
        return service to createRootAndStart(service, action)
    }

    private fun testService(
        connection: EditableInputConnection,
        action: Int = EditorInfo.IME_ACTION_UNSPECIFIED,
    ): TestJefeKeyboardService {
        return Robolectric.buildService(TestJefeKeyboardService::class.java).create().get().apply {
            testConnection = connection
            testEditorInfo = editorInfo(action)
        }
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
        inputType: Int = android.text.InputType.TYPE_CLASS_TEXT,
    ): EditorInfo = EditorInfo().apply {
        imeOptions = action
        this.inputType = inputType
    }

    private fun grantMicrophonePermission() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    }

    private fun drainMainLooper() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun idleMainLooperUntil(condition: () -> Boolean) {
        repeat(100) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for observable editor state", condition())
    }

    private fun assertRootCallbacksDetached(root: KeyboardRootView) {
        assertNull(root.keyboardView.onKeyChar)
        assertNull(root.keyboardView.onKeyDelete)
        assertNull(root.keyboardView.onKeyEnter)
        assertNull(root.keyboardView.onKeySpace)
        assertNull(root.keyboardView.onMicClick)
        assertNull(root.keyboardView.onTranslateClick)
        assertNull(root.railView.onSuggestionClick)
        assertNull(root.railView.onClipboardTabClick)
        assertNull(root.railView.onTranslationRetryClick)
        assertNull(root.railView.onClipboardPromptClick)
        assertNull(root.railView.onClipboardPromptDismiss)
    }

    private fun assertPendingTranslationCancelled(
        stop: (TestJefeKeyboardService) -> Unit,
        destroyedByStop: Boolean,
    ) {
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
        if (!destroyedByStop) service.onDestroy()
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()
}

internal class TestJefeKeyboardService : JefeKeyboardService() {
    var testConnection: InputConnection? = null
    var testEditorInfo: EditorInfo? = null
    var recorderFactory: (() -> AudioRecorder)? = null
    var transcription: (suspend (File) -> RemoteResult<String>)? = null
    var translation: (suspend (String) -> RemoteResult<String>)? = null

    override fun getCurrentInputConnection(): InputConnection? = testConnection

    override fun getCurrentInputEditorInfo(): EditorInfo? = testEditorInfo

    override fun createAudioRecorder(): AudioRecorder {
        return recorderFactory?.invoke() ?: super.createAudioRecorder()
    }

    override suspend fun transcribeAudio(file: File): RemoteResult<String> {
        return transcription?.invoke(file) ?: super.transcribeAudio(file)
    }

    override suspend fun translateText(text: String): RemoteResult<String> {
        return translation?.invoke(text) ?: super.translateText(text)
    }
}

private enum class ExtractedTextMode {
    VALID,
    NULL,
    INVALID_SELECTION,
}

private open class EditableInputConnection(
    context: Context,
    text: String,
    selectionStart: Int,
    selectionEnd: Int = selectionStart,
    private val documentStartOffset: Int = 0,
    var extractedTextMode: ExtractedTextMode = ExtractedTextMode.VALID,
    private val fixedTextBeforeCursor: String? = null,
    private val fixedTextAfterCursor: String? = null,
) : BaseInputConnection(View(context), true) {
    private val content = SpannableStringBuilder(text).apply {
        Selection.setSelection(this, selectionStart, selectionEnd)
    }
    val editorActions = mutableListOf<Int>()

    override fun getEditable(): Editable = content

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? {
        if (extractedTextMode == ExtractedTextMode.NULL) return null
        val start = Selection.getSelectionStart(content)
        val end = Selection.getSelectionEnd(content)
        return ExtractedText().apply {
            text = content.toString()
            startOffset = documentStartOffset
            selectionStart = if (extractedTextMode == ExtractedTextMode.INVALID_SELECTION) {
                content.length + 1
            } else {
                start
            }
            selectionEnd = if (extractedTextMode == ExtractedTextMode.INVALID_SELECTION) {
                content.length + 1
            } else {
                end
            }
        }
    }

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
        return fixedTextBeforeCursor ?: super.getTextBeforeCursor(length, flags)
    }

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? {
        return fixedTextAfterCursor ?: super.getTextAfterCursor(length, flags)
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val localStart = start - documentStartOffset
        val localEnd = end - documentStartOffset
        if (localStart !in 0..content.length || localEnd !in 0..content.length) return false
        Selection.setSelection(content, localStart, localEnd)
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        editorActions += editorAction
        return true
    }

    fun select(start: Int, end: Int = start) {
        Selection.setSelection(content, start, end)
    }

    fun replaceAll(text: String, cursor: Int) {
        content.replace(0, content.length, text)
        select(cursor)
    }

    fun text(): String = content.toString()

    fun selectionStart(): Int = Selection.getSelectionStart(content)

    fun selectionEnd(): Int = Selection.getSelectionEnd(content)
}

private class RejectingCommitInputConnection(
    context: Context,
    text: String,
    selectionStart: Int,
    selectionEnd: Int = selectionStart,
) : EditableInputConnection(context, text, selectionStart, selectionEnd) {
    val commitAttempts = mutableListOf<String>()

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        commitAttempts += text?.toString().orEmpty()
        return false
    }
}

private class SynchronousCommitCallbackInputConnection(
    context: Context,
    text: String,
    selectionStart: Int,
    selectionEnd: Int = selectionStart,
) : EditableInputConnection(context, text, selectionStart, selectionEnd) {
    var onAcceptedCommit: (() -> Unit)? = null

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val accepted = super.commitText(text, newCursorPosition)
        if (accepted) onAcceptedCommit?.invoke()
        return accepted
    }
}

private class SynchronousSelectedTextCallbackInputConnection(
    context: Context,
    text: String,
    selectionStart: Int,
    selectionEnd: Int = selectionStart,
) : EditableInputConnection(context, text, selectionStart, selectionEnd) {
    var onSelectedTextRead: (() -> Unit)? = null

    override fun getSelectedText(flags: Int): CharSequence? {
        val selectedText = super.getSelectedText(flags)
        onSelectedTextRead?.invoke()
        return selectedText
    }
}

private class SelectionReportingInputConnection(
    context: Context,
    text: String,
    selectionStart: Int,
    selectionEnd: Int = selectionStart,
) : EditableInputConnection(context, text, selectionStart, selectionEnd) {
    private data class SelectionUpdate(
        val oldStart: Int,
        val oldEnd: Int,
        val newStart: Int,
        val newEnd: Int,
    )

    private val pendingSelectionUpdates = mutableListOf<SelectionUpdate>()
    private var committing = false

    override fun setSelection(start: Int, end: Int): Boolean {
        val oldStart = selectionStart()
        val oldEnd = selectionEnd()
        val success = super.setSelection(start, end)
        if (success && !committing) {
            pendingSelectionUpdates += SelectionUpdate(oldStart, oldEnd, start, end)
        }
        return success
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val oldStart = selectionStart()
        val oldEnd = selectionEnd()
        committing = true
        val success = try {
            super.commitText(text, newCursorPosition)
        } finally {
            committing = false
        }
        if (success) {
            pendingSelectionUpdates += SelectionUpdate(
                oldStart,
                oldEnd,
                selectionStart(),
                selectionEnd(),
            )
        }
        return success
    }

    fun dispatchSelectionUpdates(service: JefeKeyboardService) {
        val updates = pendingSelectionUpdates.toList()
        pendingSelectionUpdates.clear()
        updates.forEach { update ->
            service.onUpdateSelection(
                update.oldStart,
                update.oldEnd,
                update.newStart,
                update.newEnd,
                -1,
                -1,
            )
        }
    }
}

private class RejectingSuggestionInputConnection(
    context: Context,
    text: String,
    selectionStart: Int,
) : EditableInputConnection(context, text, selectionStart) {
    val commitAttempts = mutableListOf<String>()

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val value = text?.toString().orEmpty()
        if (value == "bon ") {
            commitAttempts += value
            return false
        }
        return super.commitText(text, newCursorPosition)
    }
}

private class RejectingSelectionInputConnection(
    context: Context,
    text: String,
    selectionStart: Int,
) : EditableInputConnection(context, text, selectionStart) {
    val commitAttempts = mutableListOf<String>()
    var rejectSelections = false

    override fun setSelection(start: Int, end: Int): Boolean =
        !rejectSelections && super.setSelection(start, end)

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (rejectSelections) {
            commitAttempts += text?.toString().orEmpty()
            return false
        }
        return super.commitText(text, newCursorPosition)
    }
}

private class FakeAudioRecorder(
    private val failStart: Boolean = false,
    private val failStop: Boolean = false,
) : AudioRecorder {
    var outputFile: File? = null
    var releaseCount = 0

    override fun prepareAndStart(outputFile: File) {
        this.outputFile = outputFile
        outputFile.writeBytes(byteArrayOf(1, 2, 3))
        if (failStart) error("start failed")
    }

    override fun stop() {
        if (failStop) error("stop failed")
    }

    override fun release() {
        releaseCount += 1
    }
}

private class DelayedRemoteResult(private val value: String) {
    val started = CountDownLatch(1)
    val release = CountDownLatch(1)
    val returned = CountDownLatch(1)

    suspend fun complete(@Suppress("UNUSED_PARAMETER") text: String): RemoteResult<String> = awaitResult()

    suspend fun completeFile(@Suppress("UNUSED_PARAMETER") file: File): RemoteResult<String> = awaitResult()

    private suspend fun awaitResult(): RemoteResult<String> = withContext(Dispatchers.IO) {
        started.countDown()
        assertTrue(release.await(5, TimeUnit.SECONDS))
        returned.countDown()
        RemoteResult.Success(value)
    }
}
