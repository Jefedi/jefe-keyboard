package ovh.jefe.keyboard

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Client pour envoyer un fichier audio à un serveur Whisper (OpenAI-compatible /v1/audio/transcriptions).
 * URL et clé API configurables dans les paramètres de l'app.
 */
class WhisperClient(
    private val url: String,
    private val apiKey: String,
    private val model: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribe an audio file.
     * @param audioFile WAV or M4A file to send
     * @param language optional language hint (e.g. "fr")
     * @return transcribed text, or null on error
     */
    fun transcribe(audioFile: File, language: String? = null): String? {
        val endpoint = url.trimEnd('/') + "/v1/audio/transcriptions"
        Log.d("WhisperClient", "Sending audio to $endpoint")

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/*".toMediaType()))
            .addFormDataPart("model", model)

        if (language != null) {
            multipart.addFormDataPart("language", language)
        }

        val body = multipart.build()

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(body)

        if (apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        return try {
            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("WhisperClient", "HTTP ${response.code}: ${response.body?.string()}")
                    null
                } else {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optString("text", "").trim().ifEmpty { null }
                }
            }
        } catch (e: Exception) {
            Log.e("WhisperClient", "Error: ${e.message}", e)
            null
        }
    }
}