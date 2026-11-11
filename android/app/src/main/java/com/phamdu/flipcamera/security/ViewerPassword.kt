package com.phamdu.flipcamera.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Only a salted verifier is persisted. Password derivation runs off the UI thread. */
internal class ViewerPassword private constructor(val salt: ByteArray, val digest: ByteArray) {
    fun matches(password: String): Boolean {
        if (password.length !in MIN_LENGTH..MAX_LENGTH) return false
        return MessageDigest.isEqual(digest, derive(password, salt))
    }

    companion object {
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 128
        const val ITERATIONS = 600_000

        fun create(password: String, random: SecureRandom = SecureRandom()): ViewerPassword {
            require(password.length in MIN_LENGTH..MAX_LENGTH)
            val salt = ByteArray(16).also(random::nextBytes)
            return ViewerPassword(salt, derive(password, salt))
        }

        fun restore(salt: ByteArray, digest: ByteArray): ViewerPassword? =
            if (salt.size == 16 && digest.size == 32) ViewerPassword(salt.copyOf(), digest.copyOf()) else null

        private fun derive(password: String, salt: ByteArray): ByteArray {
            val characters = password.toCharArray()
            val spec = PBEKeySpec(characters, salt, ITERATIONS, 256)
            characters.fill('\u0000')
            return try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            } finally {
                spec.clearPassword()
            }
        }
    }
}

internal interface ViewerPasswordStore {
    fun read(): ViewerPassword?
    fun write(password: ViewerPassword?)
}

internal sealed interface PasswordLogin {
    data class Approved(val sessionToken: String) : PasswordLogin
    data object InvalidPassword : PasswordLogin
    data object NotConfigured : PasswordLogin
    data object Busy : PasswordLogin
    data object RateLimited : PasswordLogin
    data object CapacityReached : PasswordLogin
}
