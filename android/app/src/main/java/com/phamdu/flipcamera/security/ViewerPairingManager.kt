package com.phamdu.flipcamera.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Semaphore

internal data class PairingCode(val digits: String, val expiresAtUtc: Long)

internal data class PairingRequestSummary(
    val requestId: String,
    val viewerName: String,
    val remoteAddress: String,
    val requestedAtUtc: Long,
    val expiresAtUtc: Long,
)

internal sealed interface PairingSubmission {
    data class Waiting(val requestId: String) : PairingSubmission
    data object InvalidCode : PairingSubmission
    data object ExpiredCode : PairingSubmission
    data object RateLimited : PairingSubmission
    data object CapacityReached : PairingSubmission
}

internal sealed interface PairingPoll {
    data object Waiting : PairingPoll
    data class Approved(val sessionToken: String) : PairingPoll
    data object Completed : PairingPoll
    data object Rejected : PairingPoll
    data object Expired : PairingPoll
    data object Unknown : PairingPoll
}

internal enum class PairingApproval {
    APPROVED,
    REJECTED,
    NOT_FOUND,
    EXPIRED,
    ALREADY_HANDLED,
    CAPACITY_REACHED,
}

internal data class ViewerSessionIdentity(val id: String, val viewerName: String)

/** In-memory one-time pairing and read-only viewer session state. */
internal class ViewerPairingManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val monotonicClock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val random: SecureRandom = SecureRandom(),
    private val maxViewers: Int = MAX_VIEWERS,
    private val passwordStore: ViewerPasswordStore? = null,
    private val passwordVerifier: (ViewerPassword, String) -> Boolean = ViewerPassword::matches,
) {
    private data class CodeState(
        val digest: ByteArray,
        val deadlineElapsed: Long,
        val expiresAtUtc: Long,
    )

    private enum class RequestState { PENDING, APPROVED, COMPLETED, REJECTED }

    private data class RequestStateRecord(
        val id: String,
        val viewerName: String,
        val remoteAddress: String,
        val requestedAtUtc: Long,
        var deadlineElapsed: Long,
        var expiresAtUtc: Long,
        var state: RequestState = RequestState.PENDING,
        var sessionToken: String? = null,
        var sessionDigestKey: String? = null,
    )

    private data class SessionRecord(
        val id: String,
        val digest: ByteArray,
        val viewerName: String,
        val pairingRequestId: String,
        val deadlineElapsed: Long,
        var lastSeenElapsed: Long,
    )

    private val failedAttempts = linkedMapOf<String, MutableList<Long>>()
    private val requests = linkedMapOf<String, RequestStateRecord>()
    private val expiredRequestIds = linkedSetOf<String>()
    private val sessionsByDigest = linkedMapOf<String, SessionRecord>()
    private var activeCode: CodeState? = null
    private var password = passwordStore?.read()
    private val passwordFailures = mutableListOf<Long>()
    private val passwordLoginPermit = Semaphore(1)
    private var authenticationGeneration = 0L

    @Synchronized
    fun hasPassword(): Boolean = password != null

    /** Local owner only; the HTTP API deliberately has no password administration route. */
    @Synchronized
    fun setPassword(value: ViewerPassword?) {
        checkNotNull(passwordStore).write(value)
        password = value
        reset()
    }

    fun loginPassword(value: String, requestedViewerName: String): PasswordLogin {
        if (!passwordLoginPermit.tryAcquire()) return PasswordLogin.Busy
        try {
            val (verifier, generation) = synchronized(this) {
                val current = password ?: return PasswordLogin.NotConfigured
                val now = monotonicClock()
                passwordFailures.removeAll { it <= now - FAILURE_WINDOW_MS }
                if (passwordFailures.size >= MAX_FAILURES) return PasswordLogin.RateLimited
                current to authenticationGeneration
            }
            // Expensive derivation must not lock session checks or the native UI.
            val matches = passwordVerifier(verifier, value)
            return synchronized(this) {
                if (password !== verifier || generation != authenticationGeneration) return PasswordLogin.InvalidPassword
                val now = monotonicClock()
                if (!matches) {
                    passwordFailures += now
                    return if (passwordFailures.size >= MAX_FAILURES) PasswordLogin.RateLimited else PasswordLogin.InvalidPassword
                }
                if (activeSessionsCount(now) >= maxViewers) return PasswordLogin.CapacityReached
                passwordFailures.clear()
                val token = randomToken(SESSION_TOKEN_BYTES)
                val digest = sha256(token)
                sessionsByDigest[encode(digest)] = SessionRecord(
                    id = randomToken(SESSION_ID_BYTES), digest = digest,
                    viewerName = sanitizeViewerName(requestedViewerName), pairingRequestId = "",
                    deadlineElapsed = now + ABSOLUTE_SESSION_TTL_MS, lastSeenElapsed = now,
                )
                PasswordLogin.Approved(token)
            }
        } finally {
            passwordLoginPermit.release()
        }
    }

    init {
        require(maxViewers in 1..MAX_VIEWERS)
    }

    @Synchronized
    fun createPairingCode(): PairingCode {
        val nowUtc = clock()
        val nowElapsed = monotonicClock()
        expireRequests(nowElapsed)
        val digits = random.nextInt(CODE_SPACE).toString().padStart(CODE_DIGITS, '0')
        val expiresAtUtc = nowUtc + PAIRING_TTL_MS
        activeCode = CodeState(sha256(digits), nowElapsed + PAIRING_TTL_MS, expiresAtUtc)
        return PairingCode(digits, expiresAtUtc)
    }

    @Synchronized
    fun submitPairingCode(code: String, remoteAddress: String, requestedViewerName: String): PairingSubmission {
        val nowUtc = clock()
        val nowElapsed = monotonicClock()
        expireRequests(nowElapsed)
        expireSessions(nowElapsed)
        val address = remoteAddress.trim().take(MAX_ADDRESS_CHARS).ifEmpty { "unknown" }
        failedAttempts.values.forEach { attempts -> attempts.removeAll { it <= nowElapsed - FAILURE_WINDOW_MS } }
        failedAttempts.entries.removeAll { it.value.isEmpty() }
        val attempts = failedAttempts.remove(address) ?: mutableListOf()
        attempts.removeAll { it <= nowElapsed - FAILURE_WINDOW_MS }
        failedAttempts[address] = attempts
        while (failedAttempts.size > MAX_TRACKED_ADDRESSES) {
            failedAttempts.remove(failedAttempts.keys.first())
        }
        if (attempts.size >= MAX_FAILURES) return PairingSubmission.RateLimited
        if (activeSessionsCount(nowElapsed) >= maxViewers) return PairingSubmission.CapacityReached

        val currentCode = activeCode
        if (currentCode == null || nowElapsed >= currentCode.deadlineElapsed) {
            activeCode = null
            return if (recordFailure(attempts, nowElapsed)) PairingSubmission.RateLimited
            else PairingSubmission.ExpiredCode
        }
        val suppliedDigest = sha256(code)
        if (!MessageDigest.isEqual(currentCode.digest, suppliedDigest)) {
            return if (recordFailure(attempts, nowElapsed)) PairingSubmission.RateLimited
            else PairingSubmission.InvalidCode
        }
        if (requests.values.count { it.state == RequestState.PENDING && nowElapsed < it.deadlineElapsed } >= MAX_PENDING_REQUESTS) {
            return PairingSubmission.CapacityReached
        }

        activeCode = null
        failedAttempts.remove(address)
        val requestId = randomToken(REQUEST_TOKEN_BYTES)
        val record = RequestStateRecord(
            id = requestId,
            viewerName = sanitizeViewerName(requestedViewerName),
            remoteAddress = address,
            requestedAtUtc = nowUtc,
            deadlineElapsed = nowElapsed + PAIRING_TTL_MS,
            expiresAtUtc = nowUtc + PAIRING_TTL_MS,
        )
        requests[requestId] = record
        return PairingSubmission.Waiting(requestId)
    }

    @Synchronized
    fun pendingRequests(): List<PairingRequestSummary> {
        val nowElapsed = monotonicClock()
        expireRequests(nowElapsed)
        return requests.values
            .filter { it.state == RequestState.PENDING && nowElapsed < it.deadlineElapsed }
            .map {
                PairingRequestSummary(
                    requestId = it.id,
                    viewerName = it.viewerName,
                    remoteAddress = it.remoteAddress,
                    requestedAtUtc = it.requestedAtUtc,
                    expiresAtUtc = it.expiresAtUtc,
                )
            }
    }

    @Synchronized
    fun approve(requestId: String): PairingApproval {
        val nowElapsed = monotonicClock()
        val nowUtc = clock()
        expireRequests(nowElapsed)
        expireSessions(nowElapsed)
        val request = requests[requestId] ?: return if (requestId in expiredRequestIds) {
            PairingApproval.EXPIRED
        } else {
            PairingApproval.NOT_FOUND
        }
        if (nowElapsed >= request.deadlineElapsed) {
            expireRequest(request)
            return PairingApproval.EXPIRED
        }
        if (request.state != RequestState.PENDING) return PairingApproval.ALREADY_HANDLED
        if (activeSessionsCount(nowElapsed) >= maxViewers) return PairingApproval.CAPACITY_REACHED

        val sessionToken = randomToken(SESSION_TOKEN_BYTES)
        val sessionDigest = sha256(sessionToken)
        val sessionId = randomToken(SESSION_ID_BYTES)
        val digestKey = encode(sessionDigest)
        sessionsByDigest[digestKey] = SessionRecord(
            id = sessionId,
            digest = sessionDigest,
            viewerName = request.viewerName,
            pairingRequestId = request.id,
            deadlineElapsed = nowElapsed + ABSOLUTE_SESSION_TTL_MS,
            lastSeenElapsed = nowElapsed,
        )
        request.sessionToken = sessionToken
        request.sessionDigestKey = digestKey
        // Give the browser a fresh delivery window after local approval. Approval may
        // happen just before the original pairing request deadline.
        request.deadlineElapsed = nowElapsed + PAIRING_TTL_MS
        request.expiresAtUtc = nowUtc + PAIRING_TTL_MS
        request.state = RequestState.APPROVED
        return PairingApproval.APPROVED
    }

    @Synchronized
    fun reject(requestId: String): PairingApproval {
        val nowElapsed = monotonicClock()
        expireRequests(nowElapsed)
        val request = requests[requestId] ?: return if (requestId in expiredRequestIds) {
            PairingApproval.EXPIRED
        } else {
            PairingApproval.NOT_FOUND
        }
        if (nowElapsed >= request.deadlineElapsed) {
            expireRequest(request)
            return PairingApproval.EXPIRED
        }
        if (request.state != RequestState.PENDING) return PairingApproval.ALREADY_HANDLED
        request.state = RequestState.REJECTED
        return PairingApproval.REJECTED
    }

    @Synchronized
    fun poll(requestId: String): PairingPoll {
        val nowElapsed = monotonicClock()
        expireRequests(nowElapsed)
        val request = requests[requestId] ?: return if (requestId in expiredRequestIds) {
            PairingPoll.Expired
        } else {
            PairingPoll.Unknown
        }
        if (nowElapsed >= request.deadlineElapsed) {
            expireRequest(request)
            return PairingPoll.Expired
        }
        return when (request.state) {
            RequestState.PENDING -> PairingPoll.Waiting
            RequestState.REJECTED -> PairingPoll.Rejected
            RequestState.APPROVED -> request.sessionToken?.let(PairingPoll::Approved) ?: PairingPoll.Completed
            RequestState.COMPLETED -> PairingPoll.Completed
        }
    }

    @Synchronized
    fun authenticate(sessionToken: String): ViewerSessionIdentity? {
        val nowElapsed = monotonicClock()
        expireRequests(nowElapsed)
        expireSessions(nowElapsed)
        if (sessionToken.length !in MIN_TOKEN_CHARS..MAX_TOKEN_CHARS) return null
        val digest = sha256(sessionToken)
        val digestKey = encode(digest)
        val record = sessionsByDigest[digestKey] ?: return null
        if (!MessageDigest.isEqual(record.digest, digest)) return null
        if (nowElapsed >= record.deadlineElapsed || nowElapsed - record.lastSeenElapsed >= IDLE_SESSION_TTL_MS) {
            removeSession(digestKey)
            return null
        }
        record.lastSeenElapsed = nowElapsed
        completeSessionDelivery(record)
        return ViewerSessionIdentity(record.id, record.viewerName)
    }

    @Synchronized
    fun logout(sessionToken: String): Boolean {
        if (sessionToken.length !in MIN_TOKEN_CHARS..MAX_TOKEN_CHARS) return false
        return removeSession(encode(sha256(sessionToken))) != null
    }

    @Synchronized
    fun revokeSession(sessionId: String): Boolean {
        val entry = sessionsByDigest.entries.firstOrNull { it.value.id == sessionId } ?: return false
        removeSession(entry.key)
        return true
    }

    @Synchronized
    fun reset() {
        authenticationGeneration += 1
        activeCode = null
        failedAttempts.clear()
        requests.clear()
        expiredRequestIds.clear()
        sessionsByDigest.clear()
        passwordFailures.clear()
    }

    @Synchronized
    fun activeViewerCount(): Int {
        val nowElapsed = monotonicClock()
        expireRequests(nowElapsed)
        expireSessions(nowElapsed)
        return sessionsByDigest.size
    }

    private fun activeSessionsCount(nowElapsed: Long): Int {
        expireRequests(nowElapsed)
        expireSessions(nowElapsed)
        return sessionsByDigest.size
    }

    private fun recordFailure(attempts: MutableList<Long>, now: Long): Boolean {
        attempts += now
        return attempts.size >= MAX_FAILURES
    }

    private fun expireSessions(nowElapsed: Long) {
        val expired = sessionsByDigest.entries
            .filter { (_, session) ->
                nowElapsed >= session.deadlineElapsed || nowElapsed - session.lastSeenElapsed >= IDLE_SESSION_TTL_MS
            }
            .map { it.key to it.value }
        expired.forEach { (digestKey, _) -> removeSession(digestKey) }
    }

    private fun expireRequests(nowElapsed: Long) {
        requests.values.filter { nowElapsed >= it.deadlineElapsed }.toList().forEach(::expireRequest)
        while (expiredRequestIds.size > MAX_EXPIRED_REQUEST_IDS) {
            expiredRequestIds.remove(expiredRequestIds.first())
        }
    }

    private fun expireRequest(request: RequestStateRecord) {
        if (request.state == RequestState.APPROVED) {
            request.sessionDigestKey?.let(sessionsByDigest::remove)
        }
        request.sessionToken = null
        request.sessionDigestKey = null
        requests.remove(request.id)
        expiredRequestIds += request.id
    }

    private fun removeSession(digestKey: String): SessionRecord? {
        val session = sessionsByDigest.remove(digestKey) ?: return null
        completeSessionDelivery(session)
        return session
    }

    private fun completeSessionDelivery(session: SessionRecord) {
        val request = requests[session.pairingRequestId] ?: return
        if (request.state == RequestState.APPROVED) {
            request.sessionToken = null
            request.sessionDigestKey = null
            request.state = RequestState.COMPLETED
        }
    }

    private fun sanitizeViewerName(value: String): String = value
        .filterNot(Char::isISOControl)
        .trim()
        .take(MAX_VIEWER_NAME_CHARS)
        .ifEmpty { "Viewer" }

    private fun randomToken(byteCount: Int): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(byteCount).also(random::nextBytes))

    private fun sha256(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    private fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    companion object {
        const val PAIRING_TTL_MS = 2 * 60 * 1000L
        const val FAILURE_WINDOW_MS = 5 * 60 * 1000L
        const val IDLE_SESSION_TTL_MS = 24 * 60 * 60 * 1000L
        const val ABSOLUTE_SESSION_TTL_MS = 7 * 24 * 60 * 60 * 1000L
        private const val CODE_DIGITS = 8
        private const val CODE_SPACE = 100_000_000
        private const val MAX_FAILURES = 5
        const val MAX_VIEWERS = 16
        private const val MAX_PENDING_REQUESTS = 16
        private const val MAX_TRACKED_ADDRESSES = 512
        private const val MAX_EXPIRED_REQUEST_IDS = 128
        private const val MAX_ADDRESS_CHARS = 128
        private const val MAX_VIEWER_NAME_CHARS = 64
        private const val REQUEST_TOKEN_BYTES = 32
        private const val SESSION_TOKEN_BYTES = 32
        private const val SESSION_ID_BYTES = 16
        private const val MIN_TOKEN_CHARS = 22
        private const val MAX_TOKEN_CHARS = 64
    }
}
