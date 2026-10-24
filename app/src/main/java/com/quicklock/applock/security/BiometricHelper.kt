package com.quicklock.applock.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Thin wrapper over AndroidX BiometricPrompt (system UI; Samsung fingerprint is Class 3 / STRONG). */
object BiometricHelper {

    fun biometricAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /** Phone screen lock (PIN/pattern/password). Used as the recovery path if our own secret is forgotten. */
    fun deviceCredentialAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * @param useDeviceCredential false = fingerprint (STRONG biometrics only), true = phone screen lock.
     * @param onEnd called when the prompt ends without success (cancelled, negative button, error).
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
        useDeviceCredential: Boolean,
        negativeText: String,
        onSuccess: () -> Unit,
        onEnd: () -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onEnd()
        }
        val builder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setConfirmationRequired(false)
        subtitle?.let { builder.setSubtitle(it) }
        if (useDeviceCredential) {
            builder.setAllowedAuthenticators(DEVICE_CREDENTIAL)
        } else {
            builder.setAllowedAuthenticators(BIOMETRIC_STRONG).setNegativeButtonText(negativeText)
        }
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(builder.build())
    }
}
