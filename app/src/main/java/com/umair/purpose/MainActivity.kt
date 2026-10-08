package com.umair.purpose

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.umair.purpose.chat.AppVisibility
import com.umair.purpose.chat.ReplyService
import com.umair.purpose.data.repo.AppSettings
import com.umair.purpose.data.repo.SettingsRepository
import com.umair.purpose.ui.common.Meta
import com.umair.purpose.ui.common.PrimaryButton
import com.umair.purpose.ui.common.Ridgeline
import com.umair.purpose.ui.common.SidePadding
import com.umair.purpose.ui.nav.PurposeNavHost
import com.umair.purpose.ui.theme.Purpose
import com.umair.purpose.ui.theme.PurposeTheme
import com.umair.purpose.ui.theme.isNight
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** App lock state for the whole process: locked on launch and after 5 minutes in the background. */
@Singleton
class AppLock @Inject constructor() {
    val locked = MutableStateFlow(true)
    private var backgroundAt: Long? = null

    fun onBackground() {
        backgroundAt = SystemClock.elapsedRealtime()
    }

    fun onForeground() {
        // Read and cleared in one go: onStart also runs after a configuration change, and onStop deliberately
        // does not record a background time then. Leaving the timestamp in place meant every later onStart was
        // measured from the same old instant, so once the app had been away for 5 minutes it re-locked on every
        // rotation — and on anything else that restarts the activity.
        val since = backgroundAt ?: return
        backgroundAt = null
        if (SystemClock.elapsedRealtime() - since >= TIMEOUT_MS) locked.value = true
    }

    fun unlock() {
        locked.value = false
    }

    private companion object {
        const val TIMEOUT_MS = 5 * 60 * 1000L
    }
}

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var lock: AppLock
    @Inject lateinit var gate: com.umair.purpose.data.db.DatabaseGate

    private lateinit var settings: StateFlow<AppSettings?>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Night is the default; the real theme follows as soon as settings load.
        applySystemBars(night = true)
        // Nothing reads the database until it has opened (and migrated) safely (UPDATE-15).
        lifecycleScope.launch { gate.open() }
        settings = kotlinx.coroutines.flow.flow {
            gate.awaitReady()
            emitAll(settingsRepo.observe())
        }.stateIn(lifecycleScope, SharingStarted.Eagerly, null)

        // "Hide in recent apps": no content in the recents card, no screenshots.
        lifecycleScope.launch {
            settings.collect { s ->
                if (s == null || s.hideInRecents) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }

        setContent {
            val gateState by gate.state.collectAsStateWithLifecycle()
            (gateState as? com.umair.purpose.data.db.DatabaseGate.State.Failed)?.let { failed ->
                PurposeTheme(com.umair.purpose.data.repo.ThemeChoice.NIGHT) { DatabaseProblem(failed.details) }
                return@setContent
            }
            val s by settings.collectAsStateWithLifecycle()
            val locked by lock.locked.collectAsStateWithLifecycle()
            val current = s ?: return@setContent
            val night = isNight(current.theme)
            LaunchedEffect(night) { applySystemBars(night) }
            PurposeTheme(current.theme) {
                val needsLock = current.lockEnabled && canLock()
                if (needsLock && locked) {
                    LockScreen(onUnlock = ::authenticate)
                    LaunchedEffect(Unit) { authenticate() }
                } else {
                    PurposeNavHost()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lock.onForeground()
        AppVisibility.foreground = true
        ReplyService.clearReplied(this)
    }

    override fun onStop() {
        super.onStop()
        AppVisibility.foreground = false
        if (!isChangingConfigurations) lock.onBackground()
    }

    /**
     * DESIGN.md colors: status and navigation bars show the app's own background, drawn behind them.
     * With 3-button navigation Android adds a light-grey "contrast" scrim unless told not to; that was the grey
     * bar in QA. Gesture navigation has no scrim either way.
     */
    private fun applySystemBars(night: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = if (night) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (night) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    private fun canLock(): Boolean =
        BiometricManager.from(this).canAuthenticate(BIOMETRIC_WEAK or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = lock.unlock()
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Purpose")
                .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
                .build()
        )
    }
}

/** The database couldn't be opened or updated. Nothing was deleted; the next version of the app can fix it. */
@Composable
private fun DatabaseProblem(details: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Box(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = SidePadding), verticalArrangement = Arrangement.Center) {
            Ridgeline()
            Spacer(Modifier.height(32.dp))
            androidx.compose.material3.Text(
                "Purpose couldn't open your data safely.",
                style = Purpose.type.openingLine, color = Purpose.colors.text,
            )
            Spacer(Modifier.height(12.dp))
            Meta("Nothing was deleted. Your conversations and memory are still on this phone, untouched. Don't uninstall the app: copy the details below and send them to Claude Code, and the next version will fix it.")
            Spacer(Modifier.height(24.dp))
            PrimaryButton("Copy details", { clipboard.setText(androidx.compose.ui.text.AnnotatedString(details)) })
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Purpose.colors.background)) {
        Column(Modifier.fillMaxSize().padding(horizontal = SidePadding), verticalArrangement = Arrangement.Center) {
            Ridgeline()
            Spacer(Modifier.height(32.dp))
            Meta("Purpose is locked.")
            Spacer(Modifier.height(16.dp))
            PrimaryButton("Unlock", onUnlock)
        }
    }
}
