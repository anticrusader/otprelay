package com.example.otprelay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.regex.Pattern

object OTPForwarder {
    private val TAG = Constants.LOG_TAG
    private val MAKE_WEBHOOK_URL = Constants.MAKE_WEBHOOK_URL

    // Using a single-thread executor to process forwarding requests sequentially
    private val executorService: ExecutorService = Executors.newSingleThreadExecutor()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // Centralized duplicate prevention map for recently forwarded OTPs
    // Using ConcurrentHashMap for thread-safety as it might be accessed from different threads (Service, NotificationListener)
    private val recentlyForwardedOtps = ConcurrentHashMap<String, Long>()

    // Enhanced OTP patterns - ordered by specificity (more specific first)
    private val otpPatterns = arrayOf(
        // 1. Common explicit OTP/Code patterns (e.g., "OTP is 123456", "Code: 12345")
        Pattern.compile("(?:OTP|otp|Code|code|PIN|pin)\\s*(?:is|:)?\\s*(\\d{4,10})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\d{4,10})\\s*(?:is your|is the)\\s*(?:OTP|otp|code|Code|PIN|pin)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:Your|your)\\s*(?:OTP|otp|code|Code|PIN|pin)\\s*(?:is|:)?\\s*(\\d{4,10})\\b", Pattern.CASE_INSENSITIVE),

        // 2. Verification/Authentication patterns (e.g., "verification code 123456", "authenticate with 12345")
        Pattern.compile("(?:verification|verify|authentication|confirm)\\s*(?:code|Code)?\\s*(?:is|:)?\\s*(\\d{4,10})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(?:use|enter|input)\\s*(\\d{4,10})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\d{4,10})\\s*(?:for|to)\\s*(?:verify|authenticate|confirm)", Pattern.CASE_INSENSITIVE),

        // 3. Generic "code" patterns (e.g., "Your code is 12345")
        Pattern.compile("(?:code|Code)\\s*:?\\s*(\\d{4,10})\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("(\\d{4,10})\\s*is your\\s*\\w+\\s*code", Pattern.CASE_INSENSITIVE),

        // 4. Loose patterns - any 4-10 digit number with surrounding context
        // This pattern is broad, so the `isLikelyOtpMessage` and `isLikelyOtp` filters are crucial.
        Pattern.compile("\\b(\\d{4,10})\\b")
    )

    /**
     * Checks if a given OTP key was recently forwarded within the DUPLICATE_PREVENTION_WINDOW.
     * Also cleans up old entries in the cache.
     * @param otpKey The unique key for the OTP (e.g., "OTP_123456_SENDER").
     * @return True if the OTP was recently forwarded, false otherwise.
     */
    fun wasRecentlyForwarded(otpKey: String): Boolean {
        val lastForwarded = recentlyForwardedOtps[otpKey] ?: 0
        val now = System.currentTimeMillis()

        // Clean up old entries (older than 10 minutes) to prevent memory growth
        recentlyForwardedOtps.entries.removeIf {
            now - it.value > Constants.DUPLICATE_PREVENTION_WINDOW_MS * 2 // 10 minutes
        }

        return (now - lastForwarded) < Constants.DUPLICATE_PREVENTION_WINDOW_MS
    }

    /**
     * Extracts a potential OTP from the given message string.
     * Iterates through defined regex patterns and applies additional filtering.
     * @param message The full SMS or notification text.
     * @return The extracted OTP string, or null if no valid OTP is found.
     */
    fun extractOtpFromMessage(message: String): String? {
        Log.d(TAG, "Attempting to extract OTP from: '$message'")

        for ((index, pattern) in otpPatterns.withIndex()) {
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                val otp = matcher.group(1)
                if (otp != null && otp.length >= 4 && otp.length <= 10) { // OTPs are typically 4-10 digits
                    Log.d(TAG, "Potential OTP found with pattern $index: $otp")

                    // Apply additional checks for the broadest pattern
                    if (index == otpPatterns.size - 1) {
                        if (!isLikelyOtpMessage(message)) {
                            Log.d(TAG, "Skipping broad match, message not likely an OTP message.")
                            continue // Not a likely OTP message, try next pattern or return null
                        }
                    }

                    // Final check to filter out non-OTP numbers (e.g., phone numbers, amounts)
                    if (isLikelyOtp(otp, message)) {
                        Log.d(TAG, "OTP '$otp' passed final validation.")
                        return otp
                    } else {
                        Log.d(TAG, "OTP '$otp' failed final validation.")
                    }
                }
            }
        }
        Log.d(TAG, "No valid OTP found in message.")
        return null
    }

