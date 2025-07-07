package com.example.otprelay

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.content.SharedPreferences
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import androidx.core.app.NotificationCompat

class OTPNotificationListener : NotificationListenerService() {

    private val TAG = Constants.LOG_TAG

    private lateinit var sharedPrefs: SharedPreferences
    // Using ConcurrentHashMap for thread-safety, as onNotificationPosted can be called from different threads
    private val processedNotifications = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        // Load previously processed notifications from preferences
        loadProcessedNotifications()
    }

    /**
     * Loads previously processed notification keys from SharedPreferences.
     * Cleans up old entries to prevent excessive memory usage.
     */
    private fun loadProcessedNotifications() {
        val saved = sharedPrefs.getStringSet(Constants.KEY_LAST_PROCESSED_NOTIFICATIONS, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        // Filter out entries older than the duplicate prevention window (e.g., 5 minutes)
        processedNotifications.clear()
        saved.forEach { entry ->
            val parts = entry.split("::")
            if (parts.size == 2) {
                val key = parts[0]
                val timestamp = parts[1].toLongOrNull() ?: 0L
                if (now - timestamp < Constants.DUPLICATE_PREVENTION_WINDOW_MS) {
                    processedNotifications[key] = timestamp
                }
            }
        }
        Log.d(TAG, "OTPNotificationListener: Loaded ${processedNotifications.size} processed notifications.")
    }

    /**
     * Saves the current set of processed notification keys to SharedPreferences.
     * Only keeps the most recent entries up to a defined cache size.
     */
    private fun saveProcessedNotifications() {
        val now = System.currentTimeMillis()
        // Clean up old entries before saving
        processedNotifications.entries.removeIf { now - it.value > Constants.DUPLICATE_PREVENTION_WINDOW_MS }

        // Convert map to a set of strings for SharedPreferences
        val toSave = processedNotifications.map { "${it.key}::${it.value}" }.toSet()

        sharedPrefs.edit().putStringSet(Constants.KEY_LAST_PROCESSED_NOTIFICATIONS, toSave).apply()
        Log.d(TAG, "OTPNotificationListener: Saved ${processedNotifications.size} processed notifications.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val notification = sbn.notification
            val extras = notification.extras

            val packageName = sbn.packageName
            val postTime = sbn.postTime
            val currentTime = System.currentTimeMillis()

            // Skip if notification is too old (e.g., more than 1 minute)
            if (currentTime - postTime > Constants.NOTIFICATION_MAX_AGE_MS) {
                Log.d(TAG, "OTPNotificationListener: Skipping old notification from $packageName (posted ${currentTime - postTime}ms ago).")
                return
            }

            // Extract notification content
            val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
            val text = extras.getString(Notification.EXTRA_TEXT) ?: ""
            val bigText = extras.getString(Notification.EXTRA_BIG_TEXT) ?: text
            val subText = extras.getString(Notification.EXTRA_SUB_TEXT) ?: ""

            // Combine all relevant text fields for comprehensive OTP extraction
            val fullMessage = "$title $text $bigText $subText".trim()

            // Create a content-based key for this notification to prevent reprocessing the same notification
            // Use a hash or a truncated string to keep the key length reasonable
            val contentKey = "${packageName}_${fullMessage.take(100).hashCode()}"

            // Check if this specific notification content has been processed recently by this listener
            val lastProcessedTime = processedNotifications[contentKey] ?: 0L
            if (currentTime - lastProcessedTime < Constants.DUPLICATE_PREVENTION_WINDOW_MS) {
                Log.d(TAG, "OTPNotificationListener: Skipping already processed notification content: $contentKey")
                return
            }

            Log.d(TAG, "OTPNotificationListener: New notification from: $packageName")
            Log.d(TAG, "Title: $title, Text: $text, BigText: $bigText, SubText: $subText")
            Log.d(TAG, "Full Message for OTP extraction: '$fullMessage'")

            // Skip if combined message is too short to contain an OTP
            if (fullMessage.length < 10) {
                Log.d(TAG, "OTPNotificationListener: Full message too short, skipping.")
                return
            }

            // Primary filter: Check if it's from a known SMS app package
            val isSmsAppPackage = Constants.SMS_PACKAGES.any { packageName.equals(it, ignoreCase = true) }

            // Secondary filter: Check if the notification content looks like an SMS message with OTP
            val looksLikeSmsContent = looksLikeSmsNotification(fullMessage, title)

            if (!isSmsAppPackage && !looksLikeSmsContent) {
                Log.d(TAG, "OTPNotificationListener: Not an SMS app package and content does not look like SMS/OTP, skipping.")
                return
            }

            // Attempt to extract OTP using the centralized OTPForwarder logic
            val otp = OTPForwarder.extractOtpFromMessage(fullMessage)

            if (otp != null) {
                // Now, use the OTPForwarder's global duplicate check for the *extracted OTP*
                val otpKeyForForwarder = "OTP_${otp}_${packageName.take(50).replace("[^a-zA-Z0-9]".toRegex(), "")}"
                if (OTPForwarder.wasRecentlyForwarded(otpKeyForForwarder)) {
                    Log.d(TAG, "OTPNotificationListener: OTP '$otp' from notification already forwarded recently by OTPForwarder. Skipping.")
                    OTPForwarder.showNotification(
                        this,
                        "⚠️ Duplicate OTP Skipped (NL)",
                        "OTP '$otp' already sent from '$packageName'",
                        Constants.SMS_DEBUG_CHANNEL_ID
                    )
                    return
                }

                Log.d(TAG, "OTPNotificationListener: OTP '$otp' found from notification. Forwarding...")

                // Mark this specific notification content as processed by this listener
                processedNotifications[contentKey] = currentTime
                saveProcessedNotifications() // Save updated cache

                // Show debug notification
                OTPForwarder.showNotification(
                    this,
                    "OTP Detected (Notification Listener): $otp",
                    "From: ${title.ifEmpty { packageName }}",
                    Constants.SMS_DEBUG_CHANNEL_ID
                )

                // Forward the OTP using the centralized OTPForwarder
                OTPForwarder.forwardOtpViaMake(
                    otp,
                    fullMessage,
                    title.ifEmpty { packageName }, // Use title as sender if available, else package name
                    this
                )
            } else {
                Log.d(TAG, "OTPNotificationListener: No OTP found in notification from $packageName.")
                OTPForwarder.showNotification(
                    this,
                    "No OTP Found (NL)",
                    "Notification from $packageName: '$fullMessage'",
                    Constants.SMS_DEBUG_CHANNEL_ID,
                    priority = NotificationCompat.PRIORITY_LOW
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "OTPNotificationListener: Error processing notification", e)
            OTPForwarder.showNotification(
                this,
                "❌ Notification Listener Error",
                "Failed to process notification: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
        }
    }

    /**
     * Heuristic to determine if a notification's content looks like an SMS message containing an OTP.
     * This is a fallback if the package name isn't in our known SMS apps list.
     */
    private fun looksLikeSmsNotification(message: String, title: String): Boolean {
        val lowerMessage = message.lowercase(Locale.getDefault())
        val lowerTitle = title.lowercase(Locale.getDefault())

        val otpKeywords = listOf(
            "otp", "code", "pin", "verification", "verify",
            "authenticate", "confirm", "passcode", "one-time", "one time",
            "security", "token", "validation", "access", "login"
        )

        // Keywords commonly found in SMS messages or sender names
        val smsContextKeywords = listOf(
            "message", "sms", "text", "msg", "from", "sent", "received",
            "bank", "alert", "service", "info", "payment"
        )

        val hasOtpNumber = message.contains(Regex("\\b\\d{4,10}\\b")) // At least a 4-10 digit number
        val hasOtpKeyword = otpKeywords.any { lowerMessage.contains(it) }
        val hasSmsContextKeyword = smsContextKeywords.any { lowerMessage.contains(it) || lowerTitle.contains(it) }

        // A notification is likely an SMS OTP if:
        // 1. It contains an OTP keyword AND a number
        // 2. It contains an SMS context keyword AND a number AND an OTP keyword (stronger signal)
        // 3. It contains an OTP keyword AND the message is relatively short (not a long app update)
        return (hasOtpKeyword && hasOtpNumber) ||
                (hasSmsContextKeyword && hasOtpNumber && hasOtpKeyword) ||
                (hasOtpKeyword && hasOtpNumber && message.length < 250) // Max 250 chars for typical SMS
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not strictly needed for this app's functionality
        Log.d(TAG, "OTPNotificationListener: Notification removed: ${sbn.packageName}")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "OTPNotificationListener: Listener connected.")
        loadProcessedNotifications() // Ensure cache is loaded on connection
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "OTPNotificationListener: Listener disconnected.")
        saveProcessedNotifications() // Save state before disconnecting
    }
}
