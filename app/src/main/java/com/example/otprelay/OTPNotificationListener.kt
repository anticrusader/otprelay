package com.example.otprelay

import android.app.Notification
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.Set // Explicit import to avoid ambiguity if needed

class OTPNotificationListener : NotificationListenerService() {

    private val TAG = Constants.LOG_TAG

    private lateinit var sharedPrefs: SharedPreferences
    // Using ConcurrentHashMap for thread-safety, as onNotificationPosted can be called from different threads
    // Key: content-based hash of notification for this listener's internal duplicate tracking
    // Value: Timestamp when it was processed by this listener
    private val processedNotifications = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OTPNotificationListener: onCreate called.")
        sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, MODE_PRIVATE)
        // Load previously processed notifications from preferences on creation
        loadProcessedNotifications()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "OTPNotificationListener: Listener connected.")
        // Ensure cache is loaded on connection, as the service might be reconnected after a crash or user action
        loadProcessedNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "OTPNotificationListener: Listener disconnected.")
        // Save state before disconnecting to persist processed notifications
        saveProcessedNotifications()
    }

    /**
     * Loads previously processed notification keys from SharedPreferences.
     * Cleans up old entries to prevent excessive memory usage.
     */
    private fun loadProcessedNotifications() {
        val saved = sharedPrefs.getStringSet(Constants.KEY_LAST_PROCESSED_NOTIFICATIONS, emptySet()) ?: emptySet()
        val now = System.currentTimeMillis()
        val cleanedCount = processedNotifications.size // Track count before clear

        processedNotifications.clear() // Clear existing map before loading fresh from prefs
        saved.forEach { entry ->
            val parts = entry.split("::")
            if (parts.size == 2) {
                val key = parts[0]
                val timestamp = parts[1].toLongOrNull() ?: 0L
                // Only load entries that are still within the duplicate prevention window
                if (now - timestamp < Constants.DUPLICATE_PREVENTION_WINDOW_MS) {
                    processedNotifications[key] = timestamp
                }
            }
        }
        Log.d(TAG, "OTPNotificationListener: Loaded ${processedNotifications.size} processed notifications. Cleared ${cleanedCount - processedNotifications.size} old entries during load.")
    }

    /**
     * Saves the current set of processed notification keys to SharedPreferences.
     * Only keeps the most recent entries up to a defined cache size and age.
     */
    private fun saveProcessedNotifications() {
        val now = System.currentTimeMillis()
        // Clean up old entries from the in-memory map before saving to preferences
        processedNotifications.entries.removeIf { now - it.value > Constants.DUPLICATE_PREVENTION_WINDOW_MS }

        // Convert map to a set of strings for SharedPreferences
        val toSave = processedNotifications.map { "${it.key}::${it.value}" }.toSet()

        sharedPrefs.edit().putStringSet(Constants.KEY_LAST_PROCESSED_NOTIFICATIONS, toSave).apply()
        Log.d(TAG, "OTPNotificationListener: Saved ${processedNotifications.size} processed notifications to SharedPreferences.")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val packageName = sbn.packageName

            // Ignore notifications from our own app to prevent feedback loop (e.g., debug notifications)
            if (packageName == applicationContext.packageName) {
                Log.d(TAG, "OTPNotificationListener: Skipping notification from self ($packageName).")
                return
            }

            val notification = sbn.notification
            val extras = notification.extras

            val postTime = sbn.postTime
            val currentTime = System.currentTimeMillis()

            // Skip if notification is too old to be relevant (e.g., more than SMS_MAX_AGE_FOR_PROCESSING_MS)
            // Use SMS_MAX_AGE_FOR_PROCESSING_MS for consistency with SMS processing.
            if (currentTime - postTime > Constants.SMS_MAX_AGE_FOR_PROCESSING_MS) {
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
            // Use sbn.key (unique per notification instance) plus a content hash for robustness.
            // sbn.key includes packageName, ID, and tag, making it unique for the posted notification.
            val notificationInstanceKey = "${sbn.key}_${fullMessage.hashCode()}"

            // Check if this specific notification content has been processed recently by this listener
            // This is the listener's internal cache for its own processing.
            val lastProcessedTimeForListener = processedNotifications[notificationInstanceKey] ?: 0L
            if (currentTime - lastProcessedTimeForListener < Constants.DUPLICATE_PREVENTION_WINDOW_MS) {
                Log.d(TAG, "OTPNotificationListener: Skipping already processed notification instance: $notificationInstanceKey")
                return
            }

            Log.d(TAG, "OTPNotificationListener: New notification from: $packageName")
            Log.d(TAG, "Title: '$title', Text: '$text', '$bigText', SubText: '$subText'")
            Log.d(TAG, "Full Message for OTP extraction: '$fullMessage'")

            // Skip if combined message is too short to contain an OTP
            if (fullMessage.length < Constants.DEFAULT_OTP_MIN_LENGTH + 5) { // OTP min length + some context
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
            val otp = OTPForwarder.extractOtpFromMessage(fullMessage, this.applicationContext) // Pass applicationContext for OTPForwarder

            if (otp != null) {
                // Determine the sender string for the global OTPForwarder duplicate check
                // Prioritize title if it seems like a sender name, otherwise use package name
                val rawSenderForForwarder = if (title.isNotBlank() && title.length < 50) title else packageName
                // Use OTPForwarder's normalizeSender to ensure consistent sender key for global deduplication
                val normalizedSenderForForwarder = OTPForwarder.normalizeSender(rawSenderForForwarder)
                val otpKeyForForwarder = "OTP_${otp}_${normalizedSenderForForwarder}_${currentTime / 5000L}" // Use rounded time for key

                if (OTPForwarder.wasRecentlyForwarded(otpKeyForForwarder)) { // Use the public method
                    Log.d(TAG, "OTPNotificationListener: OTP '$otp' from notification already forwarded recently by OTPForwarder (key: $otpKeyForForwarder). Skipping.")
                    OTPForwarder.showNotification(
                        this.applicationContext, // Use applicationContext for notification
                        "⚠️ Duplicate OTP Skipped (NL)",
                        "OTP '$otp' already sent from '$rawSenderForForwarder'",
                        Constants.SMS_DEBUG_CHANNEL_ID
                    )
                    return
                }

                Log.d(TAG, "OTPNotificationListener: OTP '$otp' found from notification. Forwarding...")

                // Mark this specific notification content as processed by this listener
                processedNotifications[notificationInstanceKey] = currentTime
                saveProcessedNotifications() // Save updated cache immediately after processing

                // Show debug notification
                OTPForwarder.showNotification(
                    this.applicationContext, // Use applicationContext for notification
                    "OTP Detected (Notification Listener): $otp",
                    "From: ${title.ifEmpty { packageName }}",
                    Constants.SMS_DEBUG_CHANNEL_ID
                )

                // Forward the OTP using the centralized OTPForwarder
                // Use title as sender if available and meaningful, else package name or a simplified version
                val senderToForward = if (title.isNotBlank() && title.length < 50) title else packageName
                OTPForwarder.forwardOtpViaMake(
                    otp,
                    fullMessage,
                    senderToForward, // Pass the more descriptive sender to the payload
                    this.applicationContext // Use applicationContext for forwarding
                )
            } else {
                Log.d(TAG, "OTPNotificationListener: No OTP found in notification from $packageName. Full message: '$fullMessage'")
                OTPForwarder.showNotification(
                    this.applicationContext, // Use applicationContext for notification
                    "No OTP Found (NL)",
                    "Notification from ${title.ifEmpty { packageName }}: '$fullMessage'",
                    Constants.SMS_DEBUG_CHANNEL_ID,
                    priority = NotificationCompat.PRIORITY_LOW
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "OTPNotificationListener: Error processing notification: ${e.message}", e)
            OTPForwarder.showNotification(
                this.applicationContext, // Use applicationContext for notification
                "❌ Notification Listener Error",
                "Failed to process notification: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
        }
    }

    /**
     * Heuristic to determine if a notification's content looks like an SMS message containing an OTP.
     * This is a fallback if the package name isn't in our known SMS apps list.
     *
     * @param message The combined message content of the notification.
     * @param title The title of the notification.
     * @return True if the content likely contains an OTP, false otherwise.
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
            "bank", "alert", "service", "info", "payment", "transaction"
        )

        // Check for presence of digits matching a broad OTP pattern
        val hasOtpNumber = message.contains(Regex("\\b\\d{${Constants.DEFAULT_OTP_MIN_LENGTH},${Constants.DEFAULT_OTP_MAX_LENGTH}}\\b")) // Use configurable range for broad check

        val hasOtpKeyword = otpKeywords.any { lowerMessage.contains(it) }
        val hasSmsContextKeyword = smsContextKeywords.any { lowerMessage.contains(it) || lowerTitle.contains(it) }

        // A notification is likely an SMS OTP if it meets these criteria:
        // 1. It contains an OTP keyword AND a number within the expected length range.
        // 2. It contains a "SMS context" keyword (like "message", "bank") AND an OTP keyword AND a number.
        // 3. The message is not excessively long (typical for OTP SMS, less likely for general app notifications).
        return (hasOtpKeyword && hasOtpNumber) ||
                (hasSmsContextKeyword && hasOtpKeyword && hasOtpNumber) ||
                (hasOtpKeyword && hasOtpNumber && message.length < 250) // Max 250 chars for typical SMS
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not strictly needed for this app's core functionality, but useful for debugging.
        Log.d(TAG, "OTPNotificationListener: Notification removed: ${sbn.packageName} - ${sbn.notification.extras.getString(Notification.EXTRA_TITLE)}")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OTPNotificationListener: onDestroy called. Saving processed notifications.")
        saveProcessedNotifications() // Ensure state is saved on service destruction
    }
}