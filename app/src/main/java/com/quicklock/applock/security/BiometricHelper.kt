package com.quicklock.applock.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Thin wrapper over AndroidX BiometricPrompt (system UI; Samsung fingerprint is Class 3 / STRONG). */
object BiometricHelper {

    fun biometricAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    /**
     * @param onEnd called when the prompt ends without success (cancelled, negative button, error).
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String?,
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
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setNegativeButtonText(negativeText)
            .setConfirmationRequired(false)
        subtitle?.let { builder.setSubtitle(it) }
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
            .authenticate(builder.build())
    }
}
