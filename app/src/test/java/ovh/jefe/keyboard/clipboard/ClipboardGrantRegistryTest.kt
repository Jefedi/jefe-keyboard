package ovh.jefe.keyboard.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClipboardGrantRegistryTest {
    @Test
    fun `grant is bound to uid package session and three opens`() {
        var now = 1_000L
        val registry = ClipboardGrantRegistry(clock = { now })
        val payload = ClipboardGrantPayload("entry", 0, "image/png", 4L, false)
        val grant = registry.issue(payload, uid = 42, packageName = "editor", sessionId = 7L)

        assertNull(registry.acquire(grant.token, 41, "editor", 7L))
        assertNull(registry.acquire(grant.token, 42, "other", 7L))
        assertNull(registry.acquire(grant.token, 42, "editor", 8L))
        repeat(3) {
            val lease = registry.acquire(grant.token, 42, "editor", 7L)
            assertEquals(payload, lease!!.payload)
            lease.close()
        }
        assertNull(registry.acquire(grant.token, 42, "editor", 7L))

        now += ClipboardLimits.GRANT_WINDOW_MILLIS + 1
        assertNull(registry.acquire(grant.token, 42, "editor", 7L))
    }

    @Test
    fun `tokens are unpredictable and revocation closes active leases`() {
        val registry = ClipboardGrantRegistry(clock = { 1L })
        val payload = ClipboardGrantPayload("entry", 0, "application/pdf", 10L, true)
        val first = registry.issue(payload, 1, "editor", 1L)
        val second = registry.issue(payload, 1, "editor", 1L)
        val lease = registry.acquire(first.token, 1, "editor", 1L)!!

        assertNotEquals(first.token, second.token)
        assertTrue(first.token.length >= 22)
        registry.revokeSession(1L)

        assertTrue(lease.isRevoked)
        assertNull(registry.acquire(first.token, 1, "editor", 1L))
        assertEquals(0, registry.activeCount())
    }
}
