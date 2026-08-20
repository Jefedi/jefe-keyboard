package ovh.jefe.keyboard

import android.content.Context
import android.os.Looper
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    var transcription: ((File) -> RemoteResult<String>)? = null
    var translation: ((String) -> RemoteResult<String>)? = null

    override fun getCurrentInputConnection(): InputConnection? = testConnection

    override fun getCurrentInputEditorInfo(): EditorInfo? = testEditorInfo

    override fun createAudioRecorder(): AudioRecorder {
        return recorderFactory?.invoke() ?: super.createAudioRecorder()
    }

    override fun transcribeAudio(file: File): RemoteResult<String> {
        return transcription?.invoke(file) ?: super.transcribeAudio(file)
    }

    override fun translateText(text: String): RemoteResult<String> {
        return translation?.invoke(text) ?: super.translateText(text)
    }
}

private class EditableInputConnection(
    context: Context,
    text: String,
    selectionStart: Int,
    selectionEnd: Int = selectionStart,
) : BaseInputConnection(View(context), true) {
    private val content = SpannableStringBuilder(text).apply {
        Selection.setSelection(this, selectionStart, selectionEnd)
    }
    val editorActions = mutableListOf<Int>()

    override fun getEditable(): Editable = content

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

    fun complete(@Suppress("UNUSED_PARAMETER") text: String): RemoteResult<String> = awaitResult()

    fun completeFile(@Suppress("UNUSED_PARAMETER") file: File): RemoteResult<String> = awaitResult()

    private fun awaitResult(): RemoteResult<String> {
        started.countDown()
        assertTrue(release.await(5, TimeUnit.SECONDS))
        returned.countDown()
        return RemoteResult.Success(value)
    }
}
