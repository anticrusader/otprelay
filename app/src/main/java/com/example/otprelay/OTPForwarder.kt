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

object OTPForwarder {

    private val TAG = Constants.LOG_TAG
    private val client = OkHttpClient()

    // In-memory cache to prevent duplicate forwarding of the same OTP
    // Key: "OTP_value_normalizedSender_timestamp_rounded" (e.g., "123456_minemobile_1701010100")
    // Value: Timestamp when it was last forwarded (System.currentTimeMillis())
    private val forwardedOtpCache = ConcurrentHashMap<String, Long>()

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
            lowerCaseSender.contains("adnan") || lowerCaseSender.contains("03105697413") -> "adnan_sms_service"


            // 1. Fallback to extracting phone number if not caught by explicit mapping
            // This regex must be robust to catch various phone number formats.
            else -> {
                val phoneMatch = Regex("\\+?\\d[\\d\\s\\-()]{7,18}\\d").find(lowerCaseSender)
                if (phoneMatch != null) {
                    // Return digits only for phone numbers for consistency
                    return phoneMatch.value.replace("[^\\d]".toRegex(), "")
                }
                // 2. Fallback to generic alphanumeric cleanup if no phone number and no specific mapping
                // Remove all non-alphanumeric characters, take first 50, and convert to lowercase
                lowerCaseSender.replace("[^a-zA-Z0-9]".toRegex(), "").take(50)
            }
        }
    }


    /**
     * Checks if a specific OTP has been recently forwarded based on a unique key.
     * Also prunes old entries from the cache to manage memory.
     * @param otpKey A unique key for the OTP (e.g., "OTP_value_normalizedSender_timestamp_rounded").
     * @return True if the OTP was forwarded recently (within DUPLICATE_PREVENTION_WINDOW_MS), false otherwise.
     */
    internal fun wasRecentlyForwarded(otpKey: String): Boolean {
        // Prune old entries from cache before checking for duplicates
        val cutoffTime = System.currentTimeMillis() - Constants.DUPLICATE_PREVENTION_WINDOW_MS
        forwardedOtpCache.entries.removeIf { it.value < cutoffTime }

        val lastForwarded = forwardedOtpCache[otpKey] ?: 0L
        val isDuplicate = (System.currentTimeMillis() - lastForwarded < Constants.DUPLICATE_PREVENTION_WINDOW_MS)
        if (isDuplicate) {
            Log.d(TAG, "OTPForwarder: Cache hit for key '$otpKey'. Last forwarded: ${lastForwarded}. Skipping.")
        }
        return isDuplicate
    }

    /**
     * Extracts an OTP from a given message body using configurable regex patterns.
     *
     * @param messageBody The text message body.
     * @param context The application context to retrieve OTP length settings.
     * @return The extracted OTP string, or null if not found.
     */
    fun extractOtpFromMessage(messageBody: String, context: Context): String? {
        val sharedPrefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val minLength = sharedPrefs.getInt(Constants.KEY_OTP_MIN_LENGTH, Constants.DEFAULT_OTP_MIN_LENGTH)
        val maxLength = sharedPrefs.getInt(Constants.KEY_OTP_MAX_LENGTH, Constants.DEFAULT_OTP_MAX_LENGTH)

        // Construct the dynamic length placeholder string
        val lengthRange = "{$minLength,$maxLength}"

        // Dynamically build regex patterns with configurable digit length
        val dynamicRegexes = Constants.OTP_REGEX_PATTERNS_TO_GENERATE.map { patternString ->
            val finalPatternString = patternString.replace("{LENGTH_PLACEHOLDER}", lengthRange)
            Regex(finalPatternString, RegexOption.IGNORE_CASE)
        }

        // Combine dynamic patterns with any fixed-length specific patterns
        // Order of concatenation matters: specific fixed patterns might be more accurate.
        val allPatterns = Constants.OTP_FIXED_LENGTH_REGEX_PATTERNS + dynamicRegexes

        for (regex in allPatterns) {
            val matchResult = regex.find(messageBody)
            if (matchResult != null && matchResult.groupValues.size > 1) {
                val otp = matchResult.groupValues[1]

                // Validate OTP length
                if (otp.length !in minLength..maxLength) {
                    Log.d(TAG, "Skipping extracted string '$otp' as it does not meet length criteria ($minLength-$maxLength). Pattern: '${regex.pattern}'. Message: '$messageBody'")
                    continue // Try next regex if length does not match
                }

                // *** CRUCIAL HEURISTIC: Check if the extracted OTP is likely a phone number instead ***
                // This aims to prevent false positives when a phone number looks like an OTP
                val lowerCaseMessage = messageBody.lowercase(Locale.getDefault())

                // Conditions for likely a phone number
                val isLikelyPhoneNumber = when (otp.length) {
                    // Typical lengths for local phone numbers (e.g., 7 digits like 5697413)
                    // Check if it starts with common mobile network prefixes if applicable to your region
                    7 -> (lowerCaseMessage.contains("0310") || lowerCaseMessage.contains("0300") || lowerCaseMessage.contains("0333")) && lowerCaseMessage.contains(otp) // Example: 03XX YYYYYYY
                    // General check for longer numbers that might be full international numbers without specific country codes
                    in 10..15 -> lowerCaseMessage.contains(otp) // If it's a long number and directly present
                    else -> false
                }

                // Conditions for strong OTP keywords near the extracted number
                val strongOtpKeywords = listOf("otp", "code", "pin", "verify", "verification", "passcode", "login", "access")
                val isStronglyContextualizedAsOtp = strongOtpKeywords.any {
                    // Check if the keyword is within a reasonable proximity of the extracted OTP
                    val regexWithContext = Regex("($it\\s*[:is]*\\s*$otp|$otp\\s*(?:is your|$it)\\b)", RegexOption.IGNORE_CASE)
                    regexWithContext.containsMatchIn(lowerCaseMessage)
                }

                // If it looks like a phone number AND it's NOT strongly contextualized as an OTP, skip it.
                if (isLikelyPhoneNumber && !isStronglyContextualizedAsOtp) {
                    Log.d(TAG, "Skipping '$otp' (length ${otp.length}) from message: '$messageBody'. It looks like a phone number and lacks strong OTP context. Pattern: '${regex.pattern}'")
                    continue // Continue to the next regex
                }

                // If we reach here, it's either not a likely phone number or it has strong OTP context.
                Log.d(TAG, "OTP extracted: '$otp' using pattern: '${regex.pattern}' from message: '$messageBody'")
                return otp
            }
        }
        Log.d(TAG, "No OTP found in message: '$messageBody'")
        return null
    }

    /**
     * Forwards the extracted OTP using either webhook or direct email based on user settings.
     *
     * @param otp The extracted OTP.
     * @param originalMessage The full original SMS message.
     * @param sender The sender of the SMS (can be phone number or app name from notification).
     * @param context The application context.
     */
    fun forwardOtpViaMake(otp: String, originalMessage: String, sender: String, context: Context) {
        val sharedPrefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val forwardingMethod = sharedPrefs.getString(Constants.KEY_FORWARDING_METHOD, Constants.FORWARDING_METHOD_WEBHOOK)

        // Use the normalized sender for the cache key to detect cross-source duplicates
        val normalizedSender = normalizeSender(sender)
        // Create a unique key for this specific OTP instance to prevent immediate duplicates across forwarding methods.
        // Round timestamp to a 5-second interval for robustness against minor time differences.
        val otpKey = "OTP_${otp}_${normalizedSender}_${System.currentTimeMillis() / 5000L}"

        if (wasRecentlyForwarded(otpKey)) {
            Log.d(TAG, "OTPForwarder: Duplicate OTP '$otp' from original sender '$sender' (normalized to '$normalizedSender') detected. Skipping forwarding.")
            showNotification(
                context,
                "⚠️ Duplicate OTP Skipped",
                "OTP '$otp' already sent from '$sender'",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
            // No need to send broadcast for skipped duplicates as it wasn't 'newly' forwarded.
            return
        }

        // Mark this OTP as forwarded in the cache
        forwardedOtpCache[otpKey] = System.currentTimeMillis()
        Log.d(TAG, "OTPForwarder: Added OTP '$otp' from original sender '$sender' (normalized to '$normalizedSender') to forwarded cache.")


        CoroutineScope(Dispatchers.IO).launch {
            val deviceModel = Build.MODEL
            val deviceBrand = Build.BRAND
            val androidVersion = Build.VERSION.RELEASE
            val timestamp = System.currentTimeMillis() // Get raw timestamp here
            val timestampFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

            val jsonBody = JSONObject().apply {
                put("otp", otp)
                put("original_message", originalMessage)
                put("sender", sender) // Use original sender for the payload
                put("timestamp", timestampFormatted)
                put("device_model", deviceModel)
                put("device_brand", deviceBrand)
                put("android_version", androidVersion)
            }

            var forwardingSuccessful = false // Flag to track successful forwarding

            when (forwardingMethod) {
                Constants.FORWARDING_METHOD_WEBHOOK -> {
                    val webhookUrl = sharedPrefs.getString(Constants.KEY_WEBHOOK_URL, Constants.MAKE_WEBHOOK_URL)
                    if (webhookUrl.isNullOrEmpty() || webhookUrl == Constants.MAKE_WEBHOOK_URL) {
                        Log.e(TAG, "OTPForwarder: Webhook URL not configured or is placeholder. Cannot forward via webhook.")
                        showNotification(
                            context,
                            "❌ Webhook Failed",
                            "Webhook URL not set. Configure in app settings.",
                            Constants.SMS_DEBUG_CHANNEL_ID
                        )
                        // Do not clear the cache entry here. It was a configuration error, not a successful forward.
                    } else {
                        forwardingSuccessful = sendToWebhook(webhookUrl, jsonBody.toString(), context)
                    }
                }
                Constants.FORWARDING_METHOD_DIRECT_EMAIL -> {
                    val recipientEmail = sharedPrefs.getString(Constants.KEY_RECIPIENT_EMAIL, "")
                    val senderEmail = sharedPrefs.getString(Constants.KEY_SENDER_EMAIL, "")
                    val smtpHost = sharedPrefs.getString(Constants.KEY_SMTP_HOST, "")
                    val smtpPort = sharedPrefs.getInt(Constants.KEY_SMTP_PORT, 587)
                    val smtpUsername = sharedPrefs.getString(Constants.KEY_SMTP_USERNAME, "")
                    val smtpPassword = sharedPrefs.getString(Constants.KEY_SMTP_PASSWORD, "") // SECURITY WARNING

                    if (recipientEmail.isNullOrEmpty() || senderEmail.isNullOrEmpty() ||
                        smtpHost.isNullOrEmpty() || smtpUsername.isNullOrEmpty() || smtpPassword.isNullOrEmpty()) {
                        Log.e(TAG, "OTPForwarder: Incomplete email settings for direct email forwarding.")
                        showNotification(
                            context,
                            "❌ Email Failed",
                            "Incomplete email settings. Configure in app settings.",
                            Constants.SMS_DEBUG_CHANNEL_ID
                        )
                    } else {
                        val subject = "OTP from $sender: $otp"
                        val emailBody = "OTP: $otp\nOriginal Message: $originalMessage\nSender: $sender\nTimestamp: $timestampFormatted\nDevice: $deviceBrand $deviceModel (Android $androidVersion)"

                        forwardingSuccessful = EmailSender.sendEmail(
                            context = context,
                            subject = subject,
                            body = emailBody,
                            recipient = recipientEmail,
                            senderEmail = senderEmail,
                            smtpHost = smtpHost,
                            smtpPort = smtpPort,
                            smtpUsername = smtpUsername,
                            smtpPassword = smtpPassword
                        )
                    }
                }
                else -> {
                    Log.e(TAG, "OTPForwarder: Unknown forwarding method: $forwardingMethod")
                    showNotification(
                        context,
                        "❌ Forwarding Error",
                        "Unknown forwarding method selected. Check app settings.",
                        Constants.SMS_DEBUG_CHANNEL_ID
                    )
                }
            }

            // Send broadcast ONLY if forwarding was successful
            if (forwardingSuccessful) {
                sendOtpForwardedBroadcast(context, otp, sender, timestamp)
            } else {
                // If forwarding failed, and it wasn't a duplicate, remove from cache
                // This allows a retry if the failure was transient (e.g., network error)
                forwardedOtpCache.remove(otpKey)
                Log.d(TAG, "OTPForwarder: Removed '$otpKey' from cache due to forwarding failure.")
            }
        }
    }

    /**
     * Sends the JSON payload to the specified webhook URL.
     * Returns true on success, false on failure.
     */
    private suspend fun sendToWebhook(url: String, json: String, context: Context): Boolean {
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

        if (mediaType == null) {
            Log.e(TAG, "OTPForwarder: Failed to create MediaType for JSON.")
            showNotification(
                context,
                "❌ Webhook Error",
                "Internal error: Invalid media type.",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
            return false
        }

        val body = RequestBody.create(mediaType, json)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        return try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() } // Execute synchronously in coroutine
            response.use {
                if (!it.isSuccessful) {
                    val errorBody = it.body?.string()
                    Log.e(TAG, "OTPForwarder: Webhook request failed with code ${it.code}: $errorBody")
                    showNotification(
                        context,
                        "❌ Webhook Failed",
                        "Server error: ${it.code} - ${errorBody ?: "No error body"}",
                        Constants.SMS_DEBUG_CHANNEL_ID
                    )
                    false
                } else {
                    Log.d(TAG, "OTPForwarder: Webhook request successful. Response: ${it.body?.string()}")
                    showNotification(
                        context,
                        "✅ OTP Forwarded (Webhook)",
                        "OTP sent to webhook successfully.",
                        Constants.SMS_DEBUG_CHANNEL_ID
                    )
                    true
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "OTPForwarder: Webhook request failed: ${e.message}", e)
            showNotification(
                context,
                "❌ Webhook Failed",
                "Network error: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "OTPForwarder: Unexpected error during webhook request: ${e.message}", e)
            showNotification(
                context,
                "❌ Webhook Failed",
                "Unexpected error: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
            false
        }
    }

    /**
     * Sends a local broadcast indicating that an OTP has been forwarded.
     */
    private fun sendOtpForwardedBroadcast(context: Context, otp: String, sender: String, timestamp: Long) {
        val intent = Intent(Constants.ACTION_OTP_FORWARDED).apply {
            putExtra(Constants.EXTRA_FORWARDED_OTP, otp)
            putExtra(Constants.EXTRA_SENDER, sender)
            putExtra(Constants.EXTRA_TIMESTAMP, timestamp)
        }
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        Log.d(TAG, "OTPForwarder: Sent broadcast for forwarded OTP: $otp from $sender")
    }

    /**
     * Displays a notification to the user.
     * @param context The application context.
     * @param title The title of the notification.
     * @param message The message content of the notification.
     * @param channelId The ID of the notification channel.
     * @param priority The priority of the notification.
     * @param notificationId A specific ID for the notification. If 0, a unique ID is generated.
     */
    fun showNotification(
        context: Context,
        title: String,
        message: String,
        channelId: String,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
        notificationId: Int = 0 // Default to 0, indicating a new ID should be generated
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Ensure the channel exists (important for Android O and above)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = notificationManager.getNotificationChannel(channelId)
            if (channel == null) {
                // If channel doesn't exist, create it. This ensures all notifications use valid channels.
                val defaultChannelName = when (channelId) {
                    Constants.FOREGROUND_SERVICE_CHANNEL_ID -> "OTP Relay Service"
                    Constants.SMS_DEBUG_CHANNEL_ID -> "OTP Relay Debug Alerts"
                    else -> "General Notifications"
                }
                val defaultImportance = when (channelId) {
                    Constants.FOREGROUND_SERVICE_CHANNEL_ID -> NotificationManager.IMPORTANCE_LOW
                    Constants.SMS_DEBUG_CHANNEL_ID -> NotificationManager.IMPORTANCE_HIGH
                    else -> NotificationManager.IMPORTANCE_DEFAULT
                }
                val newChannel = NotificationChannel(channelId, defaultChannelName, defaultImportance).apply {
                    description = "Channel for OTP Relay notifications."
                    // Foreground service channel should be silent
                    if (channelId == Constants.FOREGROUND_SERVICE_CHANNEL_ID) {
                        setSound(null, null)
                        enableVibration(false)
                    }
                }
                notificationManager.createNotificationChannel(newChannel)
                Log.d(TAG, "OTPForwarder: Created missing notification channel: $channelId")
            }
        }

        // Use the provided notificationId or generate a unique one
        val finalNotificationId = if (notificationId != 0) notificationId else System.currentTimeMillis().toInt()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Generic info icon
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message)) // Show full message in expanded notification
            .setPriority(priority)
            .setAutoCancel(true) // Dismiss notification when tapped

        // Add an intent to open the app when the notification is tapped
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )
        builder.setContentIntent(pendingIntent)

        notificationManager.notify(finalNotificationId, builder.build())
        Log.d(TAG, "OTPForwarder: Notification posted - ID: $finalNotificationId, Channel: $channelId, Title: '$title'")
    }

    /**
     * Checks if the Notification Listener Service permission is granted for this app.
     * If not granted, it attempts to open the relevant settings screen.
     * @param context The application context.
     * @return True if permission is granted, false otherwise.
     */
    fun checkAndRequestNotificationListenerPermission(context: Context): Boolean {
        val cn = ComponentName(context, OTPNotificationListener::class.java)
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val isEnabled = flat != null && flat.contains(cn.flattenToString())
        Log.d(TAG, "OTPForwarder: Notification Listener permission check. Is enabled: $isEnabled")

        if (!isEnabled) {
            Log.d(TAG, "OTPForwarder: Notification Listener permission not granted. Prompting user.")
            // Direct user to Notification Access settings
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