package com.phamdu.flipcamera.security

import android.content.Context
import java.util.Base64

internal class PreferencesViewerPasswordStore(context: Context) : ViewerPasswordStore {
    private val preferences = context.getSharedPreferences("viewer-password", Context.MODE_PRIVATE)

    override fun read(): ViewerPassword? = runCatching {
        val parts = preferences.getString("verifier", null)?.split(':') ?: return null
        if (parts.size != 4 || parts[0] != "v1" || parts[1] != ViewerPassword.ITERATIONS.toString()) return null
        val decoder = Base64.getDecoder()
        ViewerPassword.restore(decoder.decode(parts[2]), decoder.decode(parts[3]))
    }.getOrNull()

    override fun write(password: ViewerPassword?) {
        val encoder = Base64.getEncoder()
        val value = password?.let {
            "v1:${ViewerPassword.ITERATIONS}:${encoder.encodeToString(it.salt)}:${encoder.encodeToString(it.digest)}"
        }
        check(preferences.edit().putString("verifier", value).commit()) { "Không lưu được mật khẩu xem." }
    }
}
