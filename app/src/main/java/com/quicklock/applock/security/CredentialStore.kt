package com.quicklock.applock.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.ProviderException
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

enum class CredentialType { PIN, PATTERN }

sealed interface VerifyResult {
    data object Success : VerifyResult
    data class Wrong(val attemptsLeftBeforeLockout: Int) : VerifyResult
    data class LockedOut(val remainingMs: Long) : VerifyResult
}

/**
 * Stores the PIN / pattern as a verifier, never as plain text:
 *
 *   verifier = HMAC-SHA256( keystoreKey, PBKDF2-HMAC-SHA256(secret, randomSalt) )
 *
 * The HMAC key is generated inside the Android Keystore (StrongBox / Samsung Knox Vault when the
 * device has it, TEE otherwise) and is non-exportable. So the stored verifier is useless if the
 * preferences file is copied off the device: an attacker can't brute-force a 4-digit PIN offline.
 * PBKDF2 uses a modest iteration count on purpose to keep unlocking instant; the hardware-bound key
 * is the real protection. Wrong guesses are rate-limited with escalating lockouts.
 */
class CredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("credential", Context.MODE_PRIVATE)

    @Volatile
    private var configured = prefs.contains(K_VERIFIER)

    val isSet: Boolean get() = configured

    val type: CredentialType?
        get() = prefs.getString(K_TYPE, null)?.let { runCatching { CredentialType.valueOf(it) }.getOrNull() }

    /** Stored so the PIN pad can auto-submit the moment the last digit is entered. */
    val pinLength: Int get() = prefs.getInt(K_PIN_LEN, 4)

    private val key: SecretKey by lazy { loadOrCreateKey() }

    /** Touch the Keystore key ahead of time so the first unlock doesn't pay for it. */
    fun warmUp() {
        key
    }

    fun set(type: CredentialType, secret: String) {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val verifier = derive(secret, salt)
        prefs.edit()
            .putString(K_TYPE, type.name)
            .putString(K_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(K_VERIFIER, Base64.encodeToString(verifier, Base64.NO_WRAP))
            .putInt(K_PIN_LEN, secret.length)
            .putInt(K_FAILS, 0)
            .putLong(K_LOCK_UNTIL, 0L)
            .commit()
        configured = true
    }

    fun verify(secret: String): VerifyResult {
        val remaining = lockoutRemainingMs()
        if (remaining > 0) return VerifyResult.LockedOut(remaining)

        val salt = prefs.getString(K_SALT, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val stored = prefs.getString(K_VERIFIER, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        val ok = try {
            salt != null && stored != null && MessageDigest.isEqual(derive(secret, salt), stored)
        } catch (e: GeneralSecurityException) {
            false
        }
        if (ok) {
            onExternalSuccess()
            return VerifyResult.Success
        }

        val fails = prefs.getInt(K_FAILS, 0) + 1
        val edit = prefs.edit().putInt(K_FAILS, fails)
        if (fails % MAX_ATTEMPTS == 0) {
            val steps = minOf(fails / MAX_ATTEMPTS - 1, 7)
            val delay = minOf(BASE_LOCKOUT_MS shl steps, MAX_LOCKOUT_MS)
            edit.putLong(K_LOCK_UNTIL, System.currentTimeMillis() + delay).commit()
            return VerifyResult.LockedOut(delay)
        }
        edit.commit()
        return VerifyResult.Wrong(MAX_ATTEMPTS - fails % MAX_ATTEMPTS)
    }

    /** Called after a successful biometric / device-credential unlock too. */
    fun onExternalSuccess() {
        if (prefs.getInt(K_FAILS, 0) != 0 || prefs.getLong(K_LOCK_UNTIL, 0L) != 0L) {
            prefs.edit().putInt(K_FAILS, 0).putLong(K_LOCK_UNTIL, 0L).apply()
        }
    }

    fun lockoutRemainingMs(): Long {
        val until = prefs.getLong(K_LOCK_UNTIL, 0L)
        if (until == 0L) return 0L
        // Clamped so moving the clock backwards can't create an absurdly long (or negative) wait.
        return (until - System.currentTimeMillis()).coerceIn(0L, MAX_LOCKOUT_MS)
    }

    // ---- internals ----

    private fun derive(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val stretched = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        return mac.doFinal(stretched)
    }

    private fun loadOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return try {
            generateKey(strongBox = true)
        } catch (e: ProviderException) { // includes StrongBoxUnavailableException
            generateKey(strongBox = false)
        }
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "quicklock_credential_hmac"
        const val K_TYPE = "type"
        const val K_SALT = "salt"
        const val K_VERIFIER = "verifier"
        const val K_PIN_LEN = "pin_len"
        const val K_FAILS = "fails"
        const val K_LOCK_UNTIL = "lock_until"
        const val SALT_BYTES = 16
        const val PBKDF2_ITERATIONS = 20_000
        const val MAX_ATTEMPTS = 5
        const val BASE_LOCKOUT_MS = 30_000L
        const val MAX_LOCKOUT_MS = 3_600_000L
    }
}
