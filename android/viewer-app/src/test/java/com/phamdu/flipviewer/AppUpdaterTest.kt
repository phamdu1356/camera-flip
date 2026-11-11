package com.phamdu.flipviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    private val sha = "a".repeat(64)
    private val remote = RemoteVersion(5, "1.0.5", sha, 100)

    @Test fun newerVersionCodeIsUpdate() = assertTrue(isUpdateAvailable(4, sha, remote))

    @Test fun olderRemoteIsNeverInstalled() = assertFalse(isUpdateAvailable(6, "b".repeat(64), remote))

    @Test fun sameVersionDifferentBytesIsUpdate() = assertTrue(isUpdateAvailable(5, "b".repeat(64), remote))

    @Test fun sameVersionSameBytesIsNotUpdate() = assertFalse(isUpdateAvailable(5, sha.uppercase(), remote))

    @Test fun sha256OfKnownBytes() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".byteInputStream()),
        )
    }
}
