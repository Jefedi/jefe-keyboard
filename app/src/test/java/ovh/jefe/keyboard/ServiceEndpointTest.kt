package ovh.jefe.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceEndpointTest {
    @Test
    fun `accepts an absolute https base URL`() {
        val result = ServiceEndpoint.parse(" https://voice.example.test/base/ ")

        assertTrue(result is RemoteResult.Success)
        assertEquals(
            "https://voice.example.test/base/",
            (result as RemoteResult.Success).value.toString(),
        )
    }

    @Test
    fun `rejects missing scheme malformed and cleartext URLs`() {
        listOf("voice.local", "not a url", "http://192.168.1.4:8080").forEach { rawUrl ->
            assertTrue(ServiceEndpoint.parse(rawUrl) is RemoteResult.Failure)
        }
    }
}
