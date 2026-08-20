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
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    private var rootView: KeyboardRootView? = null
    private var keyboardView: KeyboardView? = null
    private var railInputs = TopRailInputs()
    private var editorPrivacy = EditorPrivacyPolicy.evaluate(null)
    private var pendingEnterAction = EditorInfo.IME_ACTION_UNSPECIFIED
    private var sessionGeneration = 0L
    private var suggestionSnapshot: SuggestionSnapshot? = null
    private val suggestionGate = SuggestionSessionGate()
    private var translationJob: Job? = null
    private var translationFeedbackJob: Job? = null
    private var failedTranslationSelection: SelectionSnapshot? = null
    private var expectedTranslationSelectionUpdate: EditorSelectionRange? = null
    private var translationAttemptId = 0L

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

    override fun onCreateInputView(): View = KeyboardRootView(this).also { root ->
        rootView = root
        keyboardView = root.keyboardView
        setupKeyboardCallbacks(root.keyboardView)
        setupRailCallbacks(root.railView)
        root.keyboardView.enterAction = pendingEnterAction
        root.keyboardView.isRecording = recordingMode
        root.keyboardView.isTranslating = railInputs.translation == TranslationFeedback.Loading
        root.keyboardView.remoteActionsEnabled =
            editorPrivacy.allowTranslation || editorPrivacy.allowDictation
        setSuggestions(emptyList())
    }

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
            view.remoteActionsEnabled =
                editorPrivacy.allowTranslation || editorPrivacy.allowDictation
        }
        invalidateSuggestions()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboardView?.enterAction = pendingEnterAction
        invalidateSuggestions()
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
        val range = EditorSelectionRange(newSelStart, newSelEnd)
        if (range == expectedTranslationSelectionUpdate) {
            expectedTranslationSelectionUpdate = null
        } else if (railInputs.translation != TranslationFeedback.Idle) {
            cancelTranslation()
        }
        if (!suggestionGate.recordSelectionUpdate(range)) invalidateSuggestions()
    }

    private fun setupKeyboardCallbacks(view: KeyboardView) {
        view.onKeyChar = { char -> handleChar(char) }
        view.onKeyDelete = { handleDelete() }
        view.onKeyEnter = { handleEnter() }
        view.onKeySpace = { handleSpace() }
        view.onMicClick = { toggleRecording() }
        view.onTranslateClick = { translateSelection() }
    }

    private fun setupRailCallbacks(rail: KeyboardRailView) {
        rail.onSuggestionClick = { word -> acceptSuggestion(word) }
        rail.onClipboardTabClick = { onClipboardRequested() }
        rail.onTranslationRetryClick = { retryTranslation() }
        rail.onClipboardPromptClick = { id -> onClipboardPromptRequested(id) }
        rail.onClipboardPromptDismiss = { dismissClipboardPrompt() }
    }

    private fun onClipboardRequested() = Unit

    private fun onClipboardPromptRequested(@Suppress("UNUSED_PARAMETER") entryId: String) = Unit

    private fun dismissClipboardPrompt() = Unit

    private fun retryTranslation() {
        val expected = failedTranslationSelection ?: return
        val connection = currentInputConnection ?: return clearTranslationFeedback()
        val current = captureSelection(connection, connection.getSelectedText(0)?.toString())
        if (current != expected) return clearTranslationFeedback()
        translationFeedbackJob?.cancel()
        setTranslationFeedback(TranslationFeedback.Idle)
        launchTranslation(connection, expected)
    }

    private fun renderRail() {
        rootView?.renderRail(TopRailResolver.resolve(railInputs))
    }

    private fun setSuggestions(values: List<String>) {
        railInputs = railInputs.copy(suggestions = values)
        renderRail()
    }

    private fun setTranslationFeedback(feedback: TranslationFeedback) {
        railInputs = railInputs.copy(translation = feedback)
        keyboardView?.isTranslating = feedback == TranslationFeedback.Loading
        renderRail()
    }

    private fun handleChar(char: String) {
        val connection = currentInputConnection ?: return
        if (connection.commitText(char, 1)) {
            recordSuccessfulLocalMutation(connection, SuggestionMutation.CHARACTER)
        } else {
            invalidateSuggestions()
        }
    }

    private fun handleDelete() {
        val connection = currentInputConnection ?: return
        val success = if (!connection.getSelectedText(0).isNullOrEmpty()) {
            connection.commitText("", 1)
        } else {
            connection.deleteSurroundingTextInCodePoints(1, 0)
        }
        if (success) {
            recordSuccessfulLocalMutation(connection, SuggestionMutation.DELETE)
        } else {
            invalidateSuggestions()
        }
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
        invalidateSuggestions()
    }

    private fun handleSpace() {
        val connection = currentInputConnection ?: return
        if (connection.commitText(" ", 1)) {
            recordSuccessfulLocalMutation(connection, SuggestionMutation.SPACE)
        } else {
            invalidateSuggestions()
        }
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
        if (!suggestionGate.recordExpectedSelection(EditorSelectionRange(tokenStart, cursor))) {
            restoreCollapsedSelection(connection, cursor, tokenStart)
            invalidateSuggestions()
            return
        }

        if (!connection.commitText("$word ", 1)) {
            restoreCollapsedSelection(connection, cursor, tokenStart)
            invalidateSuggestions()
            return
        }
        recordSuccessfulLocalMutation(connection, SuggestionMutation.SUGGESTION)
    }

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
        val suggestions = predictor.suggest(context.currentWord, context.lastWord)
        val absoluteCursor = captureCandidateCursor(connection, context.currentWord)
        suggestionSnapshot = if (suggestions.isEmpty() || absoluteCursor == null) null else {
            SuggestionSnapshot(
                sessionGeneration,
                connection,
                requireNotNull(textBeforeCursor),
                absoluteCursor,
                suggestions,
            )
        }
        setSuggestions(if (suggestionSnapshot == null) emptyList() else suggestions)
    }

    private fun toggleRecording() {
        if (recordingMode) {
            stopRecording(launchTranscription = true)
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (!editorPrivacy.allowDictation) return
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
                val result = transcribeAudio(file)
                if (!isCurrentSession(generation, connection)) return@launch
                when (result) {
                    is RemoteResult.Success -> {
                        if (connection.commitText(result.value, 1)) {
                            invalidateSuggestions()
                        } else {
                            showEditorFailure()
                        }
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

    internal open suspend fun transcribeAudio(file: File): RemoteResult<String> {
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
        if (!editorPrivacy.allowTranslation) return
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

        launchTranslation(connection, selection)
    }

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
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                serviceScope.launch {
                    if (attemptId == translationAttemptId) {
                        translationJob = null
                        clearTranslationFeedback()
                    }
                }
            }
        }
        job.start()
    }

    private fun applyTranslationResult(
        attemptId: Long,
        generation: Long,
        connection: InputConnection,
        selection: SelectionSnapshot,
        result: RemoteResult<String>,
    ) {
        if (attemptId != translationAttemptId) return
        translationJob = null
        if (!isCurrentSession(generation, connection)) {
            clearTranslationFeedback()
            return
        }
        val current = captureSelection(connection, connection.getSelectedText(0)?.toString())
        if (current != selection) {
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
                    invalidateSuggestions()
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

    private fun cancelTranslation() {
        translationAttemptId += 1
        translationJob?.cancel()
        translationJob = null
        clearTranslationFeedback()
    }

    internal open suspend fun translateText(text: String): RemoteResult<String> {
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

    private fun resolveEnterAction(imeOptions: Int?): Int {
        if (imeOptions == null || imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) {
            return EditorInfo.IME_ACTION_UNSPECIFIED
        }
        return imeOptions and EditorInfo.IME_MASK_ACTION
    }

    private fun resetSession() {
        cancelTranslation()
        sessionGeneration += 1
        sessionJob.cancel()
        deletePendingAudioFiles()
        sessionJob = SupervisorJob(serviceJob)
        sessionScope = CoroutineScope(sessionJob + Dispatchers.Main.immediate)
        suggestionSnapshot = null
        setSuggestions(emptyList())
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

    private fun showEditorFailure() {
        Toast.makeText(
            this,
            "L'éditeur a refusé le texte. Touchez le champ et réessayez.",
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun hideWindow() {
        cancelTranslation()
        stopRecording(launchTranscription = false)
        super.hideWindow()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancelTranslation()
        stopRecording(launchTranscription = false)
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        cancelTranslation()
        stopRecording(launchTranscription = false)
        resetSession()
        super.onFinishInput()
    }

    override fun onDestroy() {
        cancelTranslation()
        stopRecording(launchTranscription = false)
        serviceScope.cancel()
        deletePendingAudioFiles()
        suggestionSnapshot = null
        rootView = null
        keyboardView = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_TEXT_CONTEXT = 10_000
    }
}
