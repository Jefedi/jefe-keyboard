package ovh.jefe.keyboard

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CancellationException
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
    private val targetLang: String,
    client: OkHttpClient = defaultClient(),
) {
    private val client = client.enforceHttpsTransport()

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Translate text.
     * @param text text to translate
     * @return translated text or an actionable failure
     */
    fun translate(text: String): RemoteResult<String> {
        return try {
            val baseUrl = when (val parsed = ServiceEndpoint.parse(url)) {
                is RemoteResult.Success -> parsed.value
                is RemoteResult.Failure -> return parsed
            }
            val endpoint = baseUrl.newBuilder()
                .addPathSegments("translate")
                .build()
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

            client.newCall(request).execute().use { response ->
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
                            "Le serveur de traduction a répondu avec le code ${response.code}. " +
                                "Vérifiez son adresse et sa configuration.",
                        )
                    }
                } else {
                    val translatedText = JSONObject(response.body?.string().orEmpty())
                        .optString("translatedText", "")
                    if (translatedText.isEmpty()) {
                        RemoteResult.Failure(
                            "Réponse de traduction vide. Vérifiez la compatibilité du serveur.",
                        )
                    } else {
                        RemoteResult.Success(translatedText)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: InsecureTransportException) {
            RemoteResult.Failure(e.message.orEmpty(), e)
        } catch (e: Exception) {
            RemoteResult.Failure(
                "Réponse du serveur de traduction invalide ou inaccessible. " +
                    "Vérifiez l’adresse HTTPS et le réseau.",
                e,
            )
        }
    }
}