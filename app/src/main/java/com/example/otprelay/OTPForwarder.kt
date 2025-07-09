package com.example.otprelay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern // Explicitly import Pattern

object OTPForwarder {

    private val TAG = Constants.LOG_TAG
    private val client = OkHttpClient()

    // In-memory cache to prevent duplicate forwarding of the same OTP
    // Key: "OTP_value_normalizedSender_timestamp_rounded" (e.g., "123456_minemobile_1701010100")
    // Value: Timestamp when it was last forwarded (System.currentTimeMillis())
    private val forwardedOtpCache = ConcurrentHashMap<String, Long>()

    // Enum to specify the source type of the OTP (SMS, Notification, Test, etc.)
    enum class SourceType {
        SMS,
        NOTIFICATION,
        TEST // For manual testing from UI
    }

    /**
     * Normalizes the sender string to create a consistent key for duplicate checking.
     * This is crucial for cross-source deduplication (SMS vs. Notification)
     * where the same logical sender might appear with different identifiers.
     *
     * IMPORTANT: You may need to CUSTOMIZE this function further based on the specific senders
     * you receive OTPs from. Observe the "Title" and "Package Name" from notifications,
     * and the "Sender" from SMS, and add rules here to map common logical senders
     * to a single consistent string.
     */
    internal fun normalizeSender(senderInput: String): String {
        val lowerCaseSender = senderInput.lowercase(Locale.getDefault()).trim()

        // *** CUSTOM MAPPING FOR KNOWN SERVICES (HIGHEST PRIORITY) ***
        // If "Mine" (or "My M.M.") and the specific phone number +923000503779
        // are known to be the SAME LOGICAL SENDER, map them to a common string.
        return when {
            // Map the specific phone number associated with "Mine" service
            lowerCaseSender.contains("923000503779") -> "mine_service_id"
            // Map the notification titles/package names for the same service
            lowerCaseSender.contains("mine") || lowerCaseSender.contains("my m.m.") -> "mine_service_id"
            // Add more specific service mappings here if needed.
            // Example: if "Easypaisa" appears as a title and from a specific number:
            // lowerCaseSender.contains("easypaisa") || lowerCaseSender.contains("specific_easypaisa_number_digits") -> "easypaisa_service_id"
            // Adding a specific rule for "Adnan" and "03105697413" based on your screenshot
            lowerCaseSender.contains("adnan") || lowerCaseSender.contains("03105697413") -> "adnan_contact_id"
            else -> lowerCaseSender.replace(Regex("[^a-z0-9]"), "") // Remove non-alphanumeric for general normalization
        }
    }


    /**
     * Extracts an OTP from a given message body using a list of regex patterns and configurable length.
     *
     * @param message The full message body (SMS or notification text).
     * @param context Application context for accessing SharedPreferences.
     * @param customRegexes A set of custom regex strings provided by the user. If empty, default regexes are used.
     * @param otpMinLength Minimum length for a valid OTP.
     * @param otpMaxLength Maximum length for a valid OTP.
     * @return The extracted OTP string, or null if no OTP is found.
     */
    fun extractOtpFromMessage(
        message: String,
        context: Context,
        customRegexes: Set<String>? = null, // Make this nullable and provide default
        otpMinLength: Int = Constants.DEFAULT_OTP_MIN_LENGTH,
        otpMaxLength: Int = Constants.DEFAULT_OTP_MAX_LENGTH
    ): String? {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        // Get OTP regexes from preferences, fall back to customRegexes parameter, then default constants
        val otpRegexes = customRegexes ?: prefs.getStringSet(Constants.KEY_CUSTOM_OTP_REGEXES, null)
        val regexPatternsToUse = if (!otpRegexes.isNullOrEmpty()) {
            otpRegexes.map { Pattern.compile(it, Pattern.CASE_INSENSITIVE) }
        } else {
            // Use default regexes from Constants if no custom ones are provided or configured
            Constants.DEFAULT_OTP_REGEXES.map { Pattern.compile(it, Pattern.CASE_INSENSITIVE) }
        }

        // Create a general regex for numbers within the configurable length range
        val generalOtpPattern = Pattern.compile("\\b(\\d{${otpMinLength},${otpMaxLength}})\\b")

        for (pattern in regexPatternsToUse) {
            val matcher = pattern.matcher(message)
            if (matcher.find()) {
                // Ensure the found group actually matches an OTP-like number based on length
                val potentialOtp = matcher.group(1)
                if (potentialOtp != null && potentialOtp.length >= otpMinLength && potentialOtp.length <= otpMaxLength) {
                    Log.d(TAG, "OTP extracted by specific regex: ${pattern.pattern()}")
                    return potentialOtp
                }
            }
        }

        // If no specific regex matches, try the general number pattern
        val generalMatcher = generalOtpPattern.matcher(message)
        if (generalMatcher.find()) {
            val potentialOtp = generalMatcher.group(1)
            if (potentialOtp != null && potentialOtp.length >= otpMinLength && potentialOtp.length <= otpMaxLength) {
                Log.d(TAG, "OTP extracted by general length regex.")
                return potentialOtp
            }
        }

        return null
    }

