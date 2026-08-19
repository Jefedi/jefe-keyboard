package ovh.jefe.keyboard

import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class WhisperClientTest {
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
    fun `posts multipart audio below the configured base path`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"text":"  Bonjour dicté  "}"""),
        )
        val audioFile = Files.createTempFile("jefe-whisper", ".m4a").toFile()
        audioFile.writeText("contenu-audio")
        val client = WhisperClient(
            url = server.url("/private/speech/").toString(),
            apiKey = "secret-token",
            model = "whisper-test",
            client = trustedClient,
        )

        try {
            val result = client.transcribe(audioFile, language = "fr")
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            val contentType = requireNotNull(request.getHeader("Content-Type"))
            val body = request.body.readUtf8()

            assertEquals("POST", request.method)
            assertEquals("/private/speech/v1/audio/transcriptions", request.path)
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            assertEquals("Bearer secret-token", request.getHeader("Authorization"))
            assertTrue(body.contains("name=\"file\"; filename=\"${audioFile.name}\""))
            assertTrue(body.contains("contenu-audio"))
            assertTrue(body.contains("name=\"model\""))
            assertTrue(body.contains("whisper-test"))
            assertTrue(body.contains("name=\"language\""))
            assertTrue(body.contains("fr"))
            assertEquals(RemoteResult.Success("Bonjour dicté"), result)
        } finally {
            audioFile.delete()
        }
    }

    @Test
    fun `rejects malformed and cleartext whisper URLs without throwing`() {
        val audioFile = Files.createTempFile("jefe-whisper", ".m4a").toFile()
        audioFile.writeText("contenu-audio")

        try {
            listOf("pas une URL", "http://127.0.0.1:65535/private/").forEach { url ->
                val result = WhisperClient(url, "", "whisper-1", trustedClient)
                    .transcribe(audioFile, language = "fr")

                assertTrue(result is RemoteResult.Failure)
            }
        } finally {
            audioFile.delete()
        }
    }
}
