package ovh.jefe.keyboard

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Client pour envoyer un fichier audio à un serveur Whisper (OpenAI-compatible /v1/audio/transcriptions).
 * URL et clé API configurables dans les paramètres de l'app.
 */
class WhisperClient(
    private val url: String,
    private val apiKey: String,
    private val model: String,
    client: OkHttpClient = defaultClient(),
) {
    private val client = client.enforceHttpsTransport()

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Transcribe an audio file.
     * @param audioFile WAV or M4A file to send
     * @param language optional language hint (e.g. "fr")
     * @return transcribed text or an actionable failure
     */
    fun transcribe(audioFile: File, language: String? = null): RemoteResult<String> {
        return try {
            val baseUrl = when (val parsed = ServiceEndpoint.parse(url)) {
                is RemoteResult.Success -> parsed.value
                is RemoteResult.Failure -> return parsed
            }
            val endpoint = baseUrl.newBuilder()
                .addPathSegments("v1/audio/transcriptions")
                .build()
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    audioFile.name,
                    audioFile.asRequestBody("audio/*".toMediaType()),
                )
                .addFormDataPart("model", model)

            if (language != null) {
                multipart.addFormDataPart("language", language)
            }

            val requestBuilder = Request.Builder()
                .url(endpoint)
                .post(multipart.build())

            if (apiKey.isNotEmpty()) {
                requestBuilder.header("Authorization", "Bearer $apiKey")
            }

            client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val redirect = response.header("Location")
                        ?.let(response.request.url::resolve)
                    if (response.isRedirect && redirect?.isHttps == false) {
                        RemoteResult.Failure(
                            "Redirection non sécurisée refusée. " +
                                "Configurez une destination HTTPS.",
                        )
                    } else {
                        RemoteResult.Failure(
                            "Le serveur de transcription a répondu avec le code ${response.code}. " +
                                "Vérifiez son adresse et sa configuration.",
                        )
                    }
                } else {
                    val text = JSONObject(response.body?.string().orEmpty())
                        .optString("text", "")
                        .trim()
                    if (text.isEmpty()) {
                        RemoteResult.Failure(
                            "Réponse de transcription vide. Vérifiez la compatibilité du serveur.",
                        )
                    } else {
                        RemoteResult.Success(text)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InsecureTransportException) {
            RemoteResult.Failure(e.message.orEmpty(), e)
        } catch (e: Exception) {
            RemoteResult.Failure(
                "Réponse du serveur de transcription invalide ou inaccessible. " +
                    "Vérifiez l’adresse HTTPS et le réseau.",
                e,
            )
        }
    }
}