    /**
     * Centralized function to forward the extracted OTP. Handles deduplication and selected forwarding method.
     *
     * @param otp The extracted OTP.
     * @param messageBody The original message body.
     * @param sender The sender of the message.
     * @param context Application context.
     * @param sourceType The source type (SMS, NOTIFICATION, TEST).
     */
    fun forwardOtp(otp: String, messageBody: String, sender: String, context: Context, sourceType: String) {
        val normalizedSender = normalizeSender(sender)
        // Create a unique key for deduplication. Round timestamp to prevent issues with slight time differences.
        val dedupeKey = "${otp}_${normalizedSender}_${System.currentTimeMillis() / 10000}" // Group by 10-second intervals

        // Check cache to prevent duplicate forwarding
        if (forwardedOtpCache.containsKey(dedupeKey)) {
            Log.d(TAG, "OTP '$otp' from '$sender' (normalized: $normalizedSender) already forwarded recently. Skipping.")
            showNotification(context, "Duplicate OTP Skipped", "OTP from $sender: $otp", Constants.SMS_DEBUG_CHANNEL_ID, NotificationCompat.PRIORITY_LOW)
            return
        }

        // Add to cache
        forwardedOtpCache[dedupeKey] = System.currentTimeMillis()

        Log.d(TAG, "Attempting to forward OTP: $otp (Source: $sourceType)")

        val sharedPrefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val forwardingMethod = sharedPrefs.getString(Constants.KEY_FORWARDING_METHOD, Constants.FORWARDING_METHOD_WEBHOOK)

        val subject = "OTP from $sender (via OTP Relay App)"
        val body = "OTP: $otp\nSender: $sender\nMessage: $messageBody\nSource: $sourceType\nTimestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}"

        CoroutineScope(Dispatchers.IO).launch {
            when (forwardingMethod) {
                Constants.FORWARDING_METHOD_WEBHOOK -> {
                    val webhookUrl = sharedPrefs.getString(Constants.KEY_WEBHOOK_URL, null)
                    if (webhookUrl.isNullOrBlank()) {
                        Log.e(TAG, "Webhook URL not configured.")
                        showNotification(context, "Webhook Error", "Webhook URL not set in settings.", Constants.SMS_DEBUG_CHANNEL_ID)
                        return@launch
                    }
                    sendToWebhook(context, webhookUrl, subject, body)
                }
                Constants.FORWARDING_METHOD_DIRECT_EMAIL -> {
                    val smtpHost = sharedPrefs.getString(Constants.KEY_SMTP_HOST, null)
                    val smtpPort = sharedPrefs.getInt(Constants.KEY_SMTP_PORT, 0)
                    val smtpUsername = sharedPrefs.getString(Constants.KEY_SMTP_USERNAME, null)
                    val smtpPassword = sharedPrefs.getString(Constants.KEY_SMTP_PASSWORD, null)
                    val recipientEmail = sharedPrefs.getString(Constants.KEY_RECIPIENT_EMAIL, null)
                    val senderEmail = sharedPrefs.getString(Constants.KEY_SENDER_EMAIL, null)

                    if (smtpHost.isNullOrBlank() || smtpPort == 0 || smtpUsername.isNullOrBlank() ||
                        smtpPassword.isNullOrBlank() || recipientEmail.isNullOrBlank() || senderEmail.isNullOrBlank()) {
                        Log.e(TAG, "Email SMTP settings incomplete.")
                        showNotification(context, "Email Error", "Email SMTP settings incomplete. Please check app settings.", Constants.SMS_DEBUG_CHANNEL_ID)
                        return@launch
                    }
                    EmailSender.sendEmail(context, subject, body, recipientEmail, senderEmail, smtpHost, smtpPort, smtpUsername, smtpPassword)
                }
                else -> {
                    Log.e(TAG, "Unknown forwarding method: $forwardingMethod")
                    showNotification(context, "Forwarding Error", "Unknown forwarding method selected.", Constants.SMS_DEBUG_CHANNEL_ID)
                }
            }

            // Send broadcast to update UI in MainActivity
            val broadcastIntent = Intent(Constants.ACTION_OTP_FORWARDED).apply {
                putExtra(Constants.EXTRA_FORWARDED_OTP, otp)
                putExtra(Constants.EXTRA_SENDER, sender)
                putExtra(Constants.EXTRA_TIMESTAMP, System.currentTimeMillis())
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(broadcastIntent)
        }
    }

    private suspend fun sendToWebhook(context: Context, url: String, subject: String, body: String): Boolean {
        return withContext(Dispatchers.IO) {
            val json = JSONObject().apply {
                put("subject", subject)
                put("body", body)
            }
            val requestBody = RequestBody.Companion.create("application/json; charset=utf-8".toMediaTypeOrNull(), json.toString())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            try {
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Webhook sent successfully to $url. Response: ${response.code}")
                    showNotification(context, "✅ OTP Forwarded (Webhook)", "OTP sent via webhook.", Constants.SMS_DEBUG_CHANNEL_ID)
                    true
                } else {
                    val errorBody = response.body?.string()
                    Log.e(TAG, "Webhook failed: ${response.code} - $errorBody")
                    showNotification(context, "❌ Webhook Failed", "Error: ${response.code} - $errorBody", Constants.SMS_DEBUG_CHANNEL_ID)
                    false
                }
            } catch (e: IOException) {
                Log.e(TAG, "Webhook network error: ${e.message}", e)
                showNotification(context, "❌ Webhook Network Error", "Error: ${e.message}", Constants.SMS_DEBUG_CHANNEL_ID)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Webhook unexpected error: ${e.message}", e)
                showNotification(context, "❌ Webhook Error", "Unexpected error: ${e.message}", Constants.SMS_DEBUG_CHANNEL_ID)
                false
            }
        }
    }

