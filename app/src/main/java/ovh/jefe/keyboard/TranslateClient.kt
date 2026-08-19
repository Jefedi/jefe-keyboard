package ovh.jefe.keyboard

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client pour LibreTranslate.
 * POST {url}/translate avec {q, source, target, api_key?}
 * URL et clé API configurables dans les paramètres de l'app.
 */
class TranslateClient(
    private val url: String,
    private val apiKey: String,
    private val sourceLang: String,
    private val targetLang: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Translate text.
     * @param text text to translate
     * @return translated text, or null on error
     */
    fun translate(text: String): String? {
        val endpoint = url.trimEnd('/') + "/translate"
        Log.d("TranslateClient", "Translating ${text.length} chars to $targetLang via $endpoint")

        val jsonBody = JSONObject().apply {
            put("q", text)
            put("source", sourceLang)
            put("target", targetLang)
            put("format", "text")
            if (apiKey.isNotEmpty()) {
                put("api_key", apiKey)
            }
        }

        val request = Request.Builder()
            .url(endpoint)
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("TranslateClient", "HTTP ${response.code}: ${response.body?.string()}")
                    null
                } else {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    json.optString("translatedText", "").ifEmpty { null }
                }
            }
        } catch (e: Exception) {
            Log.e("TranslateClient", "Error: ${e.message}", e)
            null
        }
    }
}