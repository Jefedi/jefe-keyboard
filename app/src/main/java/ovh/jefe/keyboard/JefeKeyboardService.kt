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
    private var externalSelectionRevision = 0L
    private var suggestionSnapshot: SuggestionSnapshot? = null
    private val suggestionGate = SuggestionSessionGate()
    private var translationJob: Job? = null
    private var translationFeedbackJob: Job? = null
    private var failedTranslationAttempt: FailedTranslationAttempt? = null
    private var expectedTranslationSelectionUpdate: ExpectedTranslationSelectionUpdate? = null
    private var translationAttemptId = 0L

    private var recordingMode = false
    private var recorder: AudioRecorder? = null
    private var audioFile: File? = null
    private val pendingAudioFiles = Collections.synchronizedSet(mutableSetOf<File>())

    private data class SuggestionSnapshot(
        val generation: Long,
        val connection: InputConnection,
        val root: KeyboardRootView,
        val selectionRevision: Long,
        val textBeforeCursor: String,
        val absoluteCursor: Int,
        val suggestions: List<String>,
    )

    private data class EditorOwner(
        val generation: Long,
        val connection: InputConnection,
        val root: KeyboardRootView,
        val selectionRevision: Long,
    )

    private data class TranslationOwner(
        val generation: Long,
        val connection: InputConnection,
        val selectionRevision: Long,
    )

    private data class SelectionSnapshot(
        val selectedText: String,
        val absoluteSelectionStart: Int,
        val absoluteSelectionEnd: Int,
        val textBeforeSelection: String,
        val textAfterSelection: String,
    )

    private data class FailedTranslationAttempt(
        val attemptId: Long,
        val owner: TranslationOwner,
        val selection: SelectionSnapshot,
    )

    private data class ExpectedTranslationSelectionUpdate(
        val owner: TranslationOwner,
        val previousSelection: EditorSelectionRange,
        val selection: EditorSelectionRange,
    )

    private data class ExtractedSelection(
        val text: String,
        val relativeSelectionStart: Int,
        val relativeSelectionEnd: Int,
        val absoluteSelectionStart: Int,
        val absoluteSelectionEnd: Int,
    )

    override fun onCreateInputView(): View {
        retireRoot(rootView)
        return KeyboardRootView(this).also { root ->
            rootView = root
            keyboardView = root.keyboardView
            setupKeyboardCallbacks(root)
            setupRailCallbacks(root)
            root.keyboardView.enterAction = pendingEnterAction
            root.keyboardView.isRecording = recordingMode
            root.keyboardView.isTranslating = railInputs.translation == TranslationFeedback.Loading
            root.keyboardView.remoteActionsEnabled =
                editorPrivacy.allowTranslation || editorPrivacy.allowDictation
            invalidateSuggestions()
        }
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
        val previousSelection = EditorSelectionRange(oldSelStart, oldSelEnd)
        val selection = EditorSelectionRange(newSelStart, newSelEnd)
        val expectedTranslation = consumeExpectedTranslationSelectionUpdate(
            previousSelection,
            selection,
        )
        val expectedSuggestion = suggestionGate.recordSelectionUpdate(
            previousSelection,
            selection,
        )
        if (!expectedTranslation && !expectedSuggestion) {
            advanceExternalSelectionRevision()
            if (railInputs.translation != TranslationFeedback.Idle) cancelTranslation()
        }
        if (!expectedSuggestion) invalidateSuggestions()
    }

    private fun setupKeyboardCallbacks(root: KeyboardRootView) {
        root.keyboardView.onKeyChar = { char ->
            if (rootView === root) handleChar(root, char)
        }
        root.keyboardView.onKeyDelete = {
            if (rootView === root) handleDelete(root)
        }
        root.keyboardView.onKeyEnter = {
            if (rootView === root) handleEnter(root)
        }
        root.keyboardView.onKeySpace = {
            if (rootView === root) handleSpace(root)
        }
        root.keyboardView.onMicClick = {
            if (rootView === root) toggleRecording()
        }
        root.keyboardView.onTranslateClick = {
            if (rootView === root) translateSelection(root)
        }
    }

    private fun setupRailCallbacks(root: KeyboardRootView) {
        root.railView.onSuggestionClick = { word ->
            if (rootView === root) acceptSuggestion(root, word)
        }
        root.railView.onClipboardTabClick = {
            if (rootView === root) onClipboardRequested()
        }
        root.railView.onTranslationRetryClick = {
            if (rootView === root) retryTranslation(root)
        }
        root.railView.onClipboardPromptClick = { id ->
            if (rootView === root) onClipboardPromptRequested(id)
        }
        root.railView.onClipboardPromptDismiss = {
            if (rootView === root) dismissClipboardPrompt()
        }
    }

    private fun retireRoot(root: KeyboardRootView?) {
        root ?: return
        root.keyboardView.onKeyChar = null
        root.keyboardView.onKeyDelete = null
        root.keyboardView.onKeyEnter = null
        root.keyboardView.onKeySpace = null
        root.keyboardView.onMicClick = null
        root.keyboardView.onTranslateClick = null
        root.railView.onSuggestionClick = null
        root.railView.onClipboardTabClick = null
        root.railView.onTranslationRetryClick = null
        root.railView.onClipboardPromptClick = null
        root.railView.onClipboardPromptDismiss = null
        if (rootView === root) {
            rootView = null
            keyboardView = null
        }
    }

    private fun onClipboardRequested() = Unit

    private fun onClipboardPromptRequested(@Suppress("UNUSED_PARAMETER") entryId: String) = Unit

    private fun dismissClipboardPrompt() = Unit

    private fun retryTranslation(root: KeyboardRootView) {
        val failedAttempt = failedTranslationAttempt ?: return
        val connection = currentInputConnection
        if (connection == null) {
            if (rootView === root && ownsCurrentTranslationError(failedAttempt)) {
                clearTranslationFeedback()
            }
            return
        }
        val owner = EditorOwner(
            sessionGeneration,
            connection,
            root,
            externalSelectionRevision,
        )
        if (!isCurrentTranslationRetry(failedAttempt, owner)) return
        val selectedText = owner.connection.getSelectedText(0)?.toString()
        if (!isCurrentTranslationRetry(failedAttempt, owner)) return
        val current = captureSelection(owner.connection, selectedText) {
            isCurrentTranslationRetry(failedAttempt, owner)
        }
        if (!isCurrentTranslationRetry(failedAttempt, owner)) return
        if (current != failedAttempt.selection) {
            clearTranslationFeedback()
            return
        }
        launchTranslation(owner, failedAttempt.selection)
    }

    private fun isCurrentTranslationRetry(
        failedAttempt: FailedTranslationAttempt,
        owner: EditorOwner,
    ): Boolean {
        return ownsCurrentTranslationError(failedAttempt) &&
            owner.connection === failedAttempt.owner.connection &&
            owner.generation == failedAttempt.owner.generation &&
            owner.selectionRevision == failedAttempt.owner.selectionRevision &&
            isCurrentEditorOwner(owner)
    }

    private fun ownsCurrentTranslationError(
        failedAttempt: FailedTranslationAttempt,
    ): Boolean {
        return failedAttempt === failedTranslationAttempt &&
            failedAttempt.attemptId == translationAttemptId &&
            failedAttempt.owner.generation == sessionGeneration &&
            failedAttempt.owner.selectionRevision == externalSelectionRevision &&
            railInputs.translation == TranslationFeedback.Error
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

    private fun handleChar(root: KeyboardRootView, char: String) {
        val owner = captureEditorOwner(root) ?: return
        val previousSelection = currentRange(owner.connection)
        if (!isCurrentEditorOwner(owner)) return
        val committed = owner.connection.commitText(char, 1)
        if (!isCurrentEditorOwner(owner)) return
        if (committed) {
            recordSuccessfulLocalMutation(
                owner,
                SuggestionMutation.CHARACTER,
                previousSelection,
            )
        } else {
            invalidateSuggestions()
        }
    }

    private fun handleDelete(root: KeyboardRootView) {
        val owner = captureEditorOwner(root) ?: return
        val previousSelection = currentRange(owner.connection)
        if (!isCurrentEditorOwner(owner)) return
        val selectedText = owner.connection.getSelectedText(0)
        if (!isCurrentEditorOwner(owner)) return
        val success = if (!selectedText.isNullOrEmpty()) {
            owner.connection.commitText("", 1)
        } else {
            owner.connection.deleteSurroundingTextInCodePoints(1, 0)
        }
        if (!isCurrentEditorOwner(owner)) return
        if (success) {
            recordSuccessfulLocalMutation(owner, SuggestionMutation.DELETE, previousSelection)
        } else {
            invalidateSuggestions()
        }
    }

    private fun handleEnter(root: KeyboardRootView) {
        val owner = captureEditorOwner(root) ?: return
        val enterAction = pendingEnterAction
        when (enterAction) {
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND,
            EditorInfo.IME_ACTION_PREVIOUS,
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE,
            -> owner.connection.performEditorAction(enterAction)

            else -> owner.connection.commitText("\n", 1)
        }
        if (!isCurrentEditorOwner(owner)) return
        invalidateSuggestions()
    }

    private fun handleSpace(root: KeyboardRootView) {
        val owner = captureEditorOwner(root) ?: return
        val previousSelection = currentRange(owner.connection)
        if (!isCurrentEditorOwner(owner)) return
        val committed = owner.connection.commitText(" ", 1)
        if (!isCurrentEditorOwner(owner)) return
        if (committed) {
            recordSuccessfulLocalMutation(owner, SuggestionMutation.SPACE, previousSelection)
        } else {
            invalidateSuggestions()
        }
    }

    private fun acceptSuggestion(root: KeyboardRootView, word: String) {
        val owner = captureEditorOwner(root) ?: return
        val snapshot = suggestionSnapshot ?: return
        if (
            snapshot.generation != owner.generation ||
            snapshot.connection !== owner.connection ||
            snapshot.root !== owner.root ||
            snapshot.selectionRevision != owner.selectionRevision ||
            word !in snapshot.suggestions
        ) {
            return
        }
        val selectedText = owner.connection.getSelectedText(0)
        if (!isCurrentEditorOwner(owner)) return
        if (!selectedText.isNullOrEmpty()) return

        val textBeforeCursor = owner.connection.getTextBeforeCursor(MAX_TEXT_CONTEXT, 0)
            ?.toString()
            ?: return
        if (!isCurrentEditorOwner(owner)) return
        if (textBeforeCursor != snapshot.textBeforeCursor) return

        val currentWord = TextContextParser.parse(textBeforeCursor).currentWord
        val cursor = captureCandidateCursor(owner.connection, currentWord)
        if (!isCurrentEditorOwner(owner)) return
        cursor ?: return
        if (cursor != snapshot.absoluteCursor) return
        val tokenStart = cursor - currentWord.length
        if (tokenStart < 0) return
        val collapsedSelection = EditorSelectionRange(cursor, cursor)
        val tokenSelection = EditorSelectionRange(tokenStart, cursor)
        if (!suggestionGate.recordExpectedSelection(collapsedSelection, tokenSelection)) {
            invalidateSuggestionsIfCurrent(owner)
            return
        }
        val selectedToken = owner.connection.setSelection(tokenStart, cursor)
        if (!isCurrentEditorOwner(owner)) return
        if (!selectedToken) {
            invalidateSuggestionsIfCurrent(owner)
            return
        }

        val committed = owner.connection.commitText("$word ", 1)
        if (!isCurrentEditorOwner(owner)) return
        if (!committed) {
            restoreCollapsedSelection(owner, cursor, tokenStart)
            invalidateSuggestionsIfCurrent(owner)
            return
        }
        recordSuccessfulLocalMutation(owner, SuggestionMutation.SUGGESTION, tokenSelection)
    }

    private fun currentRange(connection: InputConnection): EditorSelectionRange? {
        val extracted = captureExtractedSelection(connection) ?: return null
        return EditorSelectionRange(
            extracted.absoluteSelectionStart,
            extracted.absoluteSelectionEnd,
        )
    }

    private fun recordSuccessfulLocalMutation(
        owner: EditorOwner,
        mutation: SuggestionMutation,
        previousSelection: EditorSelectionRange?,
    ) {
        if (!isCurrentEditorOwner(owner)) return
        val selection = currentRange(owner.connection)
        if (!isCurrentEditorOwner(owner)) return
        suggestionGate.recordSuccessfulMutation(mutation, previousSelection, selection)
        updateSuggestions(owner)
    }

    private fun invalidateSuggestions() {
        suggestionGate.invalidate()
        suggestionSnapshot = null
        setSuggestions(emptyList())
    }

    private fun invalidateSuggestionsIfCurrent(owner: EditorOwner) {
        if (isCurrentEditorOwner(owner)) invalidateSuggestions()
    }

    private fun updateSuggestions(owner: EditorOwner) {
        if (!isCurrentEditorOwner(owner)) return
        val selection = currentRange(owner.connection)
        if (!isCurrentEditorOwner(owner)) return
        if (selection == null) return invalidateSuggestions()
        val textBeforeCursor = owner.connection.getTextBeforeCursor(MAX_TEXT_CONTEXT, 0)?.toString()
        if (!isCurrentEditorOwner(owner)) return
        val context = SuggestionPolicy.contextOrNull(
            SuggestionPolicyInput(
                textBeforeCursor = textBeforeCursor,
                selectionCollapsed = selection.isCollapsed,
                localMutationEligible = suggestionGate.allowsSuggestionsAt(selection),
                allowSuggestions = editorPrivacy.allowSuggestions,
            ),
        ) ?: return invalidateSuggestions()
        val suggestions = predictor.suggest(context.currentWord, context.lastWord)
        val absoluteCursor = captureCandidateCursor(owner.connection, context.currentWord)
        if (!isCurrentEditorOwner(owner)) return
        suggestionSnapshot = if (suggestions.isEmpty() || absoluteCursor == null) null else {
            SuggestionSnapshot(
                owner.generation,
                owner.connection,
                owner.root,
                owner.selectionRevision,
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

    private fun translateSelection(root: KeyboardRootView) {
        if (!editorPrivacy.allowTranslation) return
        val owner = captureEditorOwner(root) ?: return
        val selectedText = owner.connection.getSelectedText(0)?.toString()
        if (!isCurrentEditorOwner(owner)) return
        if (selectedText.isNullOrBlank()) {
            Toast.makeText(this, "Sélectionnez du texte d'abord", Toast.LENGTH_SHORT).show()
            return
        }
        val selection = captureSelection(owner.connection, selectedText) {
            isCurrentEditorOwner(owner)
        }
        if (!isCurrentEditorOwner(owner)) return
        if (selection == null) {
            Toast.makeText(
                this,
                "Impossible de vérifier cette sélection dans l'éditeur.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        launchTranslation(owner, selection)
    }

    private fun launchTranslation(
        editorOwner: EditorOwner,
        selection: SelectionSnapshot,
    ) {
        if (!isCurrentEditorOwner(editorOwner)) return
        val owner = TranslationOwner(
            editorOwner.generation,
            editorOwner.connection,
            editorOwner.selectionRevision,
        )
        if (
            !isCurrentTranslationOwner(owner) ||
            translationJob?.isActive == true ||
            !editorPrivacy.allowTranslation
        ) {
            return
        }
        translationFeedbackJob?.cancel()
        translationFeedbackJob = null
        val attemptId = ++translationAttemptId
        failedTranslationAttempt = null
        setTranslationFeedback(TranslationFeedback.Loading)
        val job = sessionScope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                translateText(selection.selectedText)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                RemoteResult.Failure("La traduction a échoué. Réessayez.")
            }
            applyTranslationResult(attemptId, owner, selection, result)
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
        owner: TranslationOwner,
        selection: SelectionSnapshot,
        result: RemoteResult<String>,
    ) {
        if (!continueTranslationAttempt(attemptId, owner)) return
        translationJob = null
        val selectedText = owner.connection.getSelectedText(0)?.toString()
        if (!continueTranslationAttempt(attemptId, owner)) return
        val current = captureSelection(owner.connection, selectedText) {
            continueTranslationAttempt(attemptId, owner)
        }
        if (!continueTranslationAttempt(attemptId, owner)) return
        if (current != selection) {
            clearTranslationFeedback()
            return
        }

        when (result) {
            is RemoteResult.Success -> {
                if (result.value.isBlank()) {
                    showTranslationError(
                        attemptId,
                        owner,
                        selection,
                        "Réponse de traduction vide. Vérifiez la compatibilité du serveur.",
                    )
                    return
                }

                val committed = owner.connection.commitText(result.value, 1)
                if (!continueTranslationAttempt(attemptId, owner)) return
                if (!committed) {
                    showEditorFailure()
                    showTranslationError(
                        attemptId,
                        owner,
                        selection,
                        "L’éditeur a refusé la traduction.",
                    )
                    return
                }

                val committedRange = currentRange(owner.connection)
                if (!continueTranslationAttempt(attemptId, owner)) return
                failedTranslationAttempt = null
                expectedTranslationSelectionUpdate = committedRange?.let { range ->
                    ExpectedTranslationSelectionUpdate(
                        owner,
                        EditorSelectionRange(
                            selection.absoluteSelectionStart,
                            selection.absoluteSelectionEnd,
                        ),
                        range,
                    )
                }
                invalidateSuggestions()
                setTranslationFeedback(TranslationFeedback.Success)
                scheduleTranslationClear(1_200L)
            }
            is RemoteResult.Failure -> showTranslationError(
                attemptId,
                owner,
                selection,
                result.message,
            )
        }
    }

    private fun continueTranslationAttempt(
        attemptId: Long,
        owner: TranslationOwner,
    ): Boolean {
        if (attemptId != translationAttemptId) return false
        if (isCurrentTranslationOwner(owner)) return true
        clearTranslationFeedback()
        return false
    }

    private fun showTranslationError(
        attemptId: Long,
        owner: TranslationOwner,
        selection: SelectionSnapshot,
        message: String,
    ) {
        if (!continueTranslationAttempt(attemptId, owner)) return
        failedTranslationAttempt = FailedTranslationAttempt(
            attemptId,
            owner,
            selection,
        )
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
        failedTranslationAttempt = null
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
        afterEditorRead: () -> Boolean = { true },
    ): SelectionSnapshot? {
        if (selectedText == null) return null
        val extracted = captureExtractedSelection(connection)
        if (!afterEditorRead()) return null
        if (extracted == null) return null
        val relativeStart = minOf(
            extracted.relativeSelectionStart,
            extracted.relativeSelectionEnd,
        )
        val relativeEnd = maxOf(
            extracted.relativeSelectionStart,
            extracted.relativeSelectionEnd,
        )
        if (extracted.text.substring(relativeStart, relativeEnd) != selectedText) return null
        val textBeforeSelection = connection.getTextBeforeCursor(MAX_TEXT_CONTEXT, 0)
            ?.toString()
            .orEmpty()
        if (!afterEditorRead()) return null
        val textAfterSelection = connection.getTextAfterCursor(MAX_TEXT_CONTEXT, 0)
            ?.toString()
            .orEmpty()
        if (!afterEditorRead()) return null
        return SelectionSnapshot(
            selectedText = selectedText,
            absoluteSelectionStart = extracted.absoluteSelectionStart,
            absoluteSelectionEnd = extracted.absoluteSelectionEnd,
            textBeforeSelection = textBeforeSelection,
            textAfterSelection = textAfterSelection,
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
        owner: EditorOwner,
        preferredCursor: Int,
        fallbackCursor: Int,
    ) {
        if (!isCurrentEditorOwner(owner)) return
        val restoredPreferred = owner.connection.setSelection(preferredCursor, preferredCursor)
        if (!isCurrentEditorOwner(owner)) return
        if (!restoredPreferred) {
            owner.connection.setSelection(fallbackCursor, fallbackCursor)
        }
    }

    private fun consumeExpectedTranslationSelectionUpdate(
        previousSelection: EditorSelectionRange,
        selection: EditorSelectionRange,
    ): Boolean {
        val expected = expectedTranslationSelectionUpdate ?: return false
        val matches = isCurrentTranslationOwner(expected.owner) &&
            expected.previousSelection == previousSelection &&
            expected.selection == selection
        if (matches) expectedTranslationSelectionUpdate = null
        return matches
    }

    private fun captureEditorOwner(root: KeyboardRootView): EditorOwner? {
        if (rootView !== root) return null
        val connection = currentInputConnection ?: return null
        val owner = EditorOwner(
            sessionGeneration,
            connection,
            root,
            externalSelectionRevision,
        )
        return owner.takeIf(::isCurrentEditorOwner)
    }

    private fun isCurrentEditorOwner(owner: EditorOwner): Boolean {
        return owner.generation == sessionGeneration &&
            owner.connection === currentInputConnection &&
            owner.root === rootView &&
            owner.selectionRevision == externalSelectionRevision
    }

    private fun isCurrentTranslationOwner(owner: TranslationOwner): Boolean {
        return owner.generation == sessionGeneration &&
            owner.connection === currentInputConnection &&
            owner.selectionRevision == externalSelectionRevision
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
        advanceExternalSelectionRevision()
        sessionGeneration += 1
        sessionJob.cancel()
        deletePendingAudioFiles()
        sessionJob = SupervisorJob(serviceJob)
        sessionScope = CoroutineScope(sessionJob + Dispatchers.Main.immediate)
        suggestionGate.invalidate()
        suggestionSnapshot = null
        setSuggestions(emptyList())
    }

    private fun advanceExternalSelectionRevision() {
        if (externalSelectionRevision == Long.MAX_VALUE) {
            // Pair the wrapped revision with a new generation so no retired owner can match.
            sessionGeneration += 1
            externalSelectionRevision = 0L
        } else {
            externalSelectionRevision += 1
        }
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
        retireRoot(rootView)
        serviceScope.cancel()
        deletePendingAudioFiles()
        suggestionSnapshot = null
        super.onDestroy()
    }

    private companion object {
        const val MAX_TEXT_CONTEXT = 10_000
    }
}
