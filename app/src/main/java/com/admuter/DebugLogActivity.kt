package com.admuter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.admuter.databinding.ActivityDebugLogBinding

/**
 * Standalone debug screen for AdMuter.
 *
 * Shows the in-memory [DebugEventLog] event buffer in a scrollable
 * monospace viewer, with actions to copy/clear the log and a
 * "Simulate Ad Detection" button for diagnostic testing.
 *
 * The "Simulate Ad Detection" action was moved here from [MainActivity]
 * so the main dashboard stays minimal; it broadcasts
 * [SpotifyReceiver.ACTION_AD_DETECTED] to [MuterService] exactly like the
 * old main-screen test button did.
 */
class DebugLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDebugLogBinding
    private lateinit var audioManager: AudioManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityDebugLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        binding.logText.movementMethod = ScrollingMovementMethod()

        binding.btnClose.setOnClickListener { finish() }
        binding.btnSimulateAd.setOnClickListener { simulateAdDetection() }
        binding.btnCopyLog.setOnClickListener { copyLog() }
        binding.btnClearLog.setOnClickListener {
            DebugEventLog.clear()
            refreshLog()
            Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-read the log buffer every time the screen is (re)shown.
        refreshLog()
    }

    // ---------------------------------------------------------------
    //  Log actions
    // ---------------------------------------------------------------

    private fun refreshLog() {
        binding.logText.text = DebugEventLog.getText().ifEmpty { "(No events captured yet)" }
    }

    private fun copyLog() {
        val rawLog = DebugEventLog.getText()
        if (rawLog.isBlank()) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AdMuter Debug Log", rawLog))
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------
    //  Simulate Ad Detection
    // ---------------------------------------------------------------

    /**
     * Sends an AD_DETECTED broadcast directly to [MuterService] so the
     * mute/restore pipeline can be verified end-to-end without waiting
     * for a real Spotify ad. Mirrors the logic formerly housed in
     * [MainActivity] (kept off the main dashboard).
     */
    private fun simulateAdDetection() {
        if (!MuterService.isRunning(this)) {
            Toast.makeText(
                this,
                "Please start the service first by flipping the switch",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        // Send an AD_DETECTED broadcast to MuterService directly
        val testIntent = Intent().apply {
            `package` = packageName
            action = SpotifyReceiver.ACTION_AD_DETECTED
        }
        sendBroadcast(testIntent)

        Toast.makeText(
            this,
            "Test ad detection sent. Volume should go to 0.\nToggle the switch to restore volume.",
            Toast.LENGTH_LONG
        ).show()

        Log.d("AdMuter", "Test ad detection sent — current volume was $currentVolume")

        // The broadcast is delivered asynchronously; refresh shortly after
        // so any events logged by the receiver show up in the viewer.
        binding.root.postDelayed({ refreshLog() }, 600L)
    }
}
