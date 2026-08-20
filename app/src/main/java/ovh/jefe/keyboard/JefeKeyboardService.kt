package ovh.jefe.keyboard

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal interface AudioRecorder {
    fun prepareAndStart(outputFile: File)

    fun stop()

    fun release()
}

private class AndroidAudioRecorder : AudioRecorder {
    private val recorder = MediaRecorder()

    override fun prepareAndStart(outputFile: File) {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioSamplingRate(16_000)
        recorder.setAudioEncodingBitRate(64_000)
        recorder.setOutputFile(outputFile.absolutePath)
        recorder.prepare()
        recorder.start()
    }

    override fun stop() = recorder.stop()

    override fun release() = recorder.release()
}

/**
 * IME Service — connecte KeyboardView → InputConnection + Whisper + LibreTranslate.
 */
open class JefeKeyboardService : InputMethodService() {
    private val predictor = FrenchPredictor()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main.immediate)
    private var sessionJob = SupervisorJob(serviceJob)
    private var sessionScope = CoroutineScope(sessionJob + Dispatchers.Main.immediate)

    private var keyboardView: KeyboardView? = null
    private var pendingEnterAction = EditorInfo.IME_ACTION_UNSPECIFIED
    private var sessionGeneration = 0L
    private var suggestionSnapshot: SuggestionSnapshot? = null

    private var recordingMode = false
    private var recorder: AudioRecorder? = null
    private var audioFile: File? = null
    private val pendingAudioFiles = Collections.synchronizedSet(mutableSetOf<File>())

    private data class SuggestionSnapshot(
        val generation: Long,
        val connection: InputConnection,
        val textBeforeCursor: String,
        val absoluteCursor: Int,
        val suggestions: List<String>,
    )

    private data class SelectionSnapshot(
        val selectedText: String,
        val absoluteSelectionStart: Int,
        val absoluteSelectionEnd: Int,
        val textBeforeSelection: String,
        val textAfterSelection: String,
    )

    private data class ExtractedSelection(
        val text: String,
        val relativeSelectionStart: Int,
        val relativeSelectionEnd: Int,
        val absoluteSelectionStart: Int,
        val absoluteSelectionEnd: Int,
    )

    override fun onCreateInputView(): View {
        return KeyboardView(this).also { view ->
            keyboardView = view
            setupKeyboardCallbacks(view)
            view.enterAction = pendingEnterAction
            view.suggestions = emptyList()
            view.isRecording = recordingMode
        }
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        stopRecording(launchTranscription = false)
        resetSession()
        pendingEnterAction = info?.imeOptions
            ?.and(EditorInfo.IME_MASK_ACTION)
            ?: EditorInfo.IME_ACTION_UNSPECIFIED
        keyboardView?.let { view ->
            view.enterAction = pendingEnterAction
            view.suggestions = emptyList()
            view.isRecording = false
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView?.enterAction = pendingEnterAction
        updateSuggestions()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        updateSuggestions()
    }

    private fun setupKeyboardCallbacks(view: KeyboardView) {
        view.onKeyChar = { char -> handleChar(char) }
        view.onKeyDelete = { handleDelete() }
        view.onKeyEnter = { handleEnter() }
        view.onKeySpace = { handleSpace() }
        view.onMicClick = { toggleRecording() }
        view.onTranslateClick = { translateSelection() }
        view.onSuggestionClick = { word -> acceptSuggestion(word) }
    }

    private fun handleChar(char: String) {
        currentInputConnection?.commitText(char, 1)
        updateSuggestions()
    }

    private fun handleDelete() {
        val connection = currentInputConnection ?: return
        val selectedText = connection.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
            connection.commitText("", 1)
        } else {
            connection.deleteSurroundingTextInCodePoints(1, 0)
        }
        updateSuggestions()
    }

    private fun handleEnter() {
        val connection = currentInputConnection ?: return
        when (pendingEnterAction) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_PREVIOUS,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            -> connection.performEditorAction(pendingEnterAction)

            else -> connection.commitText("\n", 1)
        }
        updateSuggestions()
    }

    private fun handleSpace() {
        currentInputConnection?.commitText(" ", 1)
        updateSuggestions()
    }

    private fun acceptSuggestion(word: String) {
        val snapshot = suggestionSnapshot ?: return
        val connection = currentInputConnection ?: return
        if (
            snapshot.generation != sessionGeneration ||
            snapshot.connection !== connection ||
            word !in snapshot.suggestions ||
            !connection.getSelectedText(0).isNullOrEmpty()
        ) {
            return
        }

        val textBeforeCursor = connection.getTextBeforeCursor(MAX_TEXT_CONTEXT, 0)
            ?.toString()
            ?: return
        if (textBeforeCursor != snapshot.textBeforeCursor) return

        val currentWord = TextContextParser.parse(textBeforeCursor).currentWord
        val cursor = captureCandidateCursor(connection, currentWord) ?: return
        if (cursor != snapshot.absoluteCursor) return
        val tokenStart = cursor - currentWord.length
        if (tokenStart < 0 || !connection.setSelection(tokenStart, cursor)) return

        if (!connection.commitText("$word ", 1)) {
            restoreCollapsedSelection(connection, cursor, tokenStart)
            updateSuggestions()
            return
        }
        updateSuggestions()
    }

    private fun updateSuggestions() {
        val view = keyboardView
        val connection = currentInputConnection
        if (view == null || connection == null || !connection.getSelectedText(0).isNullOrEmpty()) {
            suggestionSnapshot = null
            view?.suggestions = emptyList()
            return
        }

        val textBeforeCursor = connection.getTextBeforeCursor(MAX_TEXT_CONTEXT, 0)
            ?.toString()
            .orEmpty()
        val context = TextContextParser.parse(textBeforeCursor)
        val suggestions = predictor.suggest(context.currentWord, context.lastWord)
        val absoluteCursor = captureCandidateCursor(connection, context.currentWord)
        suggestionSnapshot = if (suggestions.isEmpty() || absoluteCursor == null) {
            null
        } else {
            SuggestionSnapshot(
                sessionGeneration,
                connection,
                textBeforeCursor,
                absoluteCursor,
                suggestions,
            )
        }
        view.suggestions = if (suggestionSnapshot == null) emptyList() else suggestions
    }

    private fun toggleRecording() {
        if (recordingMode) {
            stopRecording(launchTranscription = true)
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "Ouvrez l'app Jefe Keyboard pour accorder le micro",
                Toast.LENGTH_LONG,
            ).show()
            startActivity(Intent(this, SettingsActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            })
            return
        }

        val file = File(cacheDir, "dictation_${System.currentTimeMillis()}.m4a")
        var nextRecorder: AudioRecorder? = null
        var started = false
        var failure: Exception? = null
        try {
            val createdRecorder = createAudioRecorder()
            nextRecorder = createdRecorder
            recorder = createdRecorder
            audioFile = file
            createdRecorder.prepareAndStart(file)
            started = true
            recordingMode = true
            keyboardView?.isRecording = true
        } catch (error: Exception) {
            failure = error
        } finally {
            if (!started) {
                nextRecorder?.let(::releaseRecorder)
                recorder = null
                audioFile = null
                recordingMode = false
                keyboardView?.isRecording = false
                file.delete()
            }
        }

        if (started) {
            Toast.makeText(this, "Parlez maintenant…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Erreur micro: ${failure?.message.orEmpty()}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRecording(launchTranscription: Boolean) {
        val activeRecorder = recorder
        val file = audioFile
        recorder = null
        audioFile = null
        recordingMode = false
        keyboardView?.isRecording = false

        if (activeRecorder == null) {
            file?.delete()
            return
        }

        var stopped = false
        var stopFailure: Exception? = null
        try {
            activeRecorder.stop()
            stopped = true
        } catch (error: Exception) {
            stopFailure = error
        } finally {
            releaseRecorder(activeRecorder)
        }

        if (!launchTranscription || !stopped || file == null || !file.exists() || file.length() <= 0L) {
            file?.delete()
            if (launchTranscription && stopFailure != null) {
                Toast.makeText(
                    this,
                    "Impossible d'arrêter l'enregistrement: ${stopFailure.message.orEmpty()}",
                    Toast.LENGTH_LONG,
                ).show()
            }
            return
        }

        launchTranscription(file)
    }

    private fun launchTranscription(file: File) {
        val generation = sessionGeneration
        val connection = currentInputConnection
        if (connection == null) {
            file.delete()
            return
        }

        pendingAudioFiles += file
        Toast.makeText(this, "Transcription…", Toast.LENGTH_SHORT).show()
        sessionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = withContext(Dispatchers.IO) { transcribeAudio(file) }
                if (!isCurrentSession(generation, connection)) return@launch
                when (result) {
                    is RemoteResult.Success -> {
                        connection.commitText(result.value, 1)
                        updateSuggestions()
                    }

                    is RemoteResult.Failure -> showRemoteFailure(result.message)
                }
            } finally {
                pendingAudioFiles -= file
                file.delete()
            }
        }
    }

    internal open fun createAudioRecorder(): AudioRecorder = AndroidAudioRecorder()

    internal open fun transcribeAudio(file: File): RemoteResult<String> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val url = preferences.getString("whisper_url", "").orEmpty()
        if (url.isBlank()) {
            return RemoteResult.Failure(
                "Configurez une adresse HTTPS de transcription dans les réglages.",
            )
        }
        val apiKey = preferences.getString("whisper_api_key", "").orEmpty()
        val model = preferences.getString("whisper_model", "whisper-1") ?: "whisper-1"
        return WhisperClient(url, apiKey, model).transcribe(file, language = "fr")
    }

    private fun translateSelection() {
        val connection = currentInputConnection ?: return
        val selectedText = connection.getSelectedText(0)?.toString()
        if (selectedText.isNullOrBlank()) {
            Toast.makeText(this, "Sélectionnez du texte d'abord", Toast.LENGTH_SHORT).show()
            return
        }
        val selection = captureSelection(connection, selectedText)
        if (selection == null) {
            Toast.makeText(
                this,
                "Impossible de vérifier cette sélection dans l'éditeur.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val generation = sessionGeneration
        Toast.makeText(this, "Traduction…", Toast.LENGTH_SHORT).show()
        sessionScope.launch {
            val result = withContext(Dispatchers.IO) { translateText(selection.selectedText) }
            if (
                !isCurrentSession(generation, connection) ||
                captureSelection(connection, connection.getSelectedText(0)?.toString()) != selection
            ) {
                return@launch
            }

            when (result) {
                is RemoteResult.Success -> {
                    connection.commitText(result.value, 1)
                    updateSuggestions()
                    Toast.makeText(this@JefeKeyboardService, "Traduit ✓", Toast.LENGTH_SHORT).show()
                }

                is RemoteResult.Failure -> showRemoteFailure(result.message)
            }
        }
    }

    internal open fun translateText(text: String): RemoteResult<String> {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val url = preferences.getString("translate_url", "").orEmpty()
        if (url.isBlank()) {
            return RemoteResult.Failure(
                "Configurez une adresse HTTPS de traduction dans les réglages.",
            )
        }
        val apiKey = preferences.getString("translate_api_key", "").orEmpty()
        val source = preferences.getString("translate_source", "auto") ?: "auto"
        val target = preferences.getString("translate_target", "fr") ?: "fr"
        return TranslateClient(url, apiKey, source, target).translate(text)
    }

    private fun captureSelection(
        connection: InputConnection,
        selectedText: String?,
    ): SelectionSnapshot? {
        if (selectedText == null) return null
        val extracted = captureExtractedSelection(connection) ?: return null
        val relativeStart = minOf(
            extracted.relativeSelectionStart,
            extracted.relativeSelectionEnd,
        )
        val relativeEnd = maxOf(
            extracted.relativeSelectionStart,
            extracted.relativeSelectionEnd,
        )
        if (extracted.text.substring(relativeStart, relativeEnd) != selectedText) return null
        return SelectionSnapshot(
            selectedText = selectedText,
            absoluteSelectionStart = extracted.absoluteSelectionStart,
            absoluteSelectionEnd = extracted.absoluteSelectionEnd,
            textBeforeSelection = connection.getTextBeforeCursor(MAX_TEXT_CONTEXT, 0)
                ?.toString()
                .orEmpty(),
            textAfterSelection = connection.getTextAfterCursor(MAX_TEXT_CONTEXT, 0)
                ?.toString()
                .orEmpty(),
        )
    }

    private fun captureCandidateCursor(connection: InputConnection, currentWord: String): Int? {
        val extracted = captureExtractedSelection(connection) ?: return null
        if (extracted.absoluteSelectionStart != extracted.absoluteSelectionEnd) return null
        val relativeCursor = extracted.relativeSelectionStart
        val relativeTokenStart = relativeCursor - currentWord.length
        if (relativeTokenStart < 0) return null
        if (extracted.text.substring(relativeTokenStart, relativeCursor) != currentWord) return null
        return extracted.absoluteSelectionStart
    }

    private fun captureExtractedSelection(connection: InputConnection): ExtractedSelection? {
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return null
        val text = extracted.text?.toString() ?: return null
        val relativeStart = extracted.selectionStart
        val relativeEnd = extracted.selectionEnd
        if (extracted.startOffset < 0) return null
        if (relativeStart !in 0..text.length || relativeEnd !in 0..text.length) return null

        val absoluteStart = extracted.startOffset.toLong() + relativeStart
        val absoluteEnd = extracted.startOffset.toLong() + relativeEnd
        if (absoluteStart !in 0..Int.MAX_VALUE.toLong()) return null
        if (absoluteEnd !in 0..Int.MAX_VALUE.toLong()) return null

        return ExtractedSelection(
            text = text,
            relativeSelectionStart = relativeStart,
            relativeSelectionEnd = relativeEnd,
            absoluteSelectionStart = absoluteStart.toInt(),
            absoluteSelectionEnd = absoluteEnd.toInt(),
        )
    }

    private fun restoreCollapsedSelection(
        connection: InputConnection,
        preferredCursor: Int,
        fallbackCursor: Int,
    ) {
        if (!connection.setSelection(preferredCursor, preferredCursor)) {
            connection.setSelection(fallbackCursor, fallbackCursor)
        }
    }

    private fun isCurrentSession(generation: Long, connection: InputConnection): Boolean {
        return generation == sessionGeneration && currentInputConnection === connection
    }

    private fun resetSession() {
        sessionGeneration += 1
        sessionJob.cancel()
        deletePendingAudioFiles()
        sessionJob = SupervisorJob(serviceJob)
        sessionScope = CoroutineScope(sessionJob + Dispatchers.Main.immediate)
        suggestionSnapshot = null
        keyboardView?.suggestions = emptyList()
    }

    private fun releaseRecorder(activeRecorder: AudioRecorder) {
        try {
            activeRecorder.release()
        } catch (_: Exception) {
            // The editor must remain usable even when a device recorder rejects release().
        }
    }

    private fun deletePendingAudioFiles() {
        synchronized(pendingAudioFiles) {
            pendingAudioFiles.forEach(File::delete)
            pendingAudioFiles.clear()
        }
    }

    private fun showRemoteFailure(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun hideWindow() {
        stopRecording(launchTranscription = false)
        super.hideWindow()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopRecording(launchTranscription = false)
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopRecording(launchTranscription = false)
        resetSession()
        super.onFinishInput()
    }

    override fun onDestroy() {
        stopRecording(launchTranscription = false)
        serviceScope.cancel()
        deletePendingAudioFiles()
        suggestionSnapshot = null
        keyboardView = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_TEXT_CONTEXT = 10_000
    }
}
