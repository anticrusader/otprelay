package com.example.otprelay

object Constants {
    // Tag for logging
    const val LOG_TAG = "OTPForwarderApp"

    // Make.com Webhook URL (REPLACE WITH YOUR ACTUAL URL)
    // WARNING: Hardcoding URLs in client-side code can be a security risk.
    // For production apps, consider fetching this from a secure backend,
    // or using buildConfigField in build.gradle for less sensitive cases.
    const val MAKE_WEBHOOK_URL = "https://hook.eu2.make.com/bnooc4nm64eu13l89hcq9f25tjvztiam"

    // Notification Channel IDs
    const val FOREGROUND_SERVICE_CHANNEL_ID = "otp_service_channel"
    const val FOREGROUND_SERVICE_CHANNEL_NAME = "OTP Forwarding Service"
    const val OTP_RESULT_CHANNEL_ID = "otp_forwarding_results_channel"
    const val OTP_RESULT_CHANNEL_NAME = "OTP Forwarding Results"
    const val SMS_DEBUG_CHANNEL_ID = "sms_debug_channel"
    const val SMS_DEBUG_CHANNEL_NAME = "SMS Debug"

    // Notification IDs
    const val FOREGROUND_SERVICE_NOTIFICATION_ID = 1001

    // Shared Preferences
    const val PREFS_NAME = "OTPForwarderPrefs"
    const val KEY_AUTO_FORWARD_ENABLED = "auto_forward_enabled"
    const val KEY_LAST_SENT_OTP = "last_sent_otp"
    const val KEY_LAST_PROCESSED_SMS_ID = "last_processed_sms_id" // For SMS polling
    const val KEY_LAST_PROCESSED_NOTIFICATIONS = "last_processed_notifications" // For NotificationListener

    // Intent Actions for LocalBroadcastManager
    const val ACTION_OTP_FORWARDED = "com.example.otprelay.OTP_FORWARDED"
    const val EXTRA_FORWARDED_OTP = "forwarded_otp"

    // Duplicate prevention window (5 minutes)
    const val DUPLICATE_PREVENTION_WINDOW_MS = 300000L // 5 minutes

    // SMS Polling Interval (2 seconds)
    const val SMS_POLLING_INTERVAL_MS = 2000L

    // Notification Listener: Max age for notifications (1 minute)
    const val NOTIFICATION_MAX_AGE_MS = 60000L

    // Notification Listener: Max processed notifications to keep in cache
    const val MAX_PROCESSED_NOTIFICATIONS_CACHE_SIZE = 50

    // SMS Package Names for Notification Listener filtering
    val SMS_PACKAGES = listOf(
        "com.google.android.apps.messaging",    // Google Messages
        "com.android.mms",                      // Default SMS
        "com.samsung.android.messaging",        // Samsung Messages
        "com.xiaomi.mms",                       // MIUI SMS
        "com.android.messaging",                // AOSP Messages
        "com.huawei.android.mms",               // Huawei Messages
        "com.oppo.mms"                          // Oppo Messages
    )
}
