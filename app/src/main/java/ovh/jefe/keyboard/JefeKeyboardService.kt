package ovh.jefe.keyboard

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
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
 * IME Service — le cerveau du clavier.
 * Connecte KeyboardView → InputConnection + Whisper + LibreTranslate.
 */
class JefeKeyboardService : InputMethodService(), CoroutineScope by MainScope() {

    private lateinit var keyboardView: KeyboardView
    private lateinit var predictor: FrenchPredictor

    private var currentWord = StringBuilder()
    private var lastWord: String? = null
    private var recordingMode = false
    private var recorder: MediaRecorder? = null
    private var audioFile: File? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "JefeKeyboard"
        private const val RECORD_AUDIO_REQUEST = 100
    }

    override fun onCreate() {
        super.onCreate()
        predictor = FrenchPredictor()
    }

    override fun onCreateInputView(): View {
        keyboardView = KeyboardView(this)
        setupKeyboardCallbacks()
        return keyboardView
    }

    private fun setupKeyboardCallbacks() {
        keyboardView.onKeyChar = { char -> handleChar(char) }
        keyboardView.onKeyDelete = { handleDelete() }
        keyboardView.onKeyEnter = { handleEnter() }
        keyboardView.onKeySpace = { handleSpace() }
        keyboardView.onKeyShift = { /* handled in view */ }
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
        ic.deleteSurroundingText(1, 0)
        updateSuggestions()
    }

    private fun handleEnter() {
        currentInputConnection.commitText("\n", 1)
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
        // Delete current partial word
        if (currentWord.isNotEmpty()) {
            ic.deleteSurroundingText(currentWord.length, 0)
        }
        // Insert suggestion
        ic.commitText(word, 1)
        // Add space after
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
        if (recordingMode) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        // Check microphone permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // IME services can't request permissions directly — notify user
            Toast.makeText(this, "Accordez la permission micro à l'app Jefe Keyboard", Toast.LENGTH_LONG).show()
            // Try to request via the settings activity
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
            Log.e(TAG, "Recording start failed: ${e.message}", e)
            Toast.makeText(this, "Erreur micro: ${e.message}", Toast.LENGTH_LONG).show()
            recordingMode = false
            keyboardView.isRecording = false
        }
    }

    private fun stopRecording() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recording stop error: ${e.message}")
        }
        recorder = null
        recordingMode = false
        keyboardView.isRecording = false

        val file = audioFile
        if (file != null && file.exists() && file.length() > 0) {
            Toast.makeText(this, "Transcription…", Toast.LENGTH_SHORT).show()
            launch {
                val text = withContext(Dispatchers.IO) {
                    transcribeAudio(file)
                }
                if (text != null) {
                    // Insert transcribed text at cursor
                    currentInputConnection.commitText(text, 1)
                    // Update word buffer for predictions
                    val words = text.trim().split(" ")
                    if (words.size > 1) {
                        lastWord = words.last().lowercase()
                        currentWord.clear()
                    } else if (words.size == 1) {
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

        val client = WhisperClient(url, apiKey, model)
        return client.transcribe(file, language = "fr")
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
            val translated = withContext(Dispatchers.IO) {
                translateText(selectedText.toString())
            }
            if (translated != null) {
                // Replace selected text with translation
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

        val client = TranslateClient(url, apiKey, source, target)
        return client.translate(text)
    }

    // ─── Lifecycle ───
    override fun hideWindow() {
        if (recordingMode) {
            stopRecording()
        }
        super.hideWindow()
    }

    override fun onDestroy() {
        if (recordingMode) {
            try {
                recorder?.stop()
                recorder?.release()
            } catch (_: Exception) {}
        }
        super.onDestroy()
    }
}