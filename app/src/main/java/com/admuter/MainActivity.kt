package com.admuter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.admuter.databinding.ActivityMainMinimalBinding

/**
 * Single-activity UI for AdMuter.
 *
 * Provides a toggle switch to start/stop [MuterService].
 * Handles notification permission on Android 13+.
 * Shows setup instructions for both detection methods:
 *   1. NotificationListenerService (reliable, needs system settings)
 *   2. Spotify Broadcast (needs Device Broadcast Status in Spotify)
 * The debug screen ([DebugLogActivity]) hosts the ad-simulation test button.
 *
 * ## Key design decisions
 *  - The Activity NEVER speculatively sets the service running state.
 *    UI state strictly reflects [MuterService.isRunning] which is only
 *    updated by the service itself.
 *  - The switch control is optimistic (user sees their action immediately),
 *    but [onResume] corrects any mismatch with the actual service state.
 *  - A guard flag prevents the switch's [OnCheckedChangeListener] from
 *    re-triggering service start/stop when [updateUi] programmatically changes it.
 *  - The setup instructions section is hidden (GONE) as soon as notification
 *    access is granted, keeping the dashboard minimal.
 *
 * ## Layout
 *  Drives `activity_main_minimal.xml` ([ActivityMainMinimalBinding]). The
 *  original `activity_main.xml` is preserved untouched for safe rollback —
 *  switching back only requires re-pointing this Activity at it.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainMinimalBinding

    /**
     * Prevents recursive calls when [updateUi] programmatically changes
     * the switch position, which would otherwise fire the
     * [OnCheckedChangeListener] and re-trigger service start/stop.
     */
    private var ignoreSwitchChange = false

    /** Tracks whether the setup-instructions body is expanded or collapsed. */
    private var setupExpanded = true

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startMuterService()
        } else {
            Toast.makeText(
                this,
                "Notification permission is required for the foreground service.",
                Toast.LENGTH_LONG
            ).show()
            // User denied — revert the switch to off
            updateUi(isRunning = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
            enableEdgeToEdge()
            binding = ActivityMainMinimalBinding.inflate(layoutInflater)
            setContentView(binding.root)

            checkForCrash()
            checkSpotifyInstalled()
            updateDetectionStatus()
            updateSetupVisibility()
            setupViews()
            // Initial UI render from persisted state — does NOT rely on
            // pre-set runningState; queries the service's own in-memory flag.
            updateUi(isRunning = isServiceRunning())
        } catch (t: Throwable) {
            try {
                val prefs = getSharedPreferences("admuter_prefs", MODE_PRIVATE)
                val stackTrace = android.util.Log.getStackTraceString(t)
                val msg = t.message ?: "(no message)"
                prefs.edit()
                    .putString("last_crash", "${t.javaClass.simpleName}: $msg\n$stackTrace")
                    .putLong("last_crash_time", System.currentTimeMillis())
                    .commit()
            } catch (_: Exception) { }
            throw t
        }
    }

    override fun onResume() {
        super.onResume()
        // Always correct the UI against the actual service state on resume.
        // This handles the case where the service crashed or was killed
        // while the Activity was paused.
        val isRunning = isServiceRunning()
        updateUi(isRunning)
        checkSpotifyInstalled()
        updateDetectionStatus()
        updateSetupVisibility()
    }

    // ---------------------------------------------------------------
    //  Single source of truth for the service running state
    // ---------------------------------------------------------------

    private fun isServiceRunning(): Boolean = MuterService.isRunning(this)

    // ---------------------------------------------------------------
    //  Master UI update — call this whenever isRunning changes
    // ---------------------------------------------------------------

    private fun updateUi(isRunning: Boolean) {
        // Guard: prevent programmatic setChecked from triggering the listener
        ignoreSwitchChange = true
        binding.switchEnable.isChecked = isRunning
        ignoreSwitchChange = false

        // Switch label & status header
        if (isRunning) {
            binding.switchEnable.text = "Ad Muting Enabled"
            binding.statusText.text = "Service is Running"
            binding.statusIcon.setImageResource(R.drawable.ic_mute)
            binding.statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.spotify_green)
            )
            binding.statusIcon.alpha = 1.0f
        } else {
            binding.switchEnable.text = "Enable Ad Muting"
            binding.statusText.text = "Service Stopped"
            binding.statusIcon.setImageResource(R.drawable.ic_mute)
            binding.statusIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                android.graphics.Color.WHITE
            )
            binding.statusIcon.alpha = 0.6f
        }
    }

    // ---------------------------------------------------------------
    //  Spotify detection status
    // ---------------------------------------------------------------

    private fun checkSpotifyInstalled() {
        val isInstalled = try {
            packageManager.getPackageInfo("com.spotify.music", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        binding.spotifyStatusText.text = if (isInstalled) {
            "Spotify Installed"
        } else {
            "Spotify Not Installed — install Spotify to use this app"
        }

        binding.spotifyStatusText.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (isInstalled) R.drawable.ic_check else R.drawable.ic_close,
            0, 0, 0
        )

        binding.spotifyStatusText.setTextColor(
            if (isInstalled) getColor(R.color.spotify_green)
            else android.graphics.Color.parseColor("#FFFF4444")
        )
    }

    private fun updateDetectionStatus() {
        val nlsEnabled = isNotificationListenerEnabled()

        binding.detectionStatusText.text = if (nlsEnabled) {
            "Notification Access Granted"
        } else {
            "Notification Access Not Granted — enable via setup guide below"
        }

        binding.detectionStatusText.setCompoundDrawablesRelativeWithIntrinsicBounds(
            if (nlsEnabled) R.drawable.ic_check else R.drawable.ic_close,
            0, 0, 0
        )
        binding.detectionStatusText.setTextColor(
            if (nlsEnabled) getColor(R.color.spotify_green)
            else android.graphics.Color.parseColor("#FFFF4444")
        )
    }

    /**
     * Whether ad-detection permission is active. The setup instructions
     * teach how to enable notification access, so "permission granted"
     * here means the [SpotifyNotificationListener] is enabled in system
     * settings (this is the permission that actually powers ad detection).
     */
    private fun isNotificationPermissionGranted(): Boolean = isNotificationListenerEnabled()

    private fun isNotificationListenerEnabled(): Boolean {
        return try {
            val enabledListeners = Settings.Secure.getString(
                contentResolver,
                "enabled_notification_listeners"
            )
            enabledListeners?.contains(packageName) == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Hides the entire setup section when notification access has been
     * granted (clears visual noise), and shows it otherwise. The collapsible
     * body is only reset to its expanded state when the section transitions
     * from hidden to shown (e.g. permission newly revoked) — a manual
     * collapse by the user is otherwise preserved across onResume.
     */
    private fun updateSetupVisibility() {
        if (isNotificationPermissionGranted()) {
            binding.layoutSetupInstructions.visibility = View.GONE
        } else {
            val wasHidden = binding.layoutSetupInstructions.visibility != View.VISIBLE
            binding.layoutSetupInstructions.visibility = View.VISIBLE
            if (wasHidden) {
                setupExpanded = true
                binding.layoutSetupBody.visibility = View.VISIBLE
                binding.setupHeader.text = "Setup Instructions  ▾"
            }
        }
    }

    // ---------------------------------------------------------------
    //  Crash diagnostics
    // ---------------------------------------------------------------

    private fun checkForCrash() {
        val crashInfo = MuterService.getLastCrashInfo(this)
        if (crashInfo != null) {
            val shortMsg = crashInfo.take(500)
            binding.crashInfoText.text = "Previous crash: $shortMsg"
            binding.crashInfoText.visibility = View.VISIBLE
            MuterService.clearCrashInfo(this)
        }
    }

    // ---------------------------------------------------------------
    //  UI setup — listeners
    // ---------------------------------------------------------------

    private fun setupViews() {
        // The switch listener reacts to the user's action directly:
        // checked = ON → start service; unchecked = OFF → stop service.
        // The [ignoreSwitchChange] guard prevents re-triggering when
        // [updateUi] programmatically sets the switch position.
        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            if (!ignoreSwitchChange) {
                if (isChecked) {
                    requestNotificationPermissionAndStart()
                } else {
                    stopMuterService()
                }
            }
        }

        // Top-right log icon → dedicated Debug Logs screen
        binding.btnDebugLogs.setOnClickListener {
            startActivity(Intent(this, DebugLogActivity::class.java))
        }

        // Collapsible setup section (only interactive while visible)
        binding.setupHeader.setOnClickListener {
            setupExpanded = !setupExpanded
            binding.layoutSetupBody.visibility =
                if (setupExpanded) View.VISIBLE else View.GONE
            binding.setupHeader.text =
                if (setupExpanded) "Setup Instructions  ▾" else "Setup Instructions  ▸"
        }

        // Open Notification Access settings (Method 1)
        binding.btnNotificationSettings.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Find AdMuter in the list and enable it",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(
                    this,
                    "Notification access requires Android 5.1+",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // Open Spotify (Method 2)
        binding.btnOpenSpotify.setOnClickListener {
            try {
                val intent = packageManager.getLaunchIntentForPackage("com.spotify.music")
                if (intent != null) {
                    startActivity(intent)
                    Toast.makeText(
                        this,
                        "Go to Settings → Playback → Enable Device Broadcast Status",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val playStoreIntent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=com.spotify.music")
                    ).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(playStoreIntent)
                }
            } catch (_: Exception) {
                Toast.makeText(
                    this,
                    "Could not open Spotify. Please open it manually.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // ---------------------------------------------------------------
    //  Permission Handling
    // ---------------------------------------------------------------

    private fun requestNotificationPermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    startMuterService()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Toast.makeText(
                        this,
                        "AdMuter needs notification permission to keep the service alive in the background.",
                        Toast.LENGTH_LONG
                    ).show()
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            startMuterService()
        }
    }

    // ---------------------------------------------------------------
    //  Service Control  —  No speculative state setting
    // ---------------------------------------------------------------

    /**
     * Starts [MuterService] by firing [MuterService.ACTION_START].
     * Does NOT speculatively set [MuterService.setRunningState] — the
     * service itself updates its own running flag when it successfully
     * enters the foreground.
     *
     * The UI is updated optimistically to reflect the user's action;
     * [onResume] will correct any mismatch with the actual service state.
     */
    private fun startMuterService() {
        val intent = Intent(this, MuterService::class.java).apply {
            action = MuterService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        updateUi(isRunning = true)
        Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
    }

    /**
     * Stops [MuterService] by firing [MuterService.ACTION_STOP].
     * Does NOT speculatively set [MuterService.setRunningState] — the
     * service itself updates its own running flag in [MuterService.onDestroy].
     */
    private fun stopMuterService() {
        val intent = Intent(this, MuterService::class.java).apply {
            action = MuterService.ACTION_STOP
        }
        startService(intent)

        updateUi(isRunning = false)
        Toast.makeText(this, R.string.service_stopped, Toast.LENGTH_SHORT).show()
    }
}
