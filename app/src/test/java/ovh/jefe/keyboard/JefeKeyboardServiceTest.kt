package ovh.jefe.keyboard

import android.content.Context
import android.os.Looper
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val view = service.onCreateInputView() as KeyboardView
        service.onStartInputView(info, false)

        assertEquals(EditorInfo.IME_ACTION_PREVIOUS, view.enterAction)
    }

    @Test
    fun `backspace removes the selection and leaves surrounding text`() {
        val connection = EditableInputConnection(context(), "avant milieu après", 6, 12)
        val (service, view) = startService(connection)

        view.onKeyDelete?.invoke()

        assertEquals("avant  après", connection.text())
        service.onDestroy()
    }

    @Test
    fun `backspace removes one Unicode code point`() {
        val connection = EditableInputConnection(context(), "A😀", 3)
        val (service, view) = startService(connection)

        view.onKeyDelete?.invoke()

        assertEquals("A", connection.text())
        service.onDestroy()
    }

    @Test
    fun `candidate replaces the actual live token`() {
        val connection = EditableInputConnection(context(), "bo", 2)
        val (service, view) = startService(connection)
        assertTrue(view.suggestions.contains("bon"))

        view.onSuggestionClick?.invoke("bon")

        assertEquals("bon ", connection.text())
        service.onDestroy()
    }

    @Test
    fun `candidate commit failure leaves the original token intact with one atomic replacement`() {
        val connection = RejectingCommitInputConnection(context(), "bo", 2)
        val (service, view) = startService(connection)
        assertTrue(view.suggestions.contains("bon"))

        view.onSuggestionClick?.invoke("bon")

        assertEquals("bo", connection.text())
        assertEquals(listOf("bon "), connection.commitAttempts)
        assertEquals(connection.selectionStart(), connection.selectionEnd())
        service.onDestroy()
    }

    @Test
    fun `candidate does not commit when editor rejects selecting the live token`() {
        val connection = RejectingSelectionInputConnection(context(), "bo", 2)
        val (service, view) = startService(connection)
        assertTrue(view.suggestions.contains("bon"))

        view.onSuggestionClick?.invoke("bon")

        assertEquals("bo", connection.text())
        assertTrue(connection.commitAttempts.isEmpty())
        service.onDestroy()
    }

    @Test
    fun `candidate fails closed when extracted cursor provenance is unavailable or invalid`() {
        listOf(ExtractedTextMode.NULL, ExtractedTextMode.INVALID_SELECTION).forEach { mode ->
            val connection = EditableInputConnection(
                context(),
                "bo",
                2,
                extractedTextMode = mode,
            )
            val (service, view) = startService(connection)

            view.onSuggestionClick?.invoke("bon")

            assertEquals("bo", connection.text())
            service.onDestroy()
        }
    }

    @Test
    fun `candidate never replaces unrelated text after a cursor move`() {
        val connection = EditableInputConnection(context(), "bo ici", 2)
        val (service, view) = startService(connection)
        assertTrue(view.suggestions.contains("bon"))
        connection.select(6)
        service.onUpdateSelection(2, 2, 6, 6, -1, -1)

        view.onSuggestionClick?.invoke("bon")

        assertEquals("bo ici", connection.text())
        service.onDestroy()
    }

    @Test
    fun `candidate from a prior session never edits the new connection`() {
        val first = EditableInputConnection(context(), "bo", 2)
        val (service, view) = startService(first)
        assertTrue(view.suggestions.contains("bon"))
        val second = EditableInputConnection(context(), "secret", 6)
        service.testConnection = second

        service.onStartInput(editorInfo(), false)
        view.onSuggestionClick?.invoke("bon")

        assertEquals("bo", first.text())
        assertEquals("secret", second.text())
        assertTrue(view.suggestions.isEmpty())
        service.onDestroy()
    }

    @Test
    fun `previous enter action is sent to the editor without a newline`() {
        val connection = EditableInputConnection(context(), "texte", 5)
        val (service, view) = startService(connection, EditorInfo.IME_ACTION_PREVIOUS)

        view.onKeyEnter?.invoke()

        assertEquals(listOf(EditorInfo.IME_ACTION_PREVIOUS), connection.editorActions)
        assertEquals("texte", connection.text())
        service.onDestroy()
    }

    @Test
    fun `no enter action flag renders and commits the default newline action`() {
        val connection = EditableInputConnection(context(), "texte", 5)
        val actionWithFlag = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        val (service, view) = startService(connection, actionWithFlag)

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
    fun `external selection changes clear and refresh suggestions from live text`() {
        val connection = EditableInputConnection(context(), "je ", 3)
        val (service, view) = startService(connection)
        assertEquals(listOf("suis", "vais", "veux"), view.suggestions)

        connection.select(0, 2)
        service.onUpdateSelection(3, 3, 0, 2, -1, -1)
        assertTrue(view.suggestions.isEmpty())

        connection.replaceAll("bo", 2)
        service.onUpdateSelection(0, 2, 2, 2, -1, -1)
        assertTrue(view.suggestions.contains("bon"))
        service.onDestroy()
    }

    @Test
    fun `translation replaces the originally selected text when selection is unchanged`() {
        val connection = EditableInputConnection(context(), "bonjour monde", 0, 7)
        val service = testService(connection)
        service.translation = { RemoteResult.Success("hello") }
        val view = createViewAndStart(service)

        view.onTranslateClick?.invoke()
        idleMainLooperUntil { connection.text() == "hello monde" }

        assertEquals("hello monde", connection.text())
        service.onDestroy()
    }

    @Test
    fun `translation preserves successful leading and trailing whitespace in the editor`() {
        val connection = EditableInputConnection(context(), "bonjour monde", 0, 7)
        val service = testService(connection)
        service.translation = { RemoteResult.Success(" traduction ") }
        val view = createViewAndStart(service)

        view.onTranslateClick?.invoke()
        idleMainLooperUntil { connection.text() != "bonjour monde" }

        assertEquals(" traduction  monde", connection.text())
        service.onDestroy()
    }

    @Test
    fun `translation whitespace success is rejected without replacing the selection`() {
        val connection = EditableInputConnection(context(), "bonjour monde", 0, 7)
        val service = testService(connection)
        service.translation = { RemoteResult.Success("  \n\t  ") }
        val view = createViewAndStart(service)

        view.onTranslateClick?.invoke()
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
        val view = createViewAndStart(service)

        view.onTranslateClick?.invoke()
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
        val view = createViewAndStart(service)

        view.onTranslateClick?.invoke()
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
        val view = createViewAndStart(service)

        view.onTranslateClick?.invoke()
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
            val view = createViewAndStart(service)

            view.onTranslateClick?.invoke()
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
        val view = createViewAndStart(service)

        view.onTranslateClick?.invoke()
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
        val view = createViewAndStart(service)
        grantMicrophonePermission()

        view.onMicClick?.invoke()
        view.onMicClick?.invoke()
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
        val view = createViewAndStart(service)
        grantMicrophonePermission()

        view.onMicClick?.invoke()
        view.onMicClick?.invoke()
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
        val view = createViewAndStart(service)
        grantMicrophonePermission()

        view.onMicClick?.invoke()

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
        val view = createViewAndStart(service)
        grantMicrophonePermission()
        val existingAudio = service.cacheDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("dictation_") }
            .map(File::getName)
            .toSet()

        view.onMicClick?.invoke()

        val remainingAudio = service.cacheDir.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("dictation_") }
            .map(File::getName)
            .toSet()
        assertFalse(view.isRecording)
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
        val view = createViewAndStart(service)
        grantMicrophonePermission()

        view.onMicClick?.invoke()
        view.onMicClick?.invoke()

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
            val view = createViewAndStart(service)
            grantMicrophonePermission()
            view.onMicClick?.invoke()

            finish(service)

            assertEquals(1, recorder.releaseCount)
            assertFalse(recorder.outputFile!!.exists())
            assertEquals(0, transcriptionCalls.get())
        }
    }

    private fun startService(
        connection: EditableInputConnection,
        action: Int = EditorInfo.IME_ACTION_UNSPECIFIED,
    ): Pair<TestJefeKeyboardService, KeyboardView> {
        val service = testService(connection, action)
        return service to createViewAndStart(service, action)
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

    private fun createViewAndStart(
        service: TestJefeKeyboardService,
        action: Int = service.testEditorInfo?.imeOptions ?: EditorInfo.IME_ACTION_UNSPECIFIED,
    ): KeyboardView {
        val info = editorInfo(action)
        service.testEditorInfo = info
        service.onStartInput(info, false)
        val view = service.onCreateInputView() as KeyboardView
        service.onStartInputView(info, false)
        return view
    }

    private fun editorInfo(action: Int = EditorInfo.IME_ACTION_UNSPECIFIED): EditorInfo {
        return EditorInfo().apply { imeOptions = action }
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
    private val extractedTextMode: ExtractedTextMode = ExtractedTextMode.VALID,
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

private class RejectingSelectionInputConnection(
    context: Context,
    text: String,
    selectionStart: Int,
) : EditableInputConnection(context, text, selectionStart) {
    val commitAttempts = mutableListOf<String>()

    override fun setSelection(start: Int, end: Int): Boolean = false

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        commitAttempts += text?.toString().orEmpty()
        return false
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
