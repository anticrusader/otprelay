package com.example.otprelay

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private val TAG = Constants.LOG_TAG
    private val SMS_PERMISSION_REQUEST_CODE = 100

    private lateinit var autoForwardSwitch: SwitchMaterial
    private lateinit var serviceStatusTextView: TextView
    private lateinit var lastSentOtpTextView: TextView
    private lateinit var notificationAccessButton: Button
    private lateinit var testForwardingButton: Button
    private lateinit var debugButton: Button
    private lateinit var permissionStatusTextView: TextView

    // BroadcastReceiver to get updates from OTPService
    private val otpForwardedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_OTP_FORWARDED) {
                val forwardedOtp = intent.getStringExtra(Constants.EXTRA_FORWARDED_OTP)
                if (forwardedOtp != null) {
                    updateLastSentOtpUI(forwardedOtp)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Log.d(TAG, "MainActivity: onCreate")

        // Initialize UI elements
        autoForwardSwitch = findViewById(R.id.autoForwardSwitch)
        serviceStatusTextView = findViewById(R.id.serviceStatusTextView)
        lastSentOtpTextView = findViewById(R.id.lastSentOtpTextView)
        notificationAccessButton = findViewById(R.id.notificationAccessButton)
        testForwardingButton = findViewById(R.id.testForwardingButton)
        debugButton = findViewById(R.id.debugButton)
        permissionStatusTextView = findViewById(R.id.permissionStatusTextView)

        // Set up listeners
        notificationAccessButton.setOnClickListener { openNotificationAccessSettings() }
        testForwardingButton.setOnClickListener { testMakeForwarding() }
        debugButton.setOnClickListener {
            val intent = Intent(this, SmsTestActivity::class.java)
            startActivity(intent)
        }

        autoForwardSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "MainActivity: Auto-forward switch changed: $isChecked")
            // Save preference
            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.KEY_AUTO_FORWARD_ENABLED, isChecked)
                .apply()

            if (isChecked) {
                // Request permissions if not granted
                if (!checkAndRequestPermissions()) {
                    // Permissions not granted, switch will be turned off in onRequestPermissionsResult
                    autoForwardSwitch.isChecked = false
                    return@setOnCheckedChangeListener
                }
                // Check notification access if permissions are granted
                if (!isNotificationServiceEnabled()) {
                    Toast.makeText(this, "⚠️ Please enable Notification Access for robust detection", Toast.LENGTH_LONG).show()
                    openNotificationAccessSettings()
                    autoForwardSwitch.isChecked = false // Turn off switch if notification access is not granted
                    return@setOnCheckedChangeListener
                }
                startOTPService()
                updateServiceStatusUI(true)
                Toast.makeText(this, "✅ OTP forwarding enabled", Toast.LENGTH_SHORT).show()
            } else {
                stopOTPService()
                updateServiceStatusUI(false)
                Toast.makeText(this, "❌ OTP forwarding disabled", Toast.LENGTH_SHORT).show()
            }
        }

        // Restore switch state and update UI on app start
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val isEnabled = prefs.getBoolean(Constants.KEY_AUTO_FORWARD_ENABLED, false)
        autoForwardSwitch.isChecked = isEnabled // This will trigger onCheckedChangeListener

        // Initial UI updates
        updateServiceStatusUI(OTPService.isServiceRunning)
        updateLastSentOtpUI(prefs.getString(Constants.KEY_LAST_SENT_OTP, null))
        checkAndRequestPermissions() // Check permissions on start to update status text
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity: onResume")
        // Re-check notification access and service status when activity resumes
        // This handles cases where user grants permission from settings
        updateNotificationAccessButtonState()
        updateServiceStatusUI(OTPService.isServiceRunning)
        checkAndRequestPermissions() // Re-check permissions status

        // Register LocalBroadcastReceiver to get updates from OTPService
        LocalBroadcastManager.getInstance(this).registerReceiver(
            otpForwardedReceiver, IntentFilter(Constants.ACTION_OTP_FORWARDED)
        )
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "MainActivity: onPause")
        // Unregister LocalBroadcastReceiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(otpForwardedReceiver)
    }

    /**
     * Checks for required permissions and requests them if not granted.
     * @return True if all permissions are granted, false otherwise.
     */
    private fun checkAndRequestPermissions(): Boolean {
        val permissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            Log.d(TAG, "MainActivity: Requesting permissions: ${permissionsNeeded.joinToString()}")
            ActivityCompat.requestPermissions(this, permissionsNeeded.toTypedArray(), SMS_PERMISSION_REQUEST_CODE)
            updatePermissionStatusUI(false, permissionsNeeded)
            return false
        } else {
            Log.d(TAG, "MainActivity: All required permissions already granted.")
            updatePermissionStatusUI(true, emptyList())
            return true
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == SMS_PERMISSION_REQUEST_CODE) {
            val deniedPermissions = mutableListOf<String>()
            for (i in permissions.indices) {
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    deniedPermissions.add(permissions[i])
                }
            }

            if (deniedPermissions.isEmpty()) {
                Toast.makeText(this, "✅ All permissions granted", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "MainActivity: All permissions granted.")
                updatePermissionStatusUI(true, emptyList())
                // If permissions are granted, re-evaluate switch state (might need to start service)
                if (autoForwardSwitch.isChecked) {
                    if (!isNotificationServiceEnabled()) {
                        Toast.makeText(this, "⚠️ Please enable Notification Access", Toast.LENGTH_LONG).show()
                        openNotificationAccessSettings()
                        autoForwardSwitch.isChecked = false
                    } else {
                        startOTPService()
                        updateServiceStatusUI(true)
                    }
                }
            } else {
                Toast.makeText(this, "❌ Required permissions denied: ${deniedPermissions.joinToString()}", Toast.LENGTH_LONG).show()
                Log.w(TAG, "MainActivity: Some permissions denied: ${deniedPermissions.joinToString()}")
                updatePermissionStatusUI(false, deniedPermissions)
                autoForwardSwitch.isChecked = false // Turn off switch if permissions are denied
                stopOTPService() // Ensure service is stopped if permissions are revoked
            }
        }
    }

    /**
     * Starts the OTPService as a foreground service.
     */
    private fun startOTPService() {
        val serviceIntent = Intent(this, OTPService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Log.d(TAG, "MainActivity: OTPService start requested.")
        updateServiceStatusUI(true)
    }

    /**
     * Stops the OTPService.
     */
    private fun stopOTPService() {
        val serviceIntent = Intent(this, OTPService::class.java)
        stopService(serviceIntent)
        Log.d(TAG, "MainActivity: OTPService stop requested.")
        updateServiceStatusUI(false)
    }

    /**
     * Checks if the NotificationListenerService is enabled for this app.
     * @return True if enabled, false otherwise.
     */
    private fun isNotificationServiceEnabled(): Boolean {
        val pkgName = packageName
        val flat = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )
        val isEnabled = flat != null && flat.contains(pkgName)
        Log.d(TAG, "MainActivity: Notification Listener enabled: $isEnabled")
        return isEnabled
    }

    /**
     * Opens the Android settings for Notification Listener access.
     */
    private fun openNotificationAccessSettings() {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Please enable Notification Access in Settings manually.", Toast.LENGTH_LONG).show()
            Log.e(TAG, "MainActivity: Error opening notification listener settings", e)
        }
    }

    /**
     * Sends a test OTP to Make.com via OTPForwarder.
     */
    private fun testMakeForwarding() {
        val testOtp = "123456"
        val testMessage = "This is a test message: Your verification code is $testOtp. Disregard."
        val testSender = "TEST_SENDER"

        Toast.makeText(this, "🧪 Sending test OTP to Make.com...", Toast.LENGTH_SHORT).show()

        // Directly call OTPForwarder for test, it handles its own duplicate check
        OTPForwarder.forwardOtpViaMake(testOtp, testMessage, testSender, this)
    }

    /**
     * Updates the UI to reflect the service's running status.
     * @param isRunning True if the service is running, false otherwise.
     */
    private fun updateServiceStatusUI(isRunning: Boolean) {
        if (isRunning) {
            serviceStatusTextView.text = "Service: Running ✅"
            serviceStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            serviceStatusTextView.text = "Service: Not Running ❌"
            serviceStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
        updateNotificationAccessButtonState() // Update button state based on service status
    }

    /**
     * Updates the UI with the last forwarded OTP.
     * @param otp The last OTP forwarded, or null if none.
     */
    private fun updateLastSentOtpUI(otp: String?) {
        lastSentOtpTextView.text = if (otp != null) {
            "Last OTP sent: $otp"
        } else {
            "Last OTP sent: N/A"
        }
    }

    /**
     * Updates the state of the Notification Access button based on permission status.
     */
    private fun updateNotificationAccessButtonState() {
        if (isNotificationServiceEnabled()) {
            notificationAccessButton.text = "Notification Access: Granted ✅"
            notificationAccessButton.isEnabled = false
            notificationAccessButton.alpha = 0.5f // Dim button if granted
        } else {
            notificationAccessButton.text = "Grant Notification Access ⚠️"
            notificationAccessButton.isEnabled = true
            notificationAccessButton.alpha = 1.0f
        }
    }

    /**
     * Updates the UI to show current permission status.
     * @param allGranted True if all required permissions are granted.
     * @param deniedPermissions List of permissions that are denied.
     */
    private fun updatePermissionStatusUI(allGranted: Boolean, deniedPermissions: List<String>) {
        if (allGranted) {
            permissionStatusTextView.text = "Permissions: All Granted ✅"
            permissionStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        } else {
            val statusText = "Permissions: Denied ❌ (${deniedPermissions.joinToString()})"
            permissionStatusTextView.text = statusText
            permissionStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }
    }
}
