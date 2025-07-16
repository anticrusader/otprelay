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
     * This helps prevent reprocessing notifications that were already handled before service restarts.
     */
    private fun loadProcessedNotifications() {
        val storedKeys = sharedPrefs.getStringSet(Constants.KEY_LAST_PROCESSED_NOTIFICATIONS, null)
        storedKeys?.forEach { key ->
            // Re-add to cache with a dummy timestamp or current time, as the exact original timestamp isn't critical for prevention
            // and we just need presence. However, to match the original cache logic, a more robust persistence would save timestamps.
            // For simplicity here, we assume if it was processed, it should still be considered processed for a grace period.
            // A more complex solution might store a Map<String, Long> as a JSON string or in a database.
            // For now, only add if the timestamp is recent (e.g., within the last hour to prevent cache bloat)
            val oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000
            val timestamp = sharedPrefs.getLong("${key}_timestamp", 0L) // Assuming timestamp was also saved
            if (timestamp > oneHourAgo) {
                processedNotifications[key] = timestamp
            }
        }
        Log.d(TAG, "OTPNotificationListener: Loaded ${processedNotifications.size} processed notifications from preferences.")
        // Clean up old entries immediately on load to prevent cache bloat
        cleanupProcessedNotifications()
    }

    /**
     * Saves the currently processed notification keys to SharedPreferences.
     */
    private fun saveProcessedNotifications() {
        // First, clean up old entries before saving
        cleanupProcessedNotifications()
        val editor = sharedPrefs.edit()
        val keysToSave = mutableSetOf<String>()
        processedNotifications.forEach { (key, timestamp) ->
            keysToSave.add(key)
            editor.putLong("${key}_timestamp", timestamp) // Save timestamp alongside key
        }
        editor.putStringSet(Constants.KEY_LAST_PROCESSED_NOTIFICATIONS, keysToSave)
        editor.apply()
        Log.d(TAG, "OTPNotificationListener: Saved ${processedNotifications.size} processed notifications to preferences.")
    }

    /**
     * Removes old entries from the processedNotifications cache.
     * Keeps entries for a certain duration (e.g., last 30 minutes) to prevent re-processing.
     */
    private fun cleanupProcessedNotifications() {
        val thirtyMinutesAgo = System.currentTimeMillis() - (30 * 60 * 1000) // 30 minutes
        val iterator = processedNotifications.entries.iterator()
        var cleanedCount = 0
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value < thirtyMinutesAgo) {
                iterator.remove()
                cleanedCount++
            }
        }
        if (cleanedCount > 0) {
            Log.d(TAG, "OTPNotificationListener: Cleaned up $cleanedCount old notifications from cache.")
        }
    }


    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val notification = sbn.notification
        val extras = notification.extras

        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val message = text ?: bigText ?: "" // Prioritize text, then bigText

        Log.d(TAG, "OTPNotificationListener: Notification Posted - Package: $packageName, Title: $title, Text: $text, BigText: $bigText")

        // Ignore notifications from excluded packages (e.g., our own app, system UIs)
        if (packageName == applicationContext.packageName || packageName == "android") {
            Log.d(TAG, "OTPNotificationListener: Ignoring self or system notification.")
            return
        }

        // Construct a unique key for this notification to check for duplicates in this session
        // This key should capture enough uniqueness without being too sensitive to minor changes
        val notificationContentHash = (title + message + packageName).hashCode().toString()
        val currentTimestamp = System.currentTimeMillis()

        // Check against internal cache first (for current session duplicates)
        if (processedNotifications.containsKey(notificationContentHash) &&
            (currentTimestamp - (processedNotifications[notificationContentHash] ?: 0L) < (15 * 1000))) { // Deduplicate within 15 seconds
            Log.d(TAG, "OTPNotificationListener: Notification already processed recently (internal cache). Skipping.")
            return
        }

        // Check if this notification is likely an SMS OTP
        if (isOtpNotification(packageName, title, message)) {
            Log.d(TAG, "OTPNotificationListener: Potentially an OTP notification identified.")

            // Attempt to extract OTP using the centralized OTPForwarder
            // Pass context and configured lengths (using default patterns)
            val sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val otpMinLength = sharedPrefs.getInt(Constants.KEY_OTP_MIN_LENGTH, Constants.DEFAULT_OTP_MIN_LENGTH)
            val otpMaxLength = sharedPrefs.getInt(Constants.KEY_OTP_MAX_LENGTH, Constants.DEFAULT_OTP_MAX_LENGTH)

            val otp = OTPForwarder.extractOtpFromMessage(message, applicationContext, null, otpMinLength, otpMaxLength)

            if (otp != null) {
                Log.d(TAG, "OTPNotificationListener: OTP '$otp' extracted from notification. Forwarding...")
                // Add to processed cache to prevent re-processing
                processedNotifications[notificationContentHash] = currentTimestamp
                saveProcessedNotifications() // Persist the updated cache

                // Extract the actual sender from the notification instead of using package name
                val actualSender = extractSenderFromNotification(title, message, packageName)

                OTPForwarder.forwardOtp(
                    otp,
                    "Notification: $title - $message",
                    actualSender, // Use extracted sender instead of package name
                    applicationContext,
                    OTPForwarder.SourceType.NOTIFICATION.name
                )
            } else {
                Log.d(TAG, "OTPNotificationListener: No OTP found in notification: $message")
                OTPForwarder.showNotification(
                    applicationContext,
                    "No OTP Found in Notification",
                    "From $packageName: '$title' - '$message'",
                    Constants.SMS_DEBUG_CHANNEL_ID,
                    priority = NotificationCompat.PRIORITY_LOW
                )
            }
        }
    }

    /**
     * Determines if a notification is likely an SMS containing an OTP.
     * This uses a combination of package name checks, keywords, and regex patterns.
     *
     * @param packageName The package name of the app that posted the notification.
     * @param title The title of the notification.
     * @param message The main text content of the notification.
     * @return True if the notification is likely an SMS OTP, false otherwise.
     */
    private fun isOtpNotification(packageName: String, title: String?, message: String?): Boolean {
        if (message.isNullOrBlank()) {
            return false
        }

        val lowerMessage = message.lowercase(Locale.getDefault())
        val lowerTitle = title?.lowercase(Locale.getDefault()) ?: ""

        // Load configurable keywords and OTP lengths (unified keyword system)
        val sharedPrefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val otpKeywords = sharedPrefs.getStringSet(Constants.KEY_SMS_KEYWORDS, Constants.DEFAULT_SMS_KEYWORDS)?.toSet() ?: emptySet()
        val smsContextKeywords = sharedPrefs.getStringSet(Constants.KEY_SMS_CONTEXT_KEYWORDS, Constants.DEFAULT_SMS_CONTEXT_KEYWORDS)?.toSet() ?: emptySet()
        val otpMinLength = sharedPrefs.getInt(Constants.KEY_OTP_MIN_LENGTH, Constants.DEFAULT_OTP_MIN_LENGTH)
        val otpMaxLength = sharedPrefs.getInt(Constants.KEY_OTP_MAX_LENGTH, Constants.DEFAULT_OTP_MAX_LENGTH)


        // 1. Check if the notification comes from a known SMS app
        val isSmsApp = Constants.SMS_PACKAGES.contains(packageName)
        if (!isSmsApp) {
            Log.d(TAG, "OTPNotificationListener: Not an SMS app ($packageName).")
            return false
        }

        // 2. Check for OTP-like numbers within the message using a general pattern
        val otpNumberRegex = Regex("\\b\\d{${otpMinLength},${otpMaxLength}}\\b")
        val hasOtpNumber = otpNumberRegex.containsMatchIn(lowerMessage)

        // Check if message contains any configured keywords (case-insensitive)
        val hasOtpKeyword = otpKeywords.any { keyword -> 
            lowerMessage.contains(keyword.lowercase(Locale.getDefault())) || 
            lowerTitle.contains(keyword.lowercase(Locale.getDefault()))
        }
        val hasSmsContextKeyword = smsContextKeywords.any { keyword -> 
            lowerMessage.contains(keyword.lowercase(Locale.getDefault())) || 
            lowerTitle.contains(keyword.lowercase(Locale.getDefault()))
        }

        // Only process notifications that contain configured keywords
        if (!hasOtpKeyword) {
            Log.d(TAG, "OTPNotificationListener: Notification does not contain any configured keywords. Skipping.")
            Log.d(TAG, "OTPNotificationListener: Configured keywords: $otpKeywords")
            Log.d(TAG, "OTPNotificationListener: Message content: $message")
            return false
        }

        // A notification is likely an SMS OTP if it meets these criteria:
        // 1. It contains an OTP keyword AND a number within the expected length range.
        // 2. The message is not excessively long (typical for OTP SMS).
        return hasOtpKeyword && hasOtpNumber && message.length < 250 // Max 250 chars for typical SMS
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Not strictly needed for this app's core functionality, but useful for debugging.
        Log.d(TAG, "OTPNotificationListener: Notification removed: ${sbn.packageName} - ${sbn.notification.extras.getString(Notification.EXTRA_TITLE)}")
    }

    /**
     * Extracts the actual sender from SMS notification instead of using the package name.
     * For SMS notifications, the sender is usually in the title or can be extracted from the message.
     */
    private fun extractSenderFromNotification(title: String?, message: String?, packageName: String): String {
        // Try to extract sender from title first (most common case)
        if (!title.isNullOrBlank()) {
            // Common patterns for SMS notification titles:
            // "John Doe", "+1234567890", "Bank Alert", etc.
            
            // If title looks like a phone number, use it
            if (title.matches(Regex("^[+]?[0-9\\s\\-()]+$"))) {
                return title.trim()
            }
            
            // If title is not a generic SMS app name, use it as sender
            val genericTitles = setOf("message", "messages", "sms", "text message", "new message")
            if (!genericTitles.contains(title.lowercase().trim())) {
                return title.trim()
            }
        }
        
        // Try to extract sender from message content
        if (!message.isNullOrBlank()) {
            // Look for patterns like "From: +1234567890" or similar
            val fromPattern = Regex("(?:from|sender)\\s*:?\\s*([+]?[0-9\\s\\-()]+)", RegexOption.IGNORE_CASE)
            val fromMatch = fromPattern.find(message)
            if (fromMatch != null) {
                return fromMatch.groupValues[1].trim()
            }
            
            // Look for phone numbers at the beginning of the message
            val phonePattern = Regex("^([+]?[0-9\\s\\-()]{7,15})\\s*[:-]")
            val phoneMatch = phonePattern.find(message)
            if (phoneMatch != null) {
                return phoneMatch.groupValues[1].trim()
            }
        }
        
        // If we can't extract a specific sender, return a more user-friendly name
        return when (packageName) {
            "com.google.android.apps.messaging" -> "Messages (SMS)"
            "com.android.mms" -> "SMS"
            "com.samsung.android.messaging" -> "Samsung Messages"
            else -> "SMS App"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OTPNotificationListener: onDestroy called. Saving processed notifications.")
        saveProcessedNotifications() // Ensure state is saved on service destruction
    }
}