    /**
     * Shows a notification to the user.
     */
    fun showNotification(
        context: Context,
        title: String,
        message: String,
        channelId: String,
        notificationId: Int = (System.currentTimeMillis() % 10000).toInt(), // Unique ID for each notification
        priority: Int = NotificationCompat.PRIORITY_DEFAULT
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = when (channelId) {
                Constants.FOREGROUND_SERVICE_CHANNEL_ID -> Constants.FOREGROUND_SERVICE_CHANNEL_NAME
                Constants.SMS_DEBUG_CHANNEL_ID -> Constants.SMS_DEBUG_CHANNEL_NAME
                else -> "General Notifications"
            }
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = "Channel for OTP Relay App notifications"
            }
            // Register the channel with the system
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Generic icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(priority)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Dismiss notification when tapped

        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Opens the Notification Listener settings screen for the user to grant access.
     */
    fun openNotificationAccessSettings(context: Context): Boolean {
        val cn = ComponentName(context, OTPNotificationListener::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val isEnabled = flat != null && flat.contains(cn.flattenToString())

        if (!isEnabled) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            } else {
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Required if starting from non-activity context
            try {
                context.startActivity(intent)
                showNotification(
                    context,
                    "Notification Access Required",
                    "Please enable 'OTP Relay' in Notification Access settings to forward OTPs from notifications.",
                    Constants.SMS_DEBUG_CHANNEL_ID,
                    priority = NotificationCompat.PRIORITY_HIGH,
                    notificationId = 999 // Fixed ID for this specific notification
                )
            } catch (e: Exception) {
                Log.e(TAG, "OTPForwarder: Failed to open Notification Listener settings: ${e.message}", e)
                showNotification(
                    context,
                    "Failed to Open Settings",
                    "Please go to Settings > Apps & Notifications > Special app access > Notification access and enable 'OTP Relay'.",
                    Constants.SMS_DEBUG_CHANNEL_ID,
                    priority = NotificationCompat.PRIORITY_HIGH,
                    notificationId = 999
                )
            }
        }
        return isEnabled
    }
}