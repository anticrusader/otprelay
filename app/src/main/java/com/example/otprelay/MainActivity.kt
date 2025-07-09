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
import android.view.inputmethod.EditorInfo
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial
import java.text.SimpleDateFormat
import java.util.*
import android.content.ComponentName

class MainActivity : AppCompatActivity() {

    private val TAG = Constants.LOG_TAG
    private val SMS_PERMISSION_REQUEST_CODE = 100
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 101 // For POST_NOTIFICATIONS on Android 13+

    // UI Elements
    private lateinit var autoForwardSwitch: SwitchMaterial
    private lateinit var serviceStatusTextView: TextView
    private lateinit var lastSentOtpTextView: TextView
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

    // New: Keyword Filtering UI elements
    private lateinit var keywordEditText: EditText
    private lateinit var addKeywordButton: Button
    private lateinit var keywordChipGroup: ChipGroup
    private var smsKeywords: MutableSet<String> = mutableSetOf() // In-memory set of keywords

    // New: Regex Management UI elements
    private lateinit var regexEditText: EditText
    private lateinit var addRegexButton: Button
    private lateinit var regexChipGroup: ChipGroup
    private var customOtpRegexes: MutableSet<String> = mutableSetOf()

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
        setContentView(R.layout.activity_main) // Assuming activity_main.xml

        // Initialize UI elements
        autoForwardSwitch = findViewById(R.id.autoForwardSwitch)
        serviceStatusTextView = findViewById(R.id.serviceStatusTextView)
        lastSentOtpTextView = findViewById(R.id.lastSentOtpTextView)
        notificationAccessButton = findViewById(R.id.notificationAccessButton)
        smsPermissionButton = findViewById(R.id.smsPermissionButton)
        notificationPermissionButton = findViewById(R.id.notificationPermissionButton)
        testForwardingButton = findViewById(R.id.testForwardingButton)
        debugButton = findViewById(R.id.debugButton)
        permissionStatusTextView = findViewById(R.id.permissionStatusTextView)

        forwardingMethodRadioGroup = findViewById(R.id.forwardingMethodRadioGroup)
        radioWebhook = findViewById(R.id.radioWebhook)
        radioDirectEmail = findViewById(R.id.radioDirectEmail)
        webhookUrlEditText = findViewById(R.id.webhookUrlEditText)
        webhookSettingsLayout = findViewById(R.id.webhookSettingsLayout)
        directEmailSettingsLayout = findViewById(R.id.directEmailSettingsLayout)

        recipientEmailEditText = findViewById(R.id.recipientEmailEditText)
        senderEmailEditText = findViewById(R.id.senderEmailEditText)
        smtpHostEditText = findViewById(R.id.smtpHostEditText)
        smtpPortEditText = findViewById(R.id.smtpPortEditText)
        smtpUsernameEditText = findViewById(R.id.smtpUsernameEditText)
        smtpPasswordEditText = findViewById(R.id.smtpPasswordEditText)

        otpMinLengthEditText = findViewById(R.id.minOtpLengthEditText)
        otpMaxLengthEditText = findViewById(R.id.maxOtpLengthEditText)

        keywordEditText = findViewById(R.id.keywordEditText)
        addKeywordButton = findViewById(R.id.addKeywordButton)
        keywordChipGroup = findViewById(R.id.keywordChipGroup)

        regexEditText = findViewById(R.id.regexEditText)
        addRegexButton = findViewById(R.id.addRegexButton)
        regexChipGroup = findViewById(R.id.regexChipGroup)


        // Load saved preferences
        loadPreferences()
        loadSmsKeywords()
        loadCustomOtpRegexes()

