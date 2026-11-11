package com.phamdu.flipcamera.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerPairingManagerTest {
    private var wallNow = 1_800_000_000_000L
    private var elapsedNow = 100L
    private val manager = ViewerPairingManager(
        clock = { wallNow },
        monotonicClock = { elapsedNow },
    )

    @Test
    fun pairingCodeIsSingleUseAndApprovedSessionDeliveryCanBeRetried() {
        val code = manager.createPairingCode()
        assertTrue(code.digits.matches(Regex("[0-9]{8}")))
        assertEquals(wallNow + ViewerPairingManager.PAIRING_TTL_MS, code.expiresAtUtc)

        val submission = manager.submitPairingCode(code.digits, "192.168.1.8", "  Living room\nviewer ")
        assertTrue(submission is PairingSubmission.Waiting)
        val requestId = (submission as PairingSubmission.Waiting).requestId
        assertTrue(requestId.length >= 40)
        assertEquals(PairingPoll.Waiting, manager.poll(requestId))
        assertEquals("Living roomviewer", manager.pendingRequests().single().viewerName)
        assertEquals(PairingSubmission.ExpiredCode, manager.submitPairingCode(code.digits, "192.168.1.8", "Second"))

        assertEquals(PairingApproval.APPROVED, manager.approve(requestId))
        val firstPoll = manager.poll(requestId)
        assertTrue(firstPoll is PairingPoll.Approved)
        val sessionToken = (firstPoll as PairingPoll.Approved).sessionToken
        assertTrue(sessionToken.length >= 40)
        // Simulate the first HTTP response being lost; the browser can poll again before expiry.
        assertEquals(firstPoll, manager.poll(requestId))

        val identity = manager.authenticate(sessionToken)
        assertNotNull(identity)
        assertEquals("Living roomviewer", identity?.viewerName)
        assertEquals(PairingPoll.Completed, manager.poll(requestId))
        assertTrue(manager.logout(sessionToken))
        assertNull(manager.authenticate(sessionToken))
        assertFalse(manager.logout(sessionToken))
    }

    @Test
    fun failedCodesAreRateLimitedPerAddressForFiveMinutes() {
        val code = manager.createPairingCode()
        repeat(4) {
            assertEquals(
                PairingSubmission.InvalidCode,
                manager.submitPairingCode("99999999", "192.168.1.10", "Viewer"),
            )
        }
        assertEquals(
            PairingSubmission.RateLimited,
            manager.submitPairingCode("99999999", "192.168.1.10", "Viewer"),
        )
        val allowedFromSecondAddress = manager.submitPairingCode(code.digits, "192.168.1.11", "Other")
        assertTrue(allowedFromSecondAddress is PairingSubmission.Waiting)
        assertEquals(
            (allowedFromSecondAddress as PairingSubmission.Waiting).requestId,
            manager.pendingRequests().single().requestId,
        )

        elapsedNow += ViewerPairingManager.FAILURE_WINDOW_MS
        manager.createPairingCode()
        assertEquals(
            PairingSubmission.InvalidCode,
            manager.submitPairingCode("11111111", "192.168.1.10", "Viewer"),
        )
    }

    @Test
    fun expiredUndeliveredApprovalDoesNotConsumeAViewerSlot() {
        val code = manager.createPairingCode()
        val requestId = (manager.submitPairingCode(code.digits, "192.168.1.12", "Viewer") as PairingSubmission.Waiting).requestId
        assertEquals(PairingApproval.APPROVED, manager.approve(requestId))
        assertEquals(1, manager.activeViewerCount())

        elapsedNow += ViewerPairingManager.PAIRING_TTL_MS
        assertEquals(PairingPoll.Expired, manager.poll(requestId))
        assertEquals(PairingApproval.EXPIRED, manager.approve(requestId))
        assertEquals(0, manager.activeViewerCount())
    }

    @Test
    fun approvalNearOriginalDeadlineGetsAFullDeliveryWindow() {
        val code = manager.createPairingCode()
        val requestId = (manager.submitPairingCode(code.digits, "192.168.1.16", "Viewer") as PairingSubmission.Waiting).requestId
        elapsedNow += ViewerPairingManager.PAIRING_TTL_MS - 1

        assertEquals(PairingApproval.APPROVED, manager.approve(requestId))
        elapsedNow += ViewerPairingManager.PAIRING_TTL_MS - 1
        val delivered = manager.poll(requestId)
        assertTrue(delivered is PairingPoll.Approved)
        val token = (delivered as PairingPoll.Approved).sessionToken
        assertNotNull(manager.authenticate(token))
    }

    @Test
    fun resetRevokesIssuedSessionsAndInvalidatesCurrentCode() {
        val code = manager.createPairingCode()
        val token = approveViewer("192.168.1.17")
        assertNotNull(manager.authenticate(token))

        manager.reset()

        assertEquals(0, manager.activeViewerCount())
        assertNull(manager.authenticate(token))
        assertEquals(
            PairingSubmission.ExpiredCode,
            manager.submitPairingCode(code.digits, "192.168.1.17", "Viewer"),
        )
    }

    @Test
    fun sessionsExpireByIdleAndAbsoluteMonotonicDeadlines() {
        val idleToken = approveViewer("192.168.1.13")
        assertNotNull(manager.authenticate(idleToken)) // Completes token delivery.
        elapsedNow += ViewerPairingManager.IDLE_SESSION_TTL_MS
        assertNull(manager.authenticate(idleToken))
        assertEquals(0, manager.activeViewerCount())

        val absoluteToken = approveViewer("192.168.1.14")
        assertNotNull(manager.authenticate(absoluteToken))
        repeat(13) {
            wallNow -= 12 * 60 * 60 * 1000L // Device wall clock rolls backward.
            elapsedNow += 12 * 60 * 60 * 1000L
            assertNotNull(manager.authenticate(absoluteToken))
        }
        wallNow -= 12 * 60 * 60 * 1000L
        elapsedNow += 12 * 60 * 60 * 1000L
        assertNull(manager.authenticate(absoluteToken))
    }

    @Test
    fun wallClockRollbackDoesNotExtendPairingCodeExpiry() {
        val code = manager.createPairingCode()
        wallNow -= 30 * 24 * 60 * 60 * 1000L
        elapsedNow += ViewerPairingManager.PAIRING_TTL_MS
        assertEquals(
            PairingSubmission.ExpiredCode,
            manager.submitPairingCode(code.digits, "192.168.1.15", "Viewer"),
        )
    }

    @Test
    fun limitsConfiguredCapacityAndSupportsLocalRevocation() {
        val limitedManager = ViewerPairingManager(
            clock = { wallNow },
            monotonicClock = { elapsedNow },
            maxViewers = 3,
        )
        val sessions = mutableListOf<Pair<String, String>>()
        repeat(3) { index ->
            val code = limitedManager.createPairingCode()
            val requestId = (limitedManager.submitPairingCode(code.digits, "192.168.1.${20 + index}", "Viewer") as PairingSubmission.Waiting).requestId
            assertEquals(PairingApproval.APPROVED, limitedManager.approve(requestId))
            val token = (limitedManager.poll(requestId) as PairingPoll.Approved).sessionToken
            sessions += token to requireNotNull(limitedManager.authenticate(token)).id
        }
        assertEquals(3, limitedManager.activeViewerCount())

        val fourthCode = limitedManager.createPairingCode()
        assertEquals(
            PairingSubmission.CapacityReached,
            limitedManager.submitPairingCode(fourthCode.digits, "192.168.1.30", "Fourth"),
        )
        assertTrue(limitedManager.revokeSession(sessions.first().second))
        assertNull(limitedManager.authenticate(sessions.first().first))
        assertTrue(limitedManager.submitPairingCode(fourthCode.digits, "192.168.1.30", "Fourth") is PairingSubmission.Waiting)
    }

    @Test
    fun defaultMaxViewersAllowsMultipleConcurrentSessions() {
        repeat(ViewerPairingManager.MAX_VIEWERS) { index ->
            val token = approveViewer("192.168.1.${20 + index}")
            assertNotNull(manager.authenticate(token))
        }
        assertEquals(ViewerPairingManager.MAX_VIEWERS, manager.activeViewerCount())
        val extraCode = manager.createPairingCode()
        assertEquals(
            PairingSubmission.CapacityReached,
            manager.submitPairingCode(extraCode.digits, "192.168.1.99", "Extra"),
        )
    }

    private fun approveViewer(address: String): String {
        val code = manager.createPairingCode()
        val requestId = (manager.submitPairingCode(code.digits, address, "Viewer") as PairingSubmission.Waiting).requestId
        assertEquals(PairingApproval.APPROVED, manager.approve(requestId))
        return (manager.poll(requestId) as PairingPoll.Approved).sessionToken
    }
}
