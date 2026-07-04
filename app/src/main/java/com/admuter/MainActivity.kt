package com.admuter

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.admuter.databinding.ActivityMainBinding

/**
 * Single-activity UI for AdMuter.
 *
 * Provides a toggle switch to start/stop [MuterService].
 * Handles notification permission on Android 13+.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
            binding.switchEnable.isChecked = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViews()
    }

    override fun onResume() {
        super.onResume()
        // Sync toggle state with service running state
        val isRunning = isServiceRunning()
        binding.switchEnable.isChecked = isRunning
        updateUiForState(isRunning)
    }

    // ---- UI Setup ----

    private fun setupViews() {
        binding.switchEnable.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestNotificationPermissionAndStart()
            } else {
                stopMuterService()
            }
        }
    }

    private fun updateUiForState(isRunning: Boolean) {
        if (isRunning) {
            binding.switchEnable.text = "Disable Ad Muting"
            binding.statusText.text = "Service is running"
            binding.statusIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        } else {
            binding.switchEnable.text = "Enable Ad Muting"
            binding.statusText.text = "Service is stopped"
            binding.statusIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_on)
        }
    }

    // ---- Permission Handling ----

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

    // ---- Service Control ----

    private fun startMuterService() {
        val intent = Intent(this, MuterService::class.java).apply {
            action = MuterService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.switchEnable.isChecked = true
        updateUiForState(true)
        Toast.makeText(this, R.string.service_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopMuterService() {
        val intent = Intent(this, MuterService::class.java).apply {
            action = MuterService.ACTION_STOP
        }
        startService(intent)
        updateUiForState(false)
        Toast.makeText(this, R.string.service_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun isServiceRunning(): Boolean {
        return MuterService.isRunning(this)
    }
}
