package com.example.otprelay

object Constants {
    const val LOG_TAG = "OTPForwarderApp"

    // SharedPreferences
    const val PREFS_NAME = "OTPRelayPrefs"
    const val KEY_LAST_PROCESSED_SMS_ID = "last_processed_sms_id"
    const val KEY_LAST_PROCESSED_NOTIFICATIONS = "last_processed_notifications_set" // Keep for future use if needed
    const val KEY_IS_FORWARDING_ENABLED = "is_forwarding_enabled"

    // Keys for Forwarding Method Preference
    const val KEY_FORWARDING_METHOD = "forwarding_method"
    const val FORWARDING_METHOD_WEBHOOK = "webhook"
    const val FORWARDING_METHOD_DIRECT_EMAIL = "direct_email"

    // Keys for Webhook URL
    const val KEY_WEBHOOK_URL = "webhook_url"

    // Keys for Direct Email (SMTP) Settings
    const val KEY_SMTP_HOST = "smtp_host"
    const val KEY_SMTP_PORT = "smtp_port"
    const val KEY_SMTP_USERNAME = "smtp_username"
    const val KEY_SMTP_PASSWORD = "smtp_password" // Consider EncryptedSharedPreferences for production
    const val KEY_RECIPIENT_EMAIL = "recipient_email"
    const val KEY_SENDER_EMAIL = "sender_email" // The email address to send from

    // Keys for configurable OTP Length
    const val KEY_OTP_MIN_LENGTH = "otp_min_length"
    const val KEY_OTP_MAX_LENGTH = "otp_max_length"
    const val DEFAULT_OTP_MIN_LENGTH = 4 // Default value
    const val DEFAULT_OTP_MAX_LENGTH = 10 // Default value

    // Notification Channels
    const val FOREGROUND_SERVICE_CHANNEL_ID = "otp_relay_service_channel"
    const val SMS_DEBUG_CHANNEL_ID = "otp_relay_debug_channel"
    const val FOREGROUND_SERVICE_NOTIFICATION_ID = 101

    // Timeouts and Intervals for SMS processing
    // Polling interval for checking SMS content provider periodically
    const val SMS_POLLING_INTERVAL_MS = 15 * 1000L // Poll every 15 seconds (was 2 seconds, too frequent)

    // Window for OTPForwarder's global duplicate checks (cross-source: SMS, Notification).
    // An OTP value forwarded within this window will be skipped.
    const val DUPLICATE_PREVENTION_WINDOW_MS = 5 * 60 * 1000L // 5 minutes (Reverted to a longer window for global OTP-level duplicates)

    // Maximum age of an SMS message (in milliseconds) from its 'date' timestamp
    // for it to be considered for processing by the polling or observer mechanisms.
    // This prevents processing very old SMS messages if lastProcessedSmsId resets or is -1.
    const val SMS_MAX_AGE_FOR_PROCESSING_MS = 60 * 1000L // 1 minute (e.g., only process SMS received in last 1 minute)

    // Make.com Webhook URL (Placeholder - user will set this in UI)
    // IMPORTANT: Replace with your actual Make.com webhook URL
    const val MAKE_WEBHOOK_URL = "YOUR_MAKE_WEBHOOK_URL_HERE"

    // Regex patterns for OTP extraction
    // These patterns will have {LENGTH_PLACEHOLDER} replaced with the actual length range.
    // Order matters: More specific patterns should come before more generic ones.
    val OTP_REGEX_PATTERNS_TO_GENERATE = listOf(
        // Strongest patterns: look for explicit OTP keywords right before or after the digits
        "\\b(?:OTP|Code|Pin|Verification|Verify|Passcode|Login|Access)\\s*[:is]*\\s*(\\d{LENGTH_PLACEHOLDER})\\b", // e.g., "OTP: 123456", "Code is 123456"
        "\\b(\\d{LENGTH_PLACEHOLDER})\\s*(?:is your|is the|is your verification|is your login|otp|code|pin|verif|authenticate)\\b", // e.g., "123456 is your OTP"
        "Your OTP is\\s*(\\d{LENGTH_PLACEHOLDER})\\b",
        "Verification Code:\\s*(\\d{LENGTH_PLACEHOLDER})\\b",
        "Pin code:\\s*(\\d{LENGTH_PLACEHOLDER})\\b",
        "Use (\\d{LENGTH_PLACEHOLDER}) to verify",
        "Code:\\s*(\\d{LENGTH_PLACEHOLDER})",
        "PIN:\\s*(\\d{LENGTH_PLACEHOLDER})",
        "OTP\\s+(\\d{LENGTH_PLACEHOLDER})",
        "is (\\d{LENGTH_PLACEHOLDER})", // More generic, but requires very careful handling in `extractOtpFromMessage`

        // Generic patterns (use with caution, as they are prone to false positives like phone numbers)
        // This is the most generic. If this pattern often catches phone numbers, you might need to remove it
        // or add stronger context checks in extractOtpFromMessage.
        // For now, rely on the `isPhoneNumberLikely` check in `extractOtpFromMessage`.
        "\\b(\\d{LENGTH_PLACEHOLDER})\\b" // Standalone digits with word boundaries
    )

    // Fixed-length specific regex patterns (for very specific known services)
    val OTP_FIXED_LENGTH_REGEX_PATTERNS = listOf(
        Regex("(\\d{6}) is your Google verification code", RegexOption.IGNORE_CASE),
        Regex("Your Amazon OTP is (\\d{6})", RegexOption.IGNORE_CASE),
        Regex("Your PayPal verification code is (\\d{6})", RegexOption.IGNORE_CASE),
        Regex("(\\d{4}) is your code for WhatsApp", RegexOption.IGNORE_CASE),
        Regex("(\\d{6}) is your code for JazzCash", RegexOption.IGNORE_CASE),
        Regex("(\\d{6}) is your code for EasyPaisa", RegexOption.IGNORE_CASE),
        Regex("Adnan your otp is (\\d{7})", RegexOption.IGNORE_CASE) // Added based on your screenshot
    )

    // Common SMS app package names (for NotificationListenerService)
    val SMS_PACKAGES = listOf(
        "com.google.android.apps.messaging", // Google Messages
        "com.android.mms", // AOSP MMS app
        "com.samsung.android.messaging", // Samsung Messages
        "com.huawei.android.mms", // Huawei Messages
        "com.xiaomi.xmsf", // Xiaomi Messages (often part of MIUI system apps)
        "com.android.messaging" // Another common AOSP or OEM messaging app
    )

    // For real-time OTP updates in UI
    const val ACTION_OTP_FORWARDED = "com.example.otprelay.ACTION_OTP_FORWARDED"
    const val EXTRA_FORWARDED_OTP = "com.example.otprelay.EXTRA_FORWARDED_OTP"
    const val EXTRA_TIMESTAMP = "com.example.otprelay.EXTRA_TIMESTAMP"
    const val EXTRA_SENDER = "com.example.otprelay.EXTRA_SENDER"

    // This key is for displaying the last sent OTP in the UI, not a setting.
    // We'll update the TextView directly rather than persisting this specific value in preferences.
    const val KEY_LAST_SENT_OTP = "last_sent_otp"
}