package ovh.jefe.keyboard

import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class TranslateClientTest {
    private lateinit var server: MockWebServer
    private lateinit var trustedClient: OkHttpClient

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()

        server = MockWebServer().apply {
            useHttps(serverCertificates.sslSocketFactory(), false)
        }
        trustedClient = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `posts translation JSON below the configured base path`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"translatedText":"Bonjour traduit"}"""),
        )
        val client = TranslateClient(
            url = server.url("/private/services/").toString(),
            apiKey = "clé-secrète",
            sourceLang = "auto",
            targetLang = "fr",
            client = trustedClient,
        )

        val result = client.translate("Hello world")
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val body = JSONObject(request.body.readUtf8())

        assertEquals("POST", request.method)
        assertEquals("/private/services/translate", request.path)
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("Hello world", body.getString("q"))
        assertEquals("auto", body.getString("source"))
        assertEquals("fr", body.getString("target"))
        assertEquals("text", body.getString("format"))
        assertEquals("clé-secrète", body.getString("api_key"))
        assertEquals(RemoteResult.Success("Bonjour traduit"), result)
    }

    @Test
    fun `returns a failure for a malformed translation response`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("not-json"),
        )
        val client = TranslateClient(
            url = server.url("/api/").toString(),
            apiKey = "",
            sourceLang = "fr",
            targetLang = "en",
            client = trustedClient,
        )

        val result = client.translate("Bonjour")

        assertTrue(result is RemoteResult.Failure)
        assertTrue((result as RemoteResult.Failure).message.contains("réponse", ignoreCase = true))
        assertTrue(result.cause != null)
    }

    @Test
    fun `rejects malformed and cleartext translation URLs without throwing`() {
        listOf("pas une URL", "http://127.0.0.1:65535/private/").forEach { url ->
            val result = TranslateClient(url, "", "auto", "fr", trustedClient)
                .translate("Bonjour")

            assertTrue(result is RemoteResult.Failure)
        }
    }

    @Test
    fun `blocks an injected interceptor from rewriting translation to HTTP`() {
        val cleartextServer = MockWebServer()
        cleartextServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"translatedText":"Texte divulgué"}"""),
        )
        val rewritingClient = trustedClient.newBuilder()
            .addInterceptor { chain ->
                val cleartextRequest = chain.request().newBuilder()
                    .url(cleartextServer.url("/leak"))
                    .build()
                chain.proceed(cleartextRequest)
            }
            .build()
        val client = TranslateClient(
            url = server.url("/private/").toString(),
            apiKey = "",
            sourceLang = "fr",
            targetLang = "en",
            client = rewritingClient,
        )

        try {
            val result = client.translate("Texte privé")

            assertTrue(result is RemoteResult.Failure)
            assertTrue((result as RemoteResult.Failure).message.contains("HTTPS"))
            assertNull(cleartextServer.takeRequest(250, TimeUnit.MILLISECONDS))
        } finally {
            cleartextServer.shutdown()
        }
    }

    @Test
    fun `rethrows translation cancellation`() {
        val cancellation = CancellationException("traduction annulée")
        val cancellingClient = trustedClient.newBuilder()
            .addInterceptor { throw cancellation }
            .build()
        val client = TranslateClient(
            url = server.url("/private/").toString(),
            apiKey = "",
            sourceLang = "fr",
            targetLang = "en",
            client = cancellingClient,
        )

        val thrown = assertThrows(CancellationException::class.java) {
            client.translate("Texte privé")
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `continues to follow redirects within HTTPS`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/translated")),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"translatedText":"Redirection sûre"}"""),
        )
        val client = TranslateClient(
            url = server.url("/private/").toString(),
            apiKey = "",
            sourceLang = "fr",
            targetLang = "en",
            client = trustedClient,
        )

        val result = client.translate("Bonjour")
        requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
        val redirectedRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))

        assertEquals("/translated", redirectedRequest.path)
        assertEquals(RemoteResult.Success("Redirection sûre"), result)
    }
}
