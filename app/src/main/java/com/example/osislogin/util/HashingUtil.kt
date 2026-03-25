package com.example.osislogin.util

import android.util.Base64
import java.security.MessageDigest

class HashingUtil {
    fun hashPassword(password: String): String {
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val hashedBytes = messageDigest.digest(password.toByteArray())
        return Base64.encodeToString(hashedBytes, Base64.NO_WRAP)
    }

    fun verifyPassword(password: String, hashedPassword: String): Boolean {
        return hashPassword(password) == hashedPassword
    }
}
