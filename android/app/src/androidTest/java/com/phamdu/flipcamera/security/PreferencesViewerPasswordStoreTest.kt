package com.phamdu.flipcamera.security

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Emulator-only; every preferences filename is redirected to a QA namespace. */
@RunWith(AndroidJUnit4::class)
class PreferencesViewerPasswordStoreTest {
    private val application = ApplicationProvider.getApplicationContext<Context>()
    private val arguments get() = InstrumentationRegistry.getArguments()

    private class Sandbox(context: Context, private val prefix: String) : ContextWrapper(context) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("qa-password-$prefix-$name", mode)
    }

    @Before fun requireExplicitEmulatorTarget() {
        assumeTrue("Never run this test on a personal phone", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeTrue("Explicit QA sandbox opt-in required", arguments.getString("passwordSandbox") == "true")
    }

    @Test fun verifierReloadsAndRemovalRevokesSessions() {
        val context = Sandbox(application, UUID.randomUUID().toString())
        val preferences = context.getSharedPreferences("viewer-password", Context.MODE_PRIVATE)
        try {
            val store = PreferencesViewerPasswordStore(context)
            store.write(ViewerPassword.create(FIXTURE))
            val loaded = PreferencesViewerPasswordStore(context).read()!!
            assertTrue(loaded.matches(FIXTURE))
            val persisted = preferences.getString("verifier", null)!!
            assertFalse(persisted.contains(FIXTURE))
            assertTrue(persisted.startsWith("v1:600000:"))
            val manager = ViewerPairingManager(passwordStore = PreferencesViewerPasswordStore(context))
            val login = manager.loginPassword(FIXTURE, "Sandbox") as PasswordLogin.Approved
            manager.setPassword(null)
            assertNull(manager.authenticate(login.sessionToken))
            assertNull(PreferencesViewerPasswordStore(context).read())
            assertFalse(preferences.contains("verifier"))
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    @Test fun malformedAndUnsupportedRecordsFailClosed() {
        val context = Sandbox(application, UUID.randomUUID().toString())
        val preferences = context.getSharedPreferences("viewer-password", Context.MODE_PRIVATE)
        try {
            for (record in listOf("", "v2:600000:AA==:AA==", "v1:1:AA==:AA==", "v1:600000:!!!:!!!", "v1:600000:AA==:AA==")) {
                assertTrue(preferences.edit().putString("verifier", record).commit())
                assertNull(PreferencesViewerPasswordStore(context).read())
            }
            assertTrue(preferences.edit().putInt("verifier", 42).commit())
            assertNull(PreferencesViewerPasswordStore(context).read())
        } finally {
            assertTrue(preferences.edit().clear().commit())
        }
    }

    /** Run twice in separate instrumentation invocations: phase=seed then phase=verify. */
    @Test fun verifierSurvivesSeparateInstrumentationProcess() {
        val phase = arguments.getString("passwordPersistencePhase")
        assumeTrue("Two explicit process phases required", phase in setOf("seed", "verify"))
        val context = Sandbox(application, "cross-process-v1")
        val preferences = context.getSharedPreferences("viewer-password", Context.MODE_PRIVATE)
        if (phase == "seed") {
            assertTrue(preferences.edit().clear().commit())
            PreferencesViewerPasswordStore(context).write(ViewerPassword.create(FIXTURE))
            assertTrue(preferences.edit().putInt("seedPid", Process.myPid()).commit())
        } else {
            try {
                assertTrue("Seed phase must have run", preferences.contains("seedPid"))
                assertNotEquals("Must use a different instrumentation process", preferences.getInt("seedPid", -1), Process.myPid())
                assertTrue(PreferencesViewerPasswordStore(context).read()!!.matches(FIXTURE))
            } finally {
                assertTrue(preferences.edit().clear().commit())
            }
        }
    }

    companion object {
        private const val FIXTURE = "Public sandbox verifier 42!"
    }
}