    /**
     * Checks if the message content contains keywords typically associated with OTPs.
     * This helps filter out random numbers caught by broad regex patterns.
     */
    private fun isLikelyOtpMessage(message: String): Boolean {
        val lowerMessage = message.lowercase(Locale.getDefault())
        val otpKeywords = listOf(
            "otp", "code", "pin", "verification", "verify", "authenticate",
            "confirm", "login", "security", "use", "enter", "passcode", "one-time"
        )
        return otpKeywords.any { lowerMessage.contains(it) }
    }

    /**
     * Performs additional checks on the extracted number to determine if it's truly an OTP.
     * Filters out common non-OTP numbers like phone numbers or financial amounts.
     */
    private fun isLikelyOtp(otp: String, message: String): Boolean {
        val lowerMessage = message.lowercase(Locale.getDefault())

        // Rule 1: Filter out common phone number lengths (adjust as needed for your region)
        // Assuming OTPs are rarely 10 or 11 digits if they are phone numbers.
        // This is a heuristic, not foolproof.
        if (otp.length == 10 || otp.length == 11) {
            // If it's a 10/11 digit number, check if it's explicitly stated as a phone number
            val phoneNumberKeywords = listOf("call", "contact", "phone", "tel", "number")
            if (phoneNumberKeywords.any { lowerMessage.contains(it) }) {
                return false
            }
        }

        // Rule 2: Filter out financial amounts unless an explicit OTP keyword is present.
        val financeKeywords = listOf("amount", "balance", "credit", "debit", "payment", "rs", "inr", "$", "usd", "eur")
        val hasFinanceKeyword = financeKeywords.any { lowerMessage.contains(it) }
        val hasOtpKeyword = listOf("otp", "code", "pin", "verification").any { lowerMessage.contains(it) }

        if (hasFinanceKeyword && !hasOtpKeyword) {
            Log.d(TAG, "OTP '$otp' rejected: Contains finance keyword without OTP keyword.")
            return false
        }

        // Rule 3: Avoid numbers that are part of dates or times if no OTP keyword
        val dateKeywords = listOf("date", "time", "expires", "valid till")
        if (dateKeywords.any { lowerMessage.contains(it) } && !hasOtpKeyword) {
            Log.d(TAG, "OTP '$otp' rejected: Contains date/time keyword without OTP keyword.")
            return false
        }

        // Add more heuristics as needed based on observed non-OTP messages
        return true
    }

