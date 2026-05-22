package com.hermesandroid.bridge.auth

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Manages the pairing code used to authenticate requests from the Hermes server.
 *
 * On first launch, generates a cryptographically random 6-character alphanumeric code.
 * The code persists across app restarts. User can regenerate from the UI.
 *
 * Security:
 *  - Uses java.security.SecureRandom (CSPRNG) instead of kotlin.random.Random
 *    (which is a non-cryptographic PRNG and can be predicted from a few outputs).
 *  - Token comparison pads both inputs to a fixed length before using
 *    MessageDigest.isEqual, ensuring constant-time behaviour even when
 *    inputs differ in length (isEqual itself short-circuits on unequal
 *    lengths). The final length check happens after the comparison completes.
 */
object PairingManager {

    private const val PREFS_NAME = "hermes_bridge_prefs"
    private const val KEY_PAIRING_CODE = "pairing_code"
    private const val CODE_LENGTH = 6

    private val secureRandom = SecureRandom()

    private var prefs: SharedPreferences? = null
    private var cachedCode: String? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Generate code on first launch
        if (getCode().isBlank()) {
            regenerateCode()
        }
    }

    fun getCode(): String {
        cachedCode?.let { return it }
        val code = prefs?.getString(KEY_PAIRING_CODE, "") ?: ""
        cachedCode = code
        return code
    }

    fun regenerateCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I to avoid confusion
        val sb = StringBuilder(CODE_LENGTH)
        repeat(CODE_LENGTH) {
            sb.append(chars[secureRandom.nextInt(chars.length)])
        }
        val code = sb.toString()
        prefs?.edit()?.putString(KEY_PAIRING_CODE, code)?.apply()
        cachedCode = code
        return code
    }

    /**
     * Validate an incoming request's Authorization header.
     * Expected format: "Bearer <code>"
     *
     * Uses fixed-length padding + MessageDigest.isEqual for constant-time
     * comparison. isEqual short-circuits on unequal lengths, so we pad both
     * arrays to the same size first, then check length after comparison.
     */
    fun validateToken(authHeader: String?): Boolean {
        if (authHeader == null) return false
        val token = authHeader.removePrefix("Bearer ").trim()
        val expected = getCode()
        if (expected.isEmpty()) return false
        val a = token.toByteArray(Charsets.UTF_8)
        val b = expected.toByteArray(Charsets.UTF_8)
        val padLen = maxOf(a.size, b.size, 256)
        return MessageDigest.isEqual(a.copyOf(padLen), b.copyOf(padLen)) && a.size == b.size
    }
}
