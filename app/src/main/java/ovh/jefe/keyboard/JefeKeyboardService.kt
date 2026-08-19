package ovh.jefe.keyboard

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * IME Service — connecte KeyboardView → InputConnection + Whisper + LibreTranslate.
 */
class JefeKeyboardService : InputMethodService(), CoroutineScope by MainScope() {

    private lateinit var keyboardView: KeyboardView
    private lateinit var predictor: FrenchPredictor

    private var currentWord = StringBuilder()
    private var lastWord: String? = null
    private var recordingMode = false
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null

    override fun onCreate() {
        super.onCreate()
        predictor = FrenchPredictor()
    }

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this)
        setupKeyboardCallbacks()
        return keyboardView
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // Set enter key action based on field type
        keyboardView.enterAction = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_UNSPECIFIED
    }

    private fun setupKeyboardCallbacks() {
        keyboardView.onKeyChar = { char -> handleChar(char) }
        keyboardView.onKeyDelete = { handleDelete() }
        keyboardView.onKeyEnter = { handleEnter() }
        keyboardView.onKeySpace = { handleSpace() }
        keyboardView.onMicClick = { toggleRecording() }
        keyboardView.onTranslateClick = { translateSelection() }
        keyboardView.onSuggestionClick = { word -> acceptSuggestion(word) }
    }

    // ─── Text input ───
    private fun handleChar(char: String) {
        currentWord.append(char)
        currentInputConnection.commitText(char, 1)
        updateSuggestions()
    }

    private fun handleDelete() {
        val ic = currentInputConnection ?: return
        if (currentWord.isNotEmpty()) {
            currentWord.deleteCharAt(currentWord.length - 1)
        }
        // Use deleteTextBeforeCursor for reliability
        ic.deleteSurroundingText(1, 0)
        updateSuggestions()
    }

    private fun handleEnter() {
        val ic = currentInputConnection ?: return
        val editorInfo = currentInputEditorInfo

        // Check if the field expects a specific action (Go, Search, Send, Next, Done)
        val action = editorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        when (action) {
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH,
            EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_DONE -> {
                ic.performEditorAction(action)
            }
            else -> {
                // Multi-line field or unspecified: insert newline
                ic.commitText("\n", 1)
            }
        }

        currentWord.clear()
        lastWord = null
        updateSuggestions()
    }

    private fun handleSpace() {
        val word = currentWord.toString()
        if (word.isNotEmpty()) {
            lastWord = word
            currentWord.clear()
        }
        currentInputConnection.commitText(" ", 1)
        updateSuggestions()
    }

    private fun acceptSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        if (currentWord.isNotEmpty()) {
            ic.deleteSurroundingText(currentWord.length, 0)
        }
        ic.commitText(word, 1)
        ic.commitText(" ", 1)
        lastWord = word
        currentWord.clear()
        updateSuggestions()
    }

    private fun updateSuggestions() {
        val word = currentWord.toString()
        keyboardView.suggestions = predictor.suggest(word, lastWord)
    }

    // ─── Whisper dictation ───
    private fun toggleRecording() {
        if (recordingMode) stopRecording() else startRecording()
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Ouvrez l'app Jefe Keyboard pour accorder le micro", Toast.LENGTH_LONG).show()
            val intent = android.content.Intent(this, SettingsActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return
        }

        try {
            audioFile = File(cacheDir, "dictation_${System.currentTimeMillis()}.m4a")
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(64000)
                setOutputFile(audioFile!!.absolutePath)
                prepare()
                start()
            }
            recordingMode = true
            keyboardView.isRecording = true
            Toast.makeText(this, "Parlez maintenant…", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erreur micro: ${e.message}", Toast.LENGTH_LONG).show()
            recordingMode = false
            keyboardView.isRecording = false
        }
    }

    private fun stopRecording() {
        try { recorder?.apply { stop(); release() } } catch (_: Exception) {}
        recorder = null
        recordingMode = false
        keyboardView.isRecording = false

        val file = audioFile
        if (file != null && file.exists() && file.length() > 0) {
            Toast.makeText(this, "Transcription…", Toast.LENGTH_SHORT).show()
            launch {
                val text = withContext(Dispatchers.IO) { transcribeAudio(file) }
                if (text != null) {
                    currentInputConnection.commitText(text, 1)
                    val words = text.trim().split(" ")
                    if (words.size > 1) {
                        lastWord = words.last().lowercase()
                        currentWord.clear()
                    } else if (words.size == 1 && words[0].isNotEmpty()) {
                        currentWord.clear()
                        currentWord.append(words[0])
                    }
                    updateSuggestions()
                } else {
                    Toast.makeText(this@JefeKeyboardService, "Erreur de transcription", Toast.LENGTH_LONG).show()
                }
                file.delete()
            }
        }
    }

    private fun transcribeAudio(file: File): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val url = prefs.getString("whisper_url", "") ?: return null
        if (url.isEmpty()) return null
        val apiKey = prefs.getString("whisper_api_key", "") ?: ""
        val model = prefs.getString("whisper_model", "whisper-1") ?: "whisper-1"
        return WhisperClient(url, apiKey, model).transcribe(file, language = "fr")
    }

    // ─── Translation ───
    private fun translateSelection() {
        val ic = currentInputConnection ?: return
        val selectedText = ic.getSelectedText(0)
        if (selectedText.isNullOrBlank()) {
            Toast.makeText(this, "Sélectionnez du texte d'abord", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "Traduction…", Toast.LENGTH_SHORT).show()
        launch {
            val translated = withContext(Dispatchers.IO) { translateText(selectedText.toString()) }
            if (translated != null) {
                ic.commitText(translated, 1)
                Toast.makeText(this@JefeKeyboardService, "Traduit ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@JefeKeyboardService, "Erreur de traduction", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun translateText(text: String): String? {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val url = prefs.getString("translate_url", "") ?: return null
        if (url.isEmpty()) return null
        val apiKey = prefs.getString("translate_api_key", "") ?: ""
        val source = prefs.getString("translate_source", "auto") ?: "auto"
        val target = prefs.getString("translate_target", "fr") ?: "fr"
        return TranslateClient(url, apiKey, source, target).translate(text)
    }

    // ─── Lifecycle ───
    override fun hideWindow() {
        if (recordingMode) stopRecording()
        super.hideWindow()
    }

    override fun onDestroy() {
        if (recordingMode) {
            try { recorder?.stop(); recorder?.release() } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}