        // Set listeners
        autoForwardSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveBooleanPreference(Constants.KEY_IS_FORWARDING_ENABLED, isChecked)
            if (isChecked) {
                startOtpService()
            } else {
                stopOtpService()
            }
            updateServiceStatusUI()
            updateUiBasedOnPermissions() // Re-check button states
        }

        forwardingMethodRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radioWebhook -> {
                    saveStringPreference(Constants.KEY_FORWARDING_METHOD, Constants.FORWARDING_METHOD_WEBHOOK)
                    webhookSettingsLayout.visibility = View.VISIBLE
                    directEmailSettingsLayout.visibility = View.GONE
                }
                R.id.radioDirectEmail -> {
                    saveStringPreference(Constants.KEY_FORWARDING_METHOD, Constants.FORWARDING_METHOD_DIRECT_EMAIL)
                    webhookSettingsLayout.visibility = View.GONE
                    directEmailSettingsLayout.visibility = View.VISIBLE
                }
            }
        }

        // Text Watchers for settings that need immediate saving or UI updates
        webhookUrlEditText.addTextChangedListener(afterTextChanged = { s -> saveStringPreference(Constants.KEY_WEBHOOK_URL, s.toString()) })
        recipientEmailEditText.addTextChangedListener(afterTextChanged = { s -> saveStringPreference(Constants.KEY_RECIPIENT_EMAIL, s.toString()) })
        senderEmailEditText.addTextChangedListener(afterTextChanged = { s -> saveStringPreference(Constants.KEY_SENDER_EMAIL, s.toString()) })
        smtpHostEditText.addTextChangedListener(afterTextChanged = { s -> saveStringPreference(Constants.KEY_SMTP_HOST, s.toString()) })
        smtpPortEditText.addTextChangedListener(afterTextChanged = { s -> saveIntPreference(Constants.KEY_SMTP_PORT, s.toString().toIntOrNull() ?: 0) })
        smtpUsernameEditText.addTextChangedListener(afterTextChanged = { s -> saveStringPreference(Constants.KEY_SMTP_USERNAME, s.toString()) })
        smtpPasswordEditText.addTextChangedListener(afterTextChanged = { s ->
            // IMPORTANT: For production, consider EncryptedSharedPreferences for passwords
            Log.w(TAG, "SMTP Password being saved in plain text. Consider EncryptedSharedPreferences!")
            saveStringPreference(Constants.KEY_SMTP_PASSWORD, s.toString())
        })

        otpMinLengthEditText.addTextChangedListener(afterTextChanged = { s ->
            saveIntPreference(Constants.KEY_OTP_MIN_LENGTH, s.toString().toIntOrNull() ?: Constants.DEFAULT_OTP_MIN_LENGTH)
        })
        otpMaxLengthEditText.addTextChangedListener(afterTextChanged = { s ->
            saveIntPreference(Constants.KEY_OTP_MAX_LENGTH, s.toString().toIntOrNull() ?: Constants.DEFAULT_OTP_MAX_LENGTH)
        })


        addKeywordButton.setOnClickListener { addKeyword() }
        keywordEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addKeyword()
                true
            } else {
                false
            }
        }

        addRegexButton.setOnClickListener { addRegex() }
        regexEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addRegex()
                true
            } else {
                false
            }
        }

        smsPermissionButton.setOnClickListener {
            requestSmsPermissions()
        }

        notificationPermissionButton.setOnClickListener {
            requestNotificationPermission()
        }

        notificationAccessButton.setOnClickListener {
            requestNotificationAccess()
        }

        testForwardingButton.setOnClickListener {
            testForwarding()
        }

        debugButton.setOnClickListener {
            // Placeholder for debug functionality
            Toast.makeText(this, "Debug button clicked (Not implemented yet)", Toast.LENGTH_SHORT).show()
        }

        // Register BroadcastReceiver
        LocalBroadcastManager.getInstance(this).registerReceiver(
            otpForwardedReceiver,
            IntentFilter(Constants.ACTION_OTP_FORWARDED)
        )
    }

    override fun onResume() {
        super.onResume()
        updateUiBasedOnPermissions()
        updateServiceStatusUI()
        loadSmsKeywords() // Reload keywords in case they were cleared or changed elsewhere
        loadCustomOtpRegexes() // Reload custom regexes
    }

    override fun onDestroy() {
        super.onDestroy()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(otpForwardedReceiver)
    }

    private fun loadPreferences() {
        val sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        autoForwardSwitch.isChecked = sharedPrefs.getBoolean(Constants.KEY_IS_FORWARDING_ENABLED, false)

        val forwardingMethod = sharedPrefs.getString(Constants.KEY_FORWARDING_METHOD, Constants.FORWARDING_METHOD_WEBHOOK)
        if (forwardingMethod == Constants.FORWARDING_METHOD_WEBHOOK) {
            radioWebhook.isChecked = true
            webhookSettingsLayout.visibility = View.VISIBLE
            directEmailSettingsLayout.visibility = View.GONE
        } else {
            radioDirectEmail.isChecked = true
            webhookSettingsLayout.visibility = View.GONE
            directEmailSettingsLayout.visibility = View.VISIBLE
        }

        webhookUrlEditText.setText(sharedPrefs.getString(Constants.KEY_WEBHOOK_URL, ""))
        recipientEmailEditText.setText(sharedPrefs.getString(Constants.KEY_RECIPIENT_EMAIL, ""))
        senderEmailEditText.setText(sharedPrefs.getString(Constants.KEY_SENDER_EMAIL, ""))
        smtpHostEditText.setText(sharedPrefs.getString(Constants.KEY_SMTP_HOST, ""))
        val smtpPort = sharedPrefs.getInt(Constants.KEY_SMTP_PORT, 0)
        smtpPortEditText.setText(if (smtpPort != 0) smtpPort.toString() else "")
        smtpUsernameEditText.setText(sharedPrefs.getString(Constants.KEY_SMTP_USERNAME, ""))
        smtpPasswordEditText.setText(sharedPrefs.getString(Constants.KEY_SMTP_PASSWORD, "")) // Read with caution

        otpMinLengthEditText.setText(sharedPrefs.getInt(Constants.KEY_OTP_MIN_LENGTH, Constants.DEFAULT_OTP_MIN_LENGTH).toString())
        otpMaxLengthEditText.setText(sharedPrefs.getInt(Constants.KEY_OTP_MAX_LENGTH, Constants.DEFAULT_OTP_MAX_LENGTH).toString())
    }

    private fun saveStringPreference(key: String, value: String) {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().putString(key, value).apply()
    }

    private fun saveBooleanPreference(key: String, value: Boolean) {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    private fun saveIntPreference(key: String, value: Int) {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().putInt(key, value).apply()
    }

    private fun addKeyword() {
        val keyword = keywordEditText.text.toString().trim()
        if (keyword.isNotBlank()) {
            if (smsKeywords.add(keyword)) { // Add only if new
                addChipToGroup(keyword, keywordChipGroup, smsKeywords, Constants.KEY_SMS_KEYWORDS)
                saveSmsKeywords()
            }
            keywordEditText.text.clear()
        }
    }

    private fun loadSmsKeywords() {
        val sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        smsKeywords = sharedPrefs.getStringSet(Constants.KEY_SMS_KEYWORDS, Constants.DEFAULT_SMS_KEYWORDS.toMutableSet())?.toMutableSet() ?: mutableSetOf()
        updateKeywordChipGroup()
    }

    private fun saveSmsKeywords() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(Constants.KEY_SMS_KEYWORDS, smsKeywords)
            .apply()
    }

    private fun updateKeywordChipGroup() {
        keywordChipGroup.removeAllViews()
        smsKeywords.forEach { keyword ->
            addChipToGroup(keyword, keywordChipGroup, smsKeywords, Constants.KEY_SMS_KEYWORDS)
        }
    }

    private fun addRegex() {
        val regex = regexEditText.text.toString().trim()
        if (regex.isNotBlank()) {
            // Basic regex validation (can be more robust if needed)
            try {
                regex.toRegex()
                if (customOtpRegexes.add(regex)) {
                    addChipToGroup(regex, regexChipGroup, customOtpRegexes, Constants.KEY_CUSTOM_OTP_REGEXES)
                    saveCustomOtpRegexes()
                }
                regexEditText.text.clear()
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid regex pattern: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e(TAG, "Invalid regex entered: $regex", e)
            }
        }
    }

    private fun loadCustomOtpRegexes() {
        val sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        customOtpRegexes = sharedPrefs.getStringSet(Constants.KEY_CUSTOM_OTP_REGEXES, Constants.DEFAULT_OTP_REGEXES.toMutableSet())?.toMutableSet() ?: mutableSetOf()
        updateRegexChipGroup()
    }

    private fun saveCustomOtpRegexes() {
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(Constants.KEY_CUSTOM_OTP_REGEXES, customOtpRegexes)
            .apply()
    }

    private fun updateRegexChipGroup() {
        regexChipGroup.removeAllViews()
        customOtpRegexes.forEach { regex ->
            addChipToGroup(regex, regexChipGroup, customOtpRegexes, Constants.KEY_CUSTOM_OTP_REGEXES)
        }
    }

    private fun addChipToGroup(text: String, chipGroup: ChipGroup, set: MutableSet<String>, prefKey: String) {
        val chip = Chip(this).apply {
            this.text = text
            isCloseIconVisible = true
            setOnCloseIconClickListener {
                chipGroup.removeView(this)
                set.remove(text)
                getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(prefKey, set)
                    .apply()
                Toast.makeText(context, "'$text' removed.", Toast.LENGTH_SHORT).show()
            }
        }
        chipGroup.addView(chip)
    }

    private fun startOtpService() {
        if (!isOtpServiceRunning()) {
            val serviceIntent = Intent(this, OTPService::class.java)
            serviceIntent.action = OTPService.ACTION_START_FOREGROUND
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.d(TAG, "OTPService started.")
        }
    }

    private fun stopOtpService() {
        if (isOtpServiceRunning()) {
            val serviceIntent = Intent(this, OTPService::class.java)
            serviceIntent.action = OTPService.ACTION_STOP_FOREGROUND
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // For Android O+, calling stopService on a foreground service will stop it
                // You don't need to call stopForeground separately from here as the service handles it in onDestroy
                stopService(serviceIntent)
            } else {
                stopService(serviceIntent)
            }
            Log.d(TAG, "OTPService stopped.")
        }
    }

    private fun isOtpServiceRunning(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (OTPService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun hasSmsPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestSmsPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS),
            SMS_PERMISSION_REQUEST_CODE
        )
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Permissions are granted by default on older versions or not required
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val cn = ComponentName(this, OTPNotificationListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun requestNotificationAccess() {
        OTPForwarder.openNotificationAccessSettings(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == SMS_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "SMS permissions granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "SMS permissions denied. App may not function correctly.", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Notification permission denied. You may not receive notifications.", Toast.LENGTH_LONG).show()
            }
        }
        updateUiBasedOnPermissions()
    }

    private fun updateServiceStatusUI() {
        if (isOtpServiceRunning()) {
            serviceStatusTextView.text = "Service Status: Running ✅"
            serviceStatusTextView.setTextColor(ContextCompat.getColor(this, R.color.green)) // Assuming a green color resource
        } else {
            serviceStatusTextView.text = "Service Status: Stopped ❌"
            serviceStatusTextView.setTextColor(ContextCompat.getColor(this, R.color.red)) // Assuming a red color resource
        }
    }

    private fun updateLastSentOtpUI(otp: String, sender: String, timestamp: Long) {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val formattedDate = sdf.format(Date(timestamp))
        lastSentOtpTextView.text = "Last forwarded OTP: $otp from $sender at $formattedDate"
        lastSentOtpTextView.visibility = View.VISIBLE
    }

    private fun updateUiBasedOnPermissions() {
        val smsGranted = hasSmsPermissions()
        val notificationPermGranted = hasNotificationPermission()
        val notificationAccessGranted = isNotificationServiceEnabled()

        permissionStatusTextView.text = """
            SMS Read/Receive: ${if (smsGranted) "Granted ✅" else "Denied ❌"}
            Post Notifications (Android 13+): ${if (notificationPermGranted) "Granted ✅" else "Denied ❌"}
            Notification Access: ${if (notificationAccessGranted) "Granted ✅" else "Denied ❌"}
        """.trimIndent()

        smsPermissionButton.isEnabled = !smsGranted
        if (smsGranted) {
            smsPermissionButton.visibility = View.GONE
        } else {
            smsPermissionButton.visibility = View.VISIBLE
        }

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
     * Simulates an OTP message and attempts to forward it using the current settings.
     * This is for testing purposes.
     */
    private fun testForwarding() {
        val testMessageBody = "Your OTP code is 123456 for testing."
        val testSender = "TestSender"
        val testTimestamp = System.currentTimeMillis()

        // Get current preferences to simulate how the service would read them
        val sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val customRegexes = sharedPrefs.getStringSet(Constants.KEY_CUSTOM_OTP_REGEXES, Constants.DEFAULT_OTP_REGEXES)?.toSet() ?: emptySet()
        val smsKeywords = sharedPrefs.getStringSet(Constants.KEY_SMS_KEYWORDS, Constants.DEFAULT_SMS_KEYWORDS)?.toSet() ?: emptySet()
        val otpMinLength = sharedPrefs.getInt(Constants.KEY_OTP_MIN_LENGTH, Constants.DEFAULT_OTP_MIN_LENGTH)
        val otpMaxLength = sharedPrefs.getInt(Constants.KEY_OTP_MAX_LENGTH, Constants.DEFAULT_OTP_MAX_LENGTH)

        // Attempt to extract OTP using the configured regexes and lengths
        val extractedOtp = OTPForwarder.extractOtpFromMessage(
            testMessageBody,
            this,
            customRegexes,
            otpMinLength,
            otpMaxLength
        )

        if (extractedOtp != null) {
            Toast.makeText(this, "Test OTP extracted: $extractedOtp. Attempting to forward...", Toast.LENGTH_LONG).show()
            // Manually call forwardOtp, which will handle the actual sending based on preferences
            // This bypasses the SMSReceiver/OTPService flow but uses the core forwarding logic
            OTPForwarder.forwardOtp(
                extractedOtp,
                testMessageBody,
                testSender,
                this,
                OTPForwarder.SourceType.TEST.name // Indicate it's from a test
            )
        } else {
            Toast.makeText(this, "No OTP extracted from test message with current settings.", Toast.LENGTH_LONG).show()
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

// Extension function for TextWatcher
fun EditText.addTextChangedListener(
    beforeTextChanged: ((CharSequence?, Int, Int, Int) -> Unit)? = null,
    onTextChanged: ((CharSequence?, Int, Int, Int) -> Unit)? = null,
    afterTextChanged: ((Editable?) -> Unit)? = null
) {
    this.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            beforeTextChanged?.invoke(s, start, count, after)
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            onTextChanged?.invoke(s, start, before, count)
        }

        override fun afterTextChanged(s: Editable?) {
            afterTextChanged?.invoke(s)
        }
    })
}