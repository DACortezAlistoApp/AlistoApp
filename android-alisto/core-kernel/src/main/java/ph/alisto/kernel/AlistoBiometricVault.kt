package ph.alisto.kernel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AlistoBiometricVault {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "AlistoHardwareSessionKeyV3"
    private const val PREFS_NAME = "alisto_keystore_session_vault"
    private const val PREF_ENC_UID = "enc_user_id"
    private const val PREF_ENC_IV = "enc_iv"
    private const val PREF_USER_LABEL = "cached_user_label"

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existingKey = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existingKey != null) return existingKey.secretKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun saveEncryptedSession(context: Context, portalRole: String, userId: String, userLabel: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(userId.toByteArray(Charsets.UTF_8))

            val prefs = context.getSharedPreferences("${PREFS_NAME}_$portalRole", Context.MODE_PRIVATE)
            prefs.edit()
                .putString(PREF_ENC_UID, Base64.encodeToString(encryptedBytes, Base64.NO_WRAP))
                .putString(PREF_ENC_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .putString(PREF_USER_LABEL, userLabel)
                .apply()
        } catch (_: Exception) {}
    }

    fun hasSavedSession(context: Context, portalRole: String): Boolean {
        val prefs = context.getSharedPreferences("${PREFS_NAME}_$portalRole", Context.MODE_PRIVATE)
        return prefs.contains(PREF_ENC_UID) && prefs.contains(PREF_ENC_IV)
    }

    fun getSavedUserLabel(context: Context, portalRole: String): String {
        val prefs = context.getSharedPreferences("${PREFS_NAME}_$portalRole", Context.MODE_PRIVATE)
        return prefs.getString(PREF_USER_LABEL, "Saved Session") ?: "Saved Session"
    }

    fun clearSavedSession(context: Context, portalRole: String) {
        val prefs = context.getSharedPreferences("${PREFS_NAME}_$portalRole", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    private fun decryptSavedUserId(context: Context, portalRole: String): String? {
        return try {
            val prefs = context.getSharedPreferences("${PREFS_NAME}_$portalRole", Context.MODE_PRIVATE)
            val encStr = prefs.getString(PREF_ENC_UID, null) ?: return null
            val ivStr = prefs.getString(PREF_ENC_IV, null) ?: return null

            val encBytes = Base64.decode(encStr, Base64.NO_WRAP)
            val ivBytes = Base64.decode(ivStr, Base64.NO_WRAP)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), spec)
            String(cipher.doFinal(encBytes), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun promptBiometricUnlock(
        activity: AppCompatActivity,
        portalRole: String,
        onUnlockedUserId: (String) -> Unit,
        onFallbackOrError: (String) -> Unit
    ) {
        val savedUid = decryptSavedUserId(activity, portalRole)
        if (savedUid == null) {
            onFallbackOrError("No valid hardware session found. Please log in once to bind Android Keystore.")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val biometricManager = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Device has no biometric/PIN lock configured in OS settings -> unlock keystore token directly
            onUnlockedUserId(savedUid)
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Alisto Biometric Session Unlock")
            .setSubtitle("Unlock persistent session for ${getSavedUserLabel(activity, portalRole)}")
            .setAllowedAuthenticators(authenticators)
            .build()

        val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onUnlockedUserId(savedUid)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                onFallbackOrError(errString.toString())
            }
        })

        prompt.authenticate(promptInfo)
    }
}
