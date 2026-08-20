package ovh.jefe.keyboard

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response

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

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    val terminal = AtomicBoolean(false)
    continuation.invokeOnCancellation {
        terminal.compareAndSet(false, true)
        cancel()
    }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (terminal.compareAndSet(false, true)) {
                continuation.resumeWith(Result.failure(error))
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (terminal.compareAndSet(false, true)) {
                continuation.resume(response) { response.close() }
            } else {
                response.close()
            }
        }
    })
}
