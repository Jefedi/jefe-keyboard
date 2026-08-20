package ovh.jefe.keyboard

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.concurrent.CancellationException
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
            val result = runBlocking { client.transcribe(audioFile, language = "fr") }
            val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            val contentType = requireNotNull(request.getHeader("Content-Type"))
            val body = request.body.readUtf8()

            assertEquals("POST", request.method)
            assertEquals("/private/speech/v1/audio/transcriptions", request.path)
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            assertEquals("Bearer secret-token", request.getHeader("Authorization"))
            assertTrue(body.contains("name=\"file\"; filename=\"${audioFile.name}\""))
            assertTrue(body.contains("Content-Type: audio/mp4"))
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
                val result = runBlocking {
                    WhisperClient(url, "", "whisper-1", trustedClient)
                        .transcribe(audioFile, language = "fr")
                }

                assertTrue(result is RemoteResult.Failure)
            }
        } finally {
            audioFile.delete()
        }
    }

    @Test
    fun `does not follow a Whisper redirect from HTTPS to HTTP`() {
        val cleartextServer = MockWebServer()
        cleartextServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"text":"Audio divulgué"}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", cleartextServer.url("/leak")),
        )
        val audioFile = Files.createTempFile("jefe-whisper", ".m4a").toFile()
        audioFile.writeText("contenu-audio")
        val client = WhisperClient(
            url = server.url("/private/").toString(),
            apiKey = "",
            model = "whisper-1",
            client = trustedClient,
        )

        try {
            val result = runBlocking { client.transcribe(audioFile, language = "fr") }

            assertTrue(result is RemoteResult.Failure)
            assertTrue((result as RemoteResult.Failure).message.contains("HTTPS"))
            assertNull(cleartextServer.takeRequest(250, TimeUnit.MILLISECONDS))
        } finally {
            audioFile.delete()
            cleartextServer.shutdown()
        }
    }

    @Test
    fun `cancelling transcription cancels the actual stalled HTTP call`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val cancellation = CancellationException("transcription annulée")
        val started = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val actualCall = AtomicReference<Call>()
        val observingClient = trustedClient.newBuilder()
            .readTimeout(1, TimeUnit.SECONDS)
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    actualCall.set(call)
                    started.countDown()
                }

                override fun callFailed(call: Call, ioe: IOException) {
                    failed.countDown()
                }
            })
            .build()
        val audioFile = Files.createTempFile("jefe-whisper", ".m4a").toFile()
        audioFile.writeText("contenu-audio")
        val client = WhisperClient(
            url = server.url("/private/").toString(),
            apiKey = "",
            model = "whisper-1",
            client = observingClient,
        )

        try {
            val deferred = async(Dispatchers.IO) {
                client.transcribe(audioFile, language = "fr")
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            deferred.cancel(cancellation)
            val thrown = try {
                deferred.await()
                null
            } catch (error: CancellationException) {
                error
            }

            assertEquals(cancellation.message, thrown?.message)
            assertTrue(failed.await(2, TimeUnit.SECONDS))
            assertTrue("The OkHttp Call must be marked cancelled", actualCall.get().isCanceled())
            assertEquals(0, observingClient.dispatcher.runningCallsCount())
        } finally {
            audioFile.delete()
        }
    }
}
