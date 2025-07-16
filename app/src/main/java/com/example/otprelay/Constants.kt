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
    const val DEFAULT_OTP_MIN_LENGTH = 4
    const val DEFAULT_OTP_MAX_LENGTH = 8 // Common max for OTPs

    // Keys for configurable keywords (unified for both SMS and notifications)
    const val KEY_SMS_KEYWORDS = "sms_keywords" // Used for both SMS and notification filtering
    const val KEY_SMS_CONTEXT_KEYWORDS = "sms_context_keywords" // For Notification Listener context detection

    // Default OTP regexes (can be overridden by user in settings)
    val DEFAULT_OTP_REGEXES = setOf(
        "\\b(\\d{4,8}) is your OTP\\b",
        "\\bOTP: (\\d{4,8})\\b",
        "\\bYour code is (\\d{4,8})\\b",
        "\\b(\\d{4,8}) is your verification code\\b",
        "\\b(\\d{6})\\b", // General 6-digit OTP
        "\\b(\\d{4})\\b", // General 4-digit OTP
        "\\bYour\\s*(?:OTP|verification code)\\s*is\\s*[:\\s]*(\\d{4,8})\\b",
        "\\b(\\d{4,8})\\s*is\\s*the\\s*code\\s*for",
        "Passcode:\\s*(\\d{4,8})",
        "Code:\\s*(\\d{4,8})",
        "is (\\d{6})", // "your OTP is (123456)"
        "(\\d{4}) for WhatsApp",
        "(\\d{6}) for JazzCash",
        "(\\d{6}) for EasyPaisa",
        "otp is (\\d{7})" // Example for "Adnan your otp is 1234567"
    )

    // Default keywords to identify OTP-like messages (can be overridden)
    val DEFAULT_SMS_KEYWORDS = setOf(
        "otp", "code", "pin", "verify", "verification", "login", "password",
        "one-time", "passcode", "access code", "security code", "jazzcash", "easypaisa", "bank","وزارة الإسكان","أدخل الرمز"
    )

    // Default keywords for notification context (if title/text contains these, more likely an SMS)
    val DEFAULT_SMS_CONTEXT_KEYWORDS = setOf(
        "message", "sms", "chat", "new message", "bank", "alert"
    )

    // Common SMS app package names (for NotificationListenerService) - kept here as they are less likely user-configurable
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

    // Notification Channel IDs
    const val FOREGROUND_SERVICE_CHANNEL_ID = "otp_relay_service_channel"
    const val FOREGROUND_SERVICE_CHANNEL_NAME = "OTP Relay Service"
    const val SMS_DEBUG_CHANNEL_ID = "otp_relay_debug_channel"
    const val SMS_DEBUG_CHANNEL_NAME = "OTP Relay Debug"

    // Notification IDs
    const val FOREGROUND_SERVICE_NOTIFICATION_ID = 1
}