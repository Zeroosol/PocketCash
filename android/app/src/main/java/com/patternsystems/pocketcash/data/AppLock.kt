package com.patternsystems.pocketcash.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.patternsystems.pocketcash.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App lock (Pro, docs/spec.md section 12): BiometricPrompt with biometrics or the phone's screen lock. The app locks
 * every time it goes to the background and shows the lock screen until the person unlocks. Widgets, the tile and
 * notifications never go through here, so they keep working while the app is locked.
 *
 * The state lives for the whole process, so turning the phone to dark mode or changing the language (which recreates
 * the activity) doesn't unlock it; a new process starts locked.
 */
object AppLock {
    /** How the phone can unlock PocketCash, which also names the switch in Settings. */
    enum class Method { FINGERPRINT, FACE, PASSCODE, NONE }

    private const val ALLOWED = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    /** Coming back from the file picker or the share sheet within this long doesn't ask again. */
    private const val PICKER_GRACE_MS = 3 * 60 * 1000L

    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var started = false
    private var autoPrompt = false
    private var authenticating = false
    private var expectingReturn = false
    private var leftAt = 0L

    /** Once per process: start locked when the lock is on. */
    fun start(lockOn: Boolean) {
        if (started) return
        started = true
        if (lockOn) lock()
    }

    private fun lock() {
        _locked.value = true
        autoPrompt = true
    }

    /**
     * The app is about to open the system's file picker, share sheet or settings on purpose. Coming back from it soon
     * doesn't lock again.
     */
    fun expectReturn() {
        expectingReturn = true
    }

    fun onStop(activity: FragmentActivity, lockOn: Boolean) {
        if (activity.isChangingConfigurations || !lockOn || authenticating) return
        if (expectingReturn) {
            leftAt = SystemClock.elapsedRealtime()
            return
        }
        lock()
    }

    fun onStart(lockOn: Boolean) {
        if (!lockOn) {
            _locked.value = false
        } else if (expectingReturn && leftAt > 0 && SystemClock.elapsedRealtime() - leftAt > PICKER_GRACE_MS) {
            lock()
        }
        leftAt = 0
    }

    /** Called from onResume: asks right away once per lock, never in a loop after a cancel. */
    fun onResume(activity: FragmentActivity, lockOn: Boolean) {
        expectingReturn = false
        if (lockOn && _locked.value && autoPrompt && !authenticating) unlock(activity)
    }

    /** Turning the lock off (in Settings, or Pro lost) opens the app. */
    fun disabled() {
        _locked.value = false
        autoPrompt = false
    }

    /** The lock screen's Unlock button, and the automatic prompt. */
    fun unlock(activity: FragmentActivity) {
        autoPrompt = false
        authenticate(activity, activity.getString(R.string.lock_prompt_title)) { ok, cancelledByBackground ->
            if (ok) _locked.value = false
            // The prompt closed because the app went to the background: ask again when it's back.
            if (cancelledByBackground) autoPrompt = true
        }
    }

    /** Turning the lock on needs one successful unlock first. */
    fun confirmEnable(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        authenticate(activity, activity.getString(R.string.lock_enable_prompt_title)) { ok, _ -> onResult(ok) }
    }

    private fun authenticate(activity: FragmentActivity, title: String, onResult: (ok: Boolean, cancelledByBackground: Boolean) -> Unit) {
        if (authenticating) return
        authenticating = true
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    onResult(true, false)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticating = false
                    val background = errorCode == BiometricPrompt.ERROR_CANCELED &&
                        !activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    onResult(false, background)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder().setTitle(title)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.setAllowedAuthenticators(ALLOWED)
        } else {
            // Android 8-10: the same "biometrics, else PIN/pattern/password" through the older switch.
            @Suppress("DEPRECATION")
            info.setDeviceCredentialAllowed(true)
        }
        try {
            prompt.authenticate(info.build())
        } catch (e: IllegalArgumentException) {
            authenticating = false
            onResult(false, false)
        }
    }

    /** What the phone offers. NONE: no screen lock set up, so the switch stays off and says so. */
    fun method(context: Context): Method {
        val manager = BiometricManager.from(context)
        if (manager.canAuthenticate(ALLOWED) != BiometricManager.BIOMETRIC_SUCCESS) return Method.NONE
        if (manager.canAuthenticate(BIOMETRIC_WEAK) != BiometricManager.BIOMETRIC_SUCCESS) return Method.PASSCODE
        val pm = context.packageManager
        return when {
            pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) -> Method.FINGERPRINT
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm.hasSystemFeature(PackageManager.FEATURE_FACE) -> Method.FACE
            else -> Method.FINGERPRINT
        }
    }
}
