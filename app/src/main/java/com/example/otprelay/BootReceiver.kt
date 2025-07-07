package com.example.otprelay
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    private val TAG = Constants.LOG_TAG

    override fun onReceive(context: Context, intent: Intent) {
        // Check if the received action is one of the boot completion actions
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_REBOOT || // Added REBOOT action for completeness
            intent.action == "android.intent.action.QUICKBOOT_POWERON" || // For some devices
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") { // For HTC devices

            Log.d(TAG, "BootReceiver: Device boot detected. Action: ${intent.action}")

            // Check if auto-forwarding is enabled in preferences before starting the service
            val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val isAutoForwardEnabled = prefs.getBoolean(Constants.KEY_AUTO_FORWARD_ENABLED, false)

            if (isAutoForwardEnabled) {
                Log.d(TAG, "BootReceiver: Auto-forwarding is enabled. Starting OTPService.")
                val serviceIntent = Intent(context, OTPService::class.java)
                // Start the service as a foreground service to ensure it keeps running
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } else {
                Log.d(TAG, "BootReceiver: Auto-forwarding is disabled. Not starting OTPService.")
            }
        }
    }
}
