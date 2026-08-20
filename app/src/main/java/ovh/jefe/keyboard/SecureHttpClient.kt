package ovh.jefe.keyboard

import okhttp3.OkHttpClient
import java.io.IOException

internal class InsecureTransportException : IOException(
    "Connexion non sécurisée refusée avant envoi. " +
        "Vérifiez que le service et ses redirections utilisent HTTPS.",
)

internal fun OkHttpClient.enforceHttpsTransport(): OkHttpClient = newBuilder()
    .followRedirects(true)
    .followSslRedirects(false)
    .addNetworkInterceptor { chain ->
        if (!chain.request().url.isHttps) {
            throw InsecureTransportException()
        }
        chain.proceed(chain.request())
    }
    .build()
