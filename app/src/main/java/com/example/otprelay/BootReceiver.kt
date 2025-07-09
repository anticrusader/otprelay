package com.example.otprelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    private val TAG = Constants.LOG_TAG

    override fun onReceive(context: Context, intent: Intent) {
        // Use goAsync() for BroadcastReceivers that start services to ensure it completes before the system kills it.
        val pendingResult = goAsync()
        Log.d(TAG, "BootReceiver: onReceive called for action: ${intent.action}")

        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) { // ACTION_LOCKED_BOOT_COMPLETED for Direct Boot Aware
            Log.d(TAG, "BootReceiver: Boot completed (or locked boot completed) detected. Checking service status.")

            val sharedPrefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val isForwardingEnabled = sharedPrefs.getBoolean(Constants.KEY_IS_FORWARDING_ENABLED, false)

            if (isForwardingEnabled) {
                Log.d(TAG, "BootReceiver: OTP forwarding is enabled. Attempting to start OTPService.")
                val serviceIntent = Intent(context, OTPService::class.java)
                try {
                    // Always use startForegroundService for Android O+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ContextCompat.startForegroundService(context, serviceIntent)
                    } else {
                        // For older versions, direct startService is fine.
                        context.startService(serviceIntent)
                    }
                    Log.d(TAG, "BootReceiver: Successfully requested OTPService start.")
                } catch (e: Exception) {
                    Log.e(TAG, "BootReceiver: Failed to start OTPService on boot: ${e.message}", e)
                    // Optionally show a notification to the user about the failure
                    OTPForwarder.showNotification(
                        context,
                        "❌ Service Start Error",
                        "OTP Relay service failed to start on boot: ${e.message}",
                        Constants.SMS_DEBUG_CHANNEL_ID
                    )
                }
            } else {
                Log.d(TAG, "BootReceiver: OTP forwarding is disabled in settings. Not starting OTPService.")
            }
        } else {
            Log.d(TAG, "BootReceiver: Received unexpected action: ${intent.action}. Ignoring.")
        }

        // Must call finish() to release the BroadcastReceiver.
        pendingResult.finish()
        Log.d(TAG, "BootReceiver: onReceive finished.")
    }
}