    /**
     * Forwards the extracted OTP, original message, and sender to the Make.com webhook.
     * This operation is performed on a background thread.
     * @param otp The extracted OTP.
     * @param originalMessage The full original SMS/notification message.
     * @param sender The sender of the SMS/notification.
     * @param context The application context.
     */
    fun forwardOtpViaMake(otp: String, originalMessage: String, sender: String, context: Context) {
        Log.d(TAG, "Attempting to forward OTP: '$otp' from '$sender'")

        // Create a unique key for this OTP to prevent immediate duplicates
        val otpKey = "OTP_${otp}_${sender.take(50).replace("[^a-zA-Z0-9]".toRegex(), "")}" // Sanitize sender for key

        // Perform the duplicate check here, before initiating the network request
        if (wasRecentlyForwarded(otpKey)) {
            Log.d(TAG, "DUPLICATE DETECTED: OTP '$otp' from '$sender' was already forwarded recently. Skipping.")
            showNotification(context, "⚠️ Duplicate OTP Skipped", "OTP '$otp' already sent from '$sender'", Constants.OTP_RESULT_CHANNEL_ID)
            return
        }

        // Mark as forwarded IMMEDIATELY to prevent race conditions from other detection methods
        recentlyForwardedOtps[otpKey] = System.currentTimeMillis()

        executorService.execute {
            try {
                val currentTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

                val payload = JSONObject().apply {
                    put("otp", otp)
                    put("original_message", originalMessage)
                    put("sender", sender)
                    put("timestamp", currentTime)
                    put("device_model", Build.MODEL)
                    put("device_brand", Build.BRAND)
                    put("android_version", Build.VERSION.RELEASE)
                }

                Log.d(TAG, "Sending payload to Make.com: $payload")

                val body = RequestBody.create(
                    "application/json; charset=utf-8".toMediaType(),
                    payload.toString()
                )

                val request = Request.Builder()
                    .url(MAKE_WEBHOOK_URL)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("User-Agent", "OTP-Forwarder-Android/${BuildConfig.VERSION_NAME ?: "1.0"}") // Use BuildConfig.VERSION_NAME
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "Make.com Response Code: ${response.code}")

                    val notificationTitle: String
                    val notificationContent: String

                    if (response.isSuccessful) {
                        notificationTitle = "✅ OTP Forwarded: $otp"
                        notificationContent = "From: $sender"
                        // Update last sent OTP only on success
                        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putString(Constants.KEY_LAST_SENT_OTP, otp).apply()

                        // Notify MainActivity about the successful forward
                        val intent = Intent(Constants.ACTION_OTP_FORWARDED).apply {
                            putExtra(Constants.EXTRA_FORWARDED_OTP, otp)
                        }
                        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)

                    } else {
                        notificationTitle = "❌ Forward Failed: $otp"
                        notificationContent = "From: $sender (Code: ${response.code})"
                        Log.e(TAG, "Failed to forward OTP. Response: ${response.body?.string()}")
                    }
                    showNotification(context, notificationTitle, notificationContent, Constants.OTP_RESULT_CHANNEL_ID)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding OTP to Make.com", e)
                showNotification(context, "❌ Error forwarding OTP", e.message ?: "Unknown error", Constants.OTP_RESULT_CHANNEL_ID)
            }
        }
    }

    /**
     * Centralized helper function to display notifications to the user.
     * @param context The application context.
     * @param title The title of the notification.
     * @param content The main text content of the notification.
     * @param channelId The ID of the notification channel to use.
     * @param notificationId An optional specific ID for the notification. If null, a unique ID based on time will be used.
     * @param priority The priority of the notification.
     */
    fun showNotification(
        context: Context,
        title: String,
        content: String,
        channelId: String,
        notificationId: Int? = null,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create notification channel if it doesn't exist (idempotent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = when (channelId) {
                Constants.FOREGROUND_SERVICE_CHANNEL_ID -> Constants.FOREGROUND_SERVICE_CHANNEL_NAME
                Constants.OTP_RESULT_CHANNEL_ID -> Constants.OTP_RESULT_CHANNEL_NAME
                Constants.SMS_DEBUG_CHANNEL_ID -> Constants.SMS_DEBUG_CHANNEL_NAME
                else -> "General Notifications"
            }
            val importance = when (channelId) {
                Constants.FOREGROUND_SERVICE_CHANNEL_ID -> NotificationManager.IMPORTANCE_LOW // Silent for ongoing service
                Constants.SMS_DEBUG_CHANNEL_ID -> NotificationManager.IMPORTANCE_LOW // Low for debug messages
                else -> NotificationManager.IMPORTANCE_DEFAULT // Default for results
            }
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                description = "$channelName for OTP Forwarder app."
                setShowBadge(false) // Don't show badge for foreground service or debug
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notificationBuilder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_email) // Generic icon
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content)) // Allow long text to expand
            .setPriority(priority)
            .setAutoCancel(true) // Dismiss when tapped

        // For foreground service notification, make it ongoing and silent
        if (channelId == Constants.FOREGROUND_SERVICE_CHANNEL_ID) {
            notificationBuilder.setOngoing(true).setSilent(true)
        }

        val finalNotificationId = notificationId ?: System.currentTimeMillis().toInt()
        notificationManager.notify(finalNotificationId, notificationBuilder.build())
    }
}
