package com.example.otprelay

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val TAG = Constants.LOG_TAG
    private val SMS_PERMISSION_REQUEST_CODE = 100
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 101 // For POST_NOTIFICATIONS on Android 13+

    // UI Elements
    private lateinit var autoForwardSwitch: SwitchMaterial
    private lateinit var serviceStatusTextView: TextView
    private lateinit var lastSentOtpTextView: TextView // New: For displaying last sent OTP
    private lateinit var notificationAccessButton: Button
    private lateinit var smsPermissionButton: Button
    private lateinit var notificationPermissionButton: Button
    private lateinit var testForwardingButton: Button
    private lateinit var debugButton: Button
    private lateinit var permissionStatusTextView: TextView

    // Forwarding Method & Settings
    private lateinit var forwardingMethodRadioGroup: RadioGroup
    private lateinit var radioWebhook: RadioButton
    private lateinit var radioDirectEmail: RadioButton
    private lateinit var webhookUrlEditText: EditText
    private lateinit var webhookSettingsLayout: LinearLayout // To show/hide webhook settings
    private lateinit var directEmailSettingsLayout: LinearLayout // To show/hide direct email settings

    // Direct Email (SMTP) Settings
    private lateinit var recipientEmailEditText: EditText
    private lateinit var senderEmailEditText: EditText
    private lateinit var smtpHostEditText: EditText
    private lateinit var smtpPortEditText: EditText
    private lateinit var smtpUsernameEditText: EditText
    private lateinit var smtpPasswordEditText: EditText

    // OTP Length Settings
    private lateinit var otpMinLengthEditText: EditText
    private lateinit var otpMaxLengthEditText: EditText

    // BroadcastReceiver to get updates from OTPService
    private val otpForwardedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Constants.ACTION_OTP_FORWARDED) {
                val forwardedOtp = intent.getStringExtra(Constants.EXTRA_FORWARDED_OTP)
                val sender = intent.getStringExtra(Constants.EXTRA_SENDER)
                val timestamp = intent.getLongExtra(Constants.EXTRA_TIMESTAMP, 0L)

                if (forwardedOtp != null && sender != null && timestamp != 0L) {
                    updateLastSentOtpUI(forwardedOtp, sender, timestamp)
                } else {
                    Log.w(TAG, "OTPForwardedReceiver: Received broadcast with missing data.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // This assumes you have activity_main.xml

        Log.d(TAG, "MainActivity: onCreate")

        // Initialize UI elements (findViewById)
        autoForwardSwitch = findViewById(R.id.autoForwardSwitch)
        serviceStatusTextView = findViewById(R.id.serviceStatusTextView)
        lastSentOtpTextView = findViewById(R.id.lastSentOtpTextView) // Initialize the new TextView
        notificationAccessButton = findViewById(R.id.notificationAccessButton)
        smsPermissionButton = findViewById(R.id.smsPermissionButton)
        notificationPermissionButton = findViewById(R.id.notificationPermissionButton)
        testForwardingButton = findViewById(R.id.testForwardingButton)
        debugButton = findViewById(R.id.debugButton)
        permissionStatusTextView = findViewById(R.id.permissionStatusTextView)

        // Forwarding Method & Settings
        forwardingMethodRadioGroup = findViewById(R.id.forwardingMethodRadioGroup)
        radioWebhook = findViewById(R.id.radioWebhook)
        radioDirectEmail = findViewById(R.id.radioDirectEmail)
        webhookUrlEditText = findViewById(R.id.webhookUrlEditText)
        webhookSettingsLayout = findViewById(R.id.webhookSettingsLayout)
        directEmailSettingsLayout = findViewById(R.id.directEmailSettingsLayout)

        // Direct Email (SMTP) Settings
        recipientEmailEditText = findViewById(R.id.recipientEmailEditText)
        senderEmailEditText = findViewById(R.id.senderEmailEditText)
        smtpHostEditText = findViewById(R.id.smtpHostEditText)
        smtpPortEditText = findViewById(R.id.smtpPortEditText)
        smtpUsernameEditText = findViewById(R.id.smtpUsernameEditText)
        smtpPasswordEditText = findViewById(R.id.smtpPasswordEditText)

        // OTP Length Settings
        otpMinLengthEditText = findViewById(R.id.minOtpLengthEditText)
        otpMaxLengthEditText = findViewById(R.id.maxOtpLengthEditText)

        // Load saved preferences and update UI
        loadPreferences()

        // Set up listeners

        // Permission Buttons
        smsPermissionButton.setOnClickListener {
            requestSmsPermissions()
        }
        notificationPermissionButton.setOnClickListener {
            requestNotificationPermission()
        }
        notificationAccessButton.setOnClickListener { openNotificationAccessSettings() }

        // Test and Debug Buttons
        testForwardingButton.setOnClickListener { testMakeForwarding() }
        debugButton.setOnClickListener {
            val intent = Intent(this, SmsTestActivity::class.java) // Assuming SmsTestActivity exists
            startActivity(intent)
        }

        // Auto-forward switch listener
        autoForwardSwitch.setOnCheckedChangeListener { _, isChecked ->
            Log.d(TAG, "MainActivity: Auto-forward switch changed: $isChecked")
            // Save the new switch state immediately
            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.KEY_IS_FORWARDING_ENABLED, isChecked)
                .apply()

            if (isChecked) {
                // Check all permissions required to start service
                if (isServiceReadyToRun()) {
                    startOTPService()
                    Toast.makeText(this, "✅ OTP forwarding enabled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Please grant all required permissions first.", Toast.LENGTH_LONG).show()
                    // Revert switch if permissions are missing
                    autoForwardSwitch.isChecked = false
                    // Update UI to reflect the reverted switch state
                    updateAllPermissionStatusUI()
                    updateServiceStatusUI(false) // Service is not running
                }
            } else {
                stopOTPService()
                Toast.makeText(this, "❌ OTP forwarding disabled", Toast.LENGTH_SHORT).show()
            }
            // Update UI after logic, especially since autoForwardSwitch.isChecked might be reverted
            updateServiceStatusUI(isServiceRunning(OTPService::class.java))
        }

        // Forwarding Method RadioGroup Listener
        forwardingMethodRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMethod = when (checkedId) {
                R.id.radioWebhook -> Constants.FORWARDING_METHOD_WEBHOOK
                R.id.radioDirectEmail -> Constants.FORWARDING_METHOD_DIRECT_EMAIL
                else -> Constants.FORWARDING_METHOD_WEBHOOK // Default
            }
            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(Constants.KEY_FORWARDING_METHOD, newMethod)
                .apply()
            updateForwardingMethodUI(newMethod)
        }

        // TextWatchers for saving EditText preferences
        setupEditTextPreferenceSaving()

        // Initial UI updates after listeners are set up
        updateServiceStatusUI(isServiceRunning(OTPService::class.java))
        updateAllPermissionStatusUI()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity: onResume")
        // Re-check all permissions and service status when activity resumes
        updateAllPermissionStatusUI()
        updateServiceStatusUI(isServiceRunning(OTPService::class.java))

        // Re-load last sent OTP details on resume
        loadLastSentOtpDetails()

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

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity: onDestroy")
    }

    /**
     * Loads all saved preferences and updates the UI elements accordingly.
     */
    private fun loadPreferences() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        // Switch state (this will also trigger the onCheckedChangeListener but that's fine for initial load)
        autoForwardSwitch.isChecked = prefs.getBoolean(Constants.KEY_IS_FORWARDING_ENABLED, false)

        // Forwarding method
        val savedForwardingMethod = prefs.getString(Constants.KEY_FORWARDING_METHOD, Constants.FORWARDING_METHOD_WEBHOOK) ?: Constants.FORWARDING_METHOD_WEBHOOK
        when (savedForwardingMethod) {
            Constants.FORWARDING_METHOD_WEBHOOK -> radioWebhook.isChecked = true
            Constants.FORWARDING_METHOD_DIRECT_EMAIL -> radioDirectEmail.isChecked = true
        }
        updateForwardingMethodUI(savedForwardingMethod) // Ensure visibility is set correctly

        // Webhook URL
        webhookUrlEditText.setText(prefs.getString(Constants.KEY_WEBHOOK_URL, Constants.MAKE_WEBHOOK_URL))

        // SMTP Settings
        recipientEmailEditText.setText(prefs.getString(Constants.KEY_RECIPIENT_EMAIL, ""))
        senderEmailEditText.setText(prefs.getString(Constants.KEY_SENDER_EMAIL, ""))
        smtpHostEditText.setText(prefs.getString(Constants.KEY_SMTP_HOST, ""))
        smtpPortEditText.setText(prefs.getInt(Constants.KEY_SMTP_PORT, 587).toString())
        smtpUsernameEditText.setText(prefs.getString(Constants.KEY_SMTP_USERNAME, ""))
        smtpPasswordEditText.setText(prefs.getString(Constants.KEY_SMTP_PASSWORD, ""))

        // OTP Length Settings
        otpMinLengthEditText.setText(prefs.getInt(Constants.KEY_OTP_MIN_LENGTH, Constants.DEFAULT_OTP_MIN_LENGTH).toString())
        otpMaxLengthEditText.setText(prefs.getInt(Constants.KEY_OTP_MAX_LENGTH, Constants.DEFAULT_OTP_MAX_LENGTH).toString())

        // Load and display the last sent OTP details
        loadLastSentOtpDetails()
    }

    /**
     * Loads the last forwarded OTP details from SharedPreferences and updates the UI.
     */
    private fun loadLastSentOtpDetails() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val lastOtp = prefs.getString("last_forwarded_otp_value", null)
        val lastSender = prefs.getString("last_forwarded_otp_sender", null)
        val lastTimestamp = prefs.getLong("last_forwarded_otp_timestamp", 0L)

        if (lastOtp != null && lastSender != null && lastTimestamp != 0L) {
            updateLastSentOtpUI(lastOtp, lastSender, lastTimestamp)
        } else {
            // Use a string resource for "N/A"
            lastSentOtpTextView.text = getString(R.string.last_otp_sent_na)
            Log.d(TAG, "No last sent OTP data found in preferences.")
        }
    }


    /**
     * Sets up TextWatchers for all EditText fields to save their values to SharedPreferences.
     */
    private fun setupEditTextPreferenceSaving() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        fun EditText.addPreferenceWatcher(key: String, type: String = "string") {
            this.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val editor = prefs.edit()
                    when (type) {
                        "string" -> editor.putString(key, s.toString())
                        "int" -> editor.putInt(key, s.toString().toIntOrNull() ?: 0)
                    }
                    editor.apply()
                }
            })
        }

        webhookUrlEditText.addPreferenceWatcher(Constants.KEY_WEBHOOK_URL)
        recipientEmailEditText.addPreferenceWatcher(Constants.KEY_RECIPIENT_EMAIL)
        senderEmailEditText.addPreferenceWatcher(Constants.KEY_SENDER_EMAIL)
        smtpHostEditText.addPreferenceWatcher(Constants.KEY_SMTP_HOST)
        smtpPortEditText.addPreferenceWatcher(Constants.KEY_SMTP_PORT, "int")
        smtpUsernameEditText.addPreferenceWatcher(Constants.KEY_SMTP_USERNAME)
        smtpPasswordEditText.addPreferenceWatcher(Constants.KEY_SMTP_PASSWORD)
        otpMinLengthEditText.addPreferenceWatcher(Constants.KEY_OTP_MIN_LENGTH, "int")
        otpMaxLengthEditText.addPreferenceWatcher(Constants.KEY_OTP_MAX_LENGTH, "int")
    }

    /**
     * Checks if RECEIVE_SMS, READ_SMS, and SEND_SMS permissions are granted.
     * @return True if all SMS permissions are granted, false otherwise.
     */
    private fun hasSmsPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Requests RECEIVE_SMS, READ_SMS, and SEND_SMS permissions.
     */
    private fun requestSmsPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), SMS_PERMISSION_REQUEST_CODE)
        } else {
            Toast.makeText(this, "SMS permissions already granted.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Checks if POST_NOTIFICATIONS permission is granted (Android 13+).
     * @return True if granted or not required, false otherwise.
     */
    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        return true // Permission not required on older Android versions
    }

    /**
     * Requests POST_NOTIFICATIONS permission (Android 13+).
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
            } else {
                Toast.makeText(this, "Notification permission already granted.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Notification permission not applicable on this Android version.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            SMS_PERMISSION_REQUEST_CODE -> {
                val smsPermissionsGranted = grantResults.isNotEmpty() && permissions.all { perm ->
                    ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
                }
                if (smsPermissionsGranted) {
                    Toast.makeText(this, "✅ All SMS permissions granted!", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "All SMS permissions granted.")
                } else {
                    Toast.makeText(this, "❌ Some SMS permissions denied. OTP forwarding may not work.", Toast.LENGTH_LONG).show()
                    Log.w(TAG, "Some SMS permissions denied.")
                    // If SMS permissions are denied, force switch off
                    if (autoForwardSwitch.isChecked) {
                        autoForwardSwitch.isChecked = false
                    }
                }
                updateAllPermissionStatusUI()
            }
            NOTIFICATION_PERMISSION_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "✅ Notification permission granted!", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Notification permission granted.")
                } else {
                    Toast.makeText(this, "❌ Notification permission denied. App may not show alerts.", Toast.LENGTH_LONG).show()
                    Log.w(TAG, "Notification permission denied.")
                    // If notification permission is denied, force switch off
                    if (autoForwardSwitch.isChecked) {
                        autoForwardSwitch.isChecked = false
                    }
                }
                updateAllPermissionStatusUI()
            }
        }
    }

    /**
     * Checks if a service is running using ActivityManager.
     * @param serviceClass The Class object of the service to check.
     * @return True if the service is running, false otherwise.
     */
    @Suppress("DEPRECATION")
    private fun <T> isServiceRunning(serviceClass: Class<T>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    /**
     * Starts the OTPService as a foreground service.
     */
    private fun startOTPService() {
        if (!isServiceRunning(OTPService::class.java)) {
            Log.d(TAG, "MainActivity: Attempting to start OTPService.")
            val serviceIntent = Intent(this, OTPService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Toast.makeText(this, "OTP Forwarding Service Started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "MainActivity: Failed to start OTPService: ${e.message}", e)
                Toast.makeText(this, "Failed to start service: ${e.message}", Toast.LENGTH_LONG).show()
                // If service fails to start, ensure the switch is off
                if (autoForwardSwitch.isChecked) {
                    autoForwardSwitch.isChecked = false
                }
            }
        } else {
            Log.d(TAG, "MainActivity: OTPService is already running.")
        }
        updateServiceStatusUI(isServiceRunning(OTPService::class.java))
    }

    /**
     * Stops the OTPService.
     */
    private fun stopOTPService() {
        if (isServiceRunning(OTPService::class.java)) {
            Log.d(TAG, "MainActivity: Attempting to stop OTPService.")
            val serviceIntent = Intent(this, OTPService::class.java)
            stopService(serviceIntent)
            Toast.makeText(this, "OTP Forwarding Service Stopped", Toast.LENGTH_SHORT).show()
        } else {
            Log.d(TAG, "MainActivity: OTPService is not running.")
        }
        updateServiceStatusUI(isServiceRunning(OTPService::class.java))
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

        Toast.makeText(this, "🧪 Sending test OTP...", Toast.LENGTH_SHORT).show()

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
    }

    /**
     * Updates the UI with the last forwarded OTP, sender, and timestamp.
     * Also persists this data to SharedPreferences.
     * @param otp The last OTP forwarded.
     * @param sender The sender of the OTP.
     * @param timestamp The timestamp when the OTP was forwarded.
     */
    private fun updateLastSentOtpUI(otp: String, sender: String, timestamp: Long) {
        val formattedDate = SimpleDateFormat("HH:mm:ss (dd MMM)", Locale.getDefault()).format(Date(timestamp))
        lastSentOtpTextView.text = getString(R.string.last_otp_sent_format, otp, sender, formattedDate)
        Log.d(TAG, "UI Updated: Last sent OTP: '$otp' from '$sender' at $formattedDate")

        // Persist the details to SharedPreferences
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("last_forwarded_otp_value", otp)
            putString("last_forwarded_otp_sender", sender)
            putLong("last_forwarded_otp_timestamp", timestamp)
            apply()
        }
    }

    /**
     * Updates the visibility of forwarding method specific settings layouts.
     * @param method The currently selected forwarding method.
     */
    private fun updateForwardingMethodUI(method: String) {
        when (method) {
            Constants.FORWARDING_METHOD_WEBHOOK -> {
                webhookSettingsLayout.visibility = View.VISIBLE
                directEmailSettingsLayout.visibility = View.GONE
            }
            Constants.FORWARDING_METHOD_DIRECT_EMAIL -> {
                webhookSettingsLayout.visibility = View.GONE
                directEmailSettingsLayout.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Checks all relevant permissions and updates their status in the UI.
     */
    private fun updateAllPermissionStatusUI() {
        val smsGranted = hasSmsPermissions()
        val notificationPermGranted = hasNotificationPermission()
        val notificationAccessGranted = isNotificationServiceEnabled()

        // Update overall permission status text view
        val deniedPermissions = mutableListOf<String>()
        if (!smsGranted) deniedPermissions.add("SMS")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermGranted) deniedPermissions.add("Notifications (A13+)")
        if (!notificationAccessGranted) deniedPermissions.add("Notification Listener")

        if (deniedPermissions.isEmpty()) {
            permissionStatusTextView.text = "Permissions: All Granted ✅"
            permissionStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        } else {
            val statusText = "Permissions: Denied ❌ (${deniedPermissions.joinToString()})"
            permissionStatusTextView.text = statusText
            permissionStatusTextView.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        // Update individual permission buttons
        smsPermissionButton.isEnabled = !smsGranted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionButton.visibility = View.VISIBLE
            notificationPermissionButton.isEnabled = !notificationPermGranted
        } else {
            notificationPermissionButton.visibility = View.GONE
        }
        notificationAccessButton.isEnabled = !notificationAccessGranted
        if (notificationAccessGranted) {
            notificationAccessButton.text = "Notification Access: Granted ✅"
            notificationAccessButton.alpha = 0.5f // Dim button if granted
        } else {
            notificationAccessButton.text = "Grant Notification Access ⚠️"
            notificationAccessButton.alpha = 1.0f
        }

        // Also control the auto-forward switch's enabled state based on critical permissions
        autoForwardSwitch.isEnabled = smsGranted && notificationAccessGranted && notificationPermGranted
        if (!autoForwardSwitch.isEnabled && autoForwardSwitch.isChecked) {
            // If the switch is checked but permissions are no longer met, turn it off.
            autoForwardSwitch.isChecked = false
        }
    }

    /**
     * Helper to check if the service is ready to be started based on current permissions.
     */
    private fun isServiceReadyToRun(): Boolean {
        val smsGranted = hasSmsPermissions()
        val notificationPermGranted = hasNotificationPermission()
        val notificationAccessGranted = isNotificationServiceEnabled()
        return smsGranted && notificationPermGranted && notificationAccessGranted
    }
}