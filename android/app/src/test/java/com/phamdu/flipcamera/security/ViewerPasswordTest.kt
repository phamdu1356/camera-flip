package com.phamdu.flipcamera.security

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ViewerPasswordTest {
    private class Store : ViewerPasswordStore {
        var value: ViewerPassword? = null
        var failWrite = false
        override fun read() = value
        override fun write(password: ViewerPassword?) {
            check(!failWrite)
            value = password
        }
    }

    @Test fun saltedVerifierSupportsExactUnicodePassword() {
        val value = " Mật khẩu thử nghiệm 42 "
        val first = ViewerPassword.create(value)
        val second = ViewerPassword.create(value)
        assertFalse(first.salt.contentEquals(second.salt))
        assertFalse(first.digest.contentEquals(second.digest))
        assertTrue(first.matches(value))
        assertFalse(first.matches(value.trim()))
        assertFalse(first.matches("incorrect password"))
        assertNull(ViewerPassword.restore(ByteArray(1), first.digest))
    }

    @Test fun passwordLoginDoesNotNeedCodeOrLocalApprovalAndLoadsPersistedVerifier() {
        val store = Store()
        ViewerPairingManager(passwordStore = store).setPassword(ViewerPassword.create("viewer test password"))
        val restarted = ViewerPairingManager(passwordStore = store)
        assertTrue(restarted.hasPassword())
        val approved = restarted.loginPassword("viewer test password", "Browser") as PasswordLogin.Approved
        assertNotNull(restarted.authenticate(approved.sessionToken))
        assertTrue(restarted.pendingRequests().isEmpty())
        assertTrue(restarted.logout(approved.sessionToken))
        assertNull(restarted.authenticate(approved.sessionToken))
    }

    @Test fun fiveFailuresLockAllClientsUntilMonotonicWindowExpires() {
        var elapsed = 1_000L
        val manager = ViewerPairingManager(monotonicClock = { elapsed }, passwordStore = Store())
        manager.setPassword(ViewerPassword.create("viewer test password"))
        repeat(4) { assertEquals(PasswordLogin.InvalidPassword, manager.loginPassword("wrong password", "Browser")) }
        assertEquals(PasswordLogin.RateLimited, manager.loginPassword("wrong password", "Browser"))
        assertEquals(PasswordLogin.RateLimited, manager.loginPassword("viewer test password", "Other browser"))
        elapsed += ViewerPairingManager.FAILURE_WINDOW_MS + 1
        assertTrue(manager.loginPassword("viewer test password", "Browser") is PasswordLogin.Approved)
    }

    @Test fun changingPasswordRevokesSessionsAndPendingPairing() {
        val manager = ViewerPairingManager(passwordStore = Store())
        manager.setPassword(ViewerPassword.create("first test password"))
        val first = manager.loginPassword("first test password", "Browser") as PasswordLogin.Approved
        val code = manager.createPairingCode()
        val pending = manager.submitPairingCode(code.digits, "local", "Pending browser") as PairingSubmission.Waiting
        manager.setPassword(ViewerPassword.create("second test password"))
        assertNull(manager.authenticate(first.sessionToken))
        assertEquals(PairingPoll.Unknown, manager.poll(pending.requestId))
        assertEquals(PasswordLogin.InvalidPassword, manager.loginPassword("first test password", "Browser"))
        assertTrue(manager.loginPassword("second test password", "Browser") is PasswordLogin.Approved)
    }

    @Test fun resettingViewerKeepsPasswordButRequiresFreshSession() {
        val manager = ViewerPairingManager(passwordStore = Store())
        manager.setPassword(ViewerPassword.create("viewer test password"))
        val first = manager.loginPassword("viewer test password", "Browser") as PasswordLogin.Approved
        manager.reset()
        assertTrue(manager.hasPassword())
        assertNull(manager.authenticate(first.sessionToken))
        assertTrue(manager.loginPassword("viewer test password", "Browser") is PasswordLogin.Approved)
    }

    @Test fun passwordSessionsRespectViewerCapacityAndRemovingPasswordRevokesThem() {
        val manager = ViewerPairingManager(maxViewers = 1, passwordStore = Store())
        assertEquals(PasswordLogin.NotConfigured, manager.loginPassword("viewer test password", "Browser"))
        manager.setPassword(ViewerPassword.create("viewer test password"))
        val first = manager.loginPassword("viewer test password", "Browser") as PasswordLogin.Approved
        assertEquals(PasswordLogin.CapacityReached, manager.loginPassword("viewer test password", "Other browser"))
        manager.setPassword(null)
        assertFalse(manager.hasPassword())
        assertNull(manager.authenticate(first.sessionToken))
        assertEquals(PasswordLogin.NotConfigured, manager.loginPassword("viewer test password", "Browser"))
    }

    @Test fun failedPersistencePreservesCurrentPasswordAndSessions() {
        val store = Store()
        val manager = ViewerPairingManager(passwordStore = store)
        manager.setPassword(ViewerPassword.create("first test password"))
        val first = manager.loginPassword("first test password", "Browser") as PasswordLogin.Approved
        store.failWrite = true
        runCatching { manager.setPassword(ViewerPassword.create("second test password")) }
            .onSuccess { fail("Store failure must be surfaced") }
        assertNotNull(manager.authenticate(first.sessionToken))
        assertTrue(store.value!!.matches("first test password"))
    }

    @Test fun resetDuringPasswordDerivationDoesNotBlockSessionsOrIssueStaleToken() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val manager = ViewerPairingManager(passwordStore = Store(), passwordVerifier = { _, _ ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        })
        manager.setPassword(ViewerPassword.create("viewer test password"))
        try {
            val result = executor.submit<PasswordLogin> { manager.loginPassword("viewer test password", "Browser") }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            assertEquals(PasswordLogin.Busy, manager.loginPassword("viewer test password", "Other browser"))
            executor.submit { manager.reset() }.get(1, TimeUnit.SECONDS)
            assertTrue(manager.hasPassword())
            release.countDown()
            assertEquals(PasswordLogin.InvalidPassword, result.get(2, TimeUnit.SECONDS))
            assertEquals(0, manager.activeViewerCount())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun invalidPasswordLengthsNeverCreateOrMatchVerifier() {
        for (length in listOf(0, 7, 129)) {
            assertThrows(IllegalArgumentException::class.java) { ViewerPassword.create("x".repeat(length)) }
        }
        val verifier = ViewerPassword.create("viewer test password")
        assertFalse(verifier.matches("short"))
        assertFalse(verifier.matches("x".repeat(129)))
        val salt = verifier.salt.copyOf()
        val digest = verifier.digest.copyOf()
        val restored = ViewerPassword.restore(salt, digest)!!
        salt.fill(0)
        digest.fill(0)
        assertTrue(restored.matches("viewer test password"))
    }

    @Test fun verifierFailureReleasesBusyPermitForRetry() {
        var fail = true
        val manager = ViewerPairingManager(passwordStore = Store(), passwordVerifier = { _, _ ->
            check(!fail)
            true
        })
        manager.setPassword(ViewerPassword.create("viewer test password"))
        assertThrows(IllegalStateException::class.java) { manager.loginPassword("viewer test password", "Browser") }
        fail = false
        assertTrue(manager.loginPassword("viewer test password", "Browser") is PasswordLogin.Approved)
    }

    @Test fun removalDuringDerivationPersistsRemovalAndPreventsStaleLogin() {
        val store = Store()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val manager = ViewerPairingManager(passwordStore = store, passwordVerifier = { _, _ ->
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        })
        manager.setPassword(ViewerPassword.create("viewer test password"))
        try {
            val result = executor.submit<PasswordLogin> { manager.loginPassword("viewer test password", "Browser") }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            manager.setPassword(null)
            release.countDown()
            assertEquals(PasswordLogin.InvalidPassword, result.get(2, TimeUnit.SECONDS))
            assertNull(store.value)
            assertFalse(ViewerPairingManager(passwordStore = store).hasPassword())
            assertEquals(0, manager.activeViewerCount())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
