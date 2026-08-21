package ovh.jefe.keyboard.clipboard

import android.net.Uri
import android.util.Base64
import java.io.Closeable
import java.security.SecureRandom

internal data class ClipboardGrantPayload(
    val entryId: String,
    val itemIndex: Int,
    val mimeType: String,
    val plainByteSize: Long,
    val sensitive: Boolean,
)

internal class IssuedClipboardGrant(
    val token: String,
    val uri: Uri,
) {
    override fun toString(): String = "IssuedClipboardGrant(redacted)"
}

internal class ClipboardGrantLease internal constructor(
    val payload: ClipboardGrantPayload,
    private val onClose: (ClipboardGrantLease) -> Unit,
) : Closeable {
    @Volatile var isRevoked: Boolean = false
        internal set
    override fun close() = onClose(this)
}

internal class ClipboardGrantRegistry(
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val random: SecureRandom = SecureRandom(),
    private val authority: String = "ovh.jefe.keyboard.clipboard",
) {
    private class Record(
        val token: String,
        val payload: ClipboardGrantPayload,
        val uid: Int,
        val packageName: String,
        val sessionId: Long,
        val expiresAt: Long,
    ) {
        var opens = 0
        var revoked = false
        val leases = LinkedHashSet<ClipboardGrantLease>()
    }

    private val records = LinkedHashMap<String, Record>()

    @Synchronized
    fun issue(
        payload: ClipboardGrantPayload,
        uid: Int,
        packageName: String,
        sessionId: Long,
    ): IssuedClipboardGrant {
        require(uid >= 0 && packageName.isNotBlank() && sessionId >= 0)
        purgeExpired()
        var token: String
        do {
            val bytes = ByteArray(16).also(random::nextBytes)
            token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } while (records.containsKey(token))
        records[token] = Record(
            token,
            payload,
            uid,
            packageName,
            sessionId,
            clock() + ClipboardLimits.GRANT_WINDOW_MILLIS,
        )
        return IssuedClipboardGrant(token, Uri.parse("content://$authority/$token"))
    }

    @Synchronized
    fun acquire(
        token: String,
        uid: Int,
        packageName: String,
        sessionId: Long,
    ): ClipboardGrantLease? {
        purgeExpired()
        val record = records[token] ?: return null
        if (record.revoked || record.uid != uid || record.packageName != packageName || record.sessionId != sessionId) return null
        if (record.opens >= ClipboardLimits.MAX_GRANT_OPENS) return null
        record.opens += 1
        lateinit var lease: ClipboardGrantLease
        lease = ClipboardGrantLease(record.payload) { closeLease(token, it) }
        record.leases += lease
        return lease
    }

    @Synchronized
    fun revokeToken(token: String) = revoke(records[token])

    @Synchronized
    fun revokeSession(sessionId: Long) = records.values.filter { it.sessionId == sessionId }.forEach(::revoke)

    @Synchronized
    fun revokeEntry(entryId: String) = records.values.filter { it.payload.entryId == entryId }.forEach(::revoke)

    @Synchronized
    fun revokeAll() = records.values.toList().forEach(::revoke)

    @Synchronized
    internal fun activeCount(): Int {
        purgeExpired()
        return records.values.count { !it.revoked }
    }

    @Synchronized
    private fun closeLease(token: String, lease: ClipboardGrantLease) {
        val record = records[token] ?: return
        record.leases.remove(lease)
        if (record.revoked || record.opens >= ClipboardLimits.MAX_GRANT_OPENS) {
            if (record.leases.isEmpty()) records.remove(token)
        }
    }

    private fun purgeExpired() {
        records.values.filter { clock() > it.expiresAt }.toList().forEach(::revoke)
    }

    private fun revoke(record: Record?) {
        if (record == null) return
        record.revoked = true
        record.leases.forEach { it.isRevoked = true }
        if (record.leases.isEmpty()) records.remove(record.token)
    }
}
