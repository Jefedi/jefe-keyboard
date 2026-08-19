package ovh.jefe.keyboard

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ServiceEndpoint {
    fun parse(raw: String): RemoteResult<HttpUrl> {
        val endpoint = raw.trim().toHttpUrlOrNull()
            ?: return RemoteResult.Failure(
                "Adresse invalide. Utilisez une URL HTTPS complète.",
            )

        return if (endpoint.isHttps) {
            RemoteResult.Success(endpoint)
        } else {
            RemoteResult.Failure("Connexion non sécurisée refusée. Utilisez HTTPS.")
        }
    }
}
