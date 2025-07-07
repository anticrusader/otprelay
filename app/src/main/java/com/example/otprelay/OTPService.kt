package com.example.otprelay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap // For thread-safe in-memory cache

class OTPService : Service() {

    private val TAG = Constants.LOG_TAG

    // Actions for intents sent to this service
    companion object {
        const val ACTION_PROCESS_SMS_BROADCAST = "com.example.otprelay.ACTION_PROCESS_SMS_BROADCAST"
        const val EXTRA_SMS_MESSAGE_BODY = "sms_message_body"
        const val EXTRA_SMS_SENDER = "sms_sender"
        const val EXTRA_SMS_TIMESTAMP = "sms_timestamp"

        var isServiceRunning = false
    }

    private var smsObserver: SmsObserver? = null
    private var pollingHandler: Handler? = null
    private var pollingRunnable: Runnable? = null
    private var lastProcessedSmsId: Long = -1 // Persisted via SharedPreferences

    // NEW: In-memory cache for recently processed SMS unique keys to prevent near-simultaneous duplicates
    // Key: A unique identifier for the SMS (e.g., hash of sender + body + timestamp)
    // Value: Timestamp when it was added to the cache
    private val recentSmsCache = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OTPService: onCreate")
        isServiceRunning = true
        // Load the last processed SMS ID from preferences
        lastProcessedSmsId = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(Constants.KEY_LAST_PROCESSED_SMS_ID, -1L)
        Log.d(TAG, "OTPService: Loaded lastProcessedSmsId: $lastProcessedSmsId")

        // Ensure notification channels are created when the service is created
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OTPService: onStartCommand. Intent action: ${intent?.action}")

        // Create notification channel and start foreground service immediately
        // Ensure the channel is created BEFORE calling startForeground
        startForeground(Constants.FOREGROUND_SERVICE_NOTIFICATION_ID, createForegroundNotification())
        Log.d(TAG, "OTPService: Foreground service started.")

        // Handle SMS received from SMSReceiver (if any)
        if (intent?.action == ACTION_PROCESS_SMS_BROADCAST) {
            val messageBody = intent.getStringExtra(EXTRA_SMS_MESSAGE_BODY)
            val sender = intent.getStringExtra(EXTRA_SMS_SENDER)
            val timestamp = intent.getLongExtra(EXTRA_SMS_TIMESTAMP, System.currentTimeMillis())

            if (messageBody != null && sender != null) {
                Log.d(TAG, "OTPService: Processing SMS from BroadcastReceiver. Sender: $sender, Body: $messageBody")
                // Pass a flag to indicate this is from BroadcastReceiver, which might need immediate processing
                processSms(messageBody, sender, timestamp, "BroadcastReceiver", true)
            } else {
                Log.w(TAG, "OTPService: Received ACTION_PROCESS_SMS_BROADCAST but SMS data was null.")
            }
        }

        // Register SMS observer (for content provider changes)
        startSmsObserver()

        // Start SMS polling as a last resort for aggressive OEM power managers
        startSmsPolling()

        return START_STICKY // Service will be restarted if killed by system
    }

    /**
     * Creates notification channels required for the foreground service and debug messages.
     * This must be called before any notifications are posted.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Foreground Service Channel
            val serviceChannel = NotificationChannel(
                Constants.FOREGROUND_SERVICE_CHANNEL_ID,
                "OTP Relay Service",
                NotificationManager.IMPORTANCE_LOW // Low importance for persistent background notification
            ).apply {
                description = "Channel for the persistent OTP Relay foreground service notification."
                setSound(null, null) // Make it silent
                enableVibration(false) // Disable vibration
            }

            // SMS Debug Channel
            val debugChannel = NotificationChannel(
                Constants.SMS_DEBUG_CHANNEL_ID,
                "OTP Relay Debug Alerts",
                NotificationManager.IMPORTANCE_HIGH // High importance for critical alerts
            ).apply {
                description = "Channel for debugging SMS processing and errors."
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(serviceChannel)
            notificationManager.createNotificationChannel(debugChannel)
            Log.d(TAG, "OTPService: Notification channels created.")
        }
    }

    /**
     * Registers a ContentObserver to monitor changes in the SMS inbox database.
     * This is a reliable way to detect new SMS if BroadcastReceiver is blocked.
     */
    private fun startSmsObserver() {
        try {
            if (smsObserver == null) { // Ensure only one observer is registered
                smsObserver = SmsObserver(this, Handler(Looper.getMainLooper()))
                contentResolver.registerContentObserver(
                    Uri.parse("content://sms/inbox"), // Specifically monitor inbox
                    true, // Notify descendants
                    smsObserver!!
                )
                Log.d(TAG, "OTPService: SMS Observer registered and started.")
            } else {
                Log.d(TAG, "OTPService: SMS Observer already running, skipping re-registration.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OTPService: Error starting SMS observer", e)
            OTPForwarder.showNotification(
                this,
                "❌ SMS Observer Error",
                "Could not start SMS observer: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID,
                priority = NotificationCompat.PRIORITY_HIGH
            )
        }
    }

    /**
     * Starts a periodic polling mechanism to check for new SMS in the inbox.
     * This is a fallback for devices where BroadcastReceivers and ContentObservers are aggressively killed.
     */
    private fun startSmsPolling() {
        Log.d(TAG, "OTPService: Starting SMS polling.")

        if (pollingHandler == null) {
            pollingHandler = Handler(Looper.getMainLooper())
        }
        if (pollingRunnable == null) {
            pollingRunnable = object : Runnable {
                override fun run() {
                    Log.d(TAG, "OTPService: Polling for new SMS...")
                    checkForNewSmsViaPolling()
                    pollingHandler?.postDelayed(this, Constants.SMS_POLLING_INTERVAL_MS)
                }
            }
            pollingHandler?.post(pollingRunnable!!)
        } else {
            Log.d(TAG, "OTPService: SMS polling already running, skipping re-init.")
        }
    }

    /**
     * Queries the SMS inbox for new messages since the last processed SMS ID.
     * Processes any new messages found.
     */
    private fun checkForNewSmsViaPolling() {
        try {
            val projection = arrayOf("_id", "address", "body", "date")
            // Temporarily remove strict ID and date filtering for debugging purposes
            // We'll query for recent messages and log all found
            val selection = "date > ?"
            val minDate = System.currentTimeMillis() - Constants.DUPLICATE_PREVENTION_WINDOW_MS * 2 // Look back 10 minutes
            val selectionArgs = arrayOf(minDate.toString())
            val sortOrder = "date DESC" // Get newest messages first for easier inspection

            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use {
                Log.d(TAG, "OTPService: Polling cursor found ${it.count} potential messages in total.")
                if (it.moveToFirst()) {
                    do {
                        val smsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                        val address = it.getString(it.getColumnIndexOrThrow("address"))
                        val body = it.getString(it.getColumnIndexOrThrow("body"))
                        val date = it.getLong(it.getColumnIndexOrThrow("date"))

                        Log.d(TAG, "OTPService: Polling found SMS - ID: $smsId, From: $address, Body: '$body', Date: $date")

                        // Now, apply the logic to process only new and relevant messages
                        if (smsId > lastProcessedSmsId && (System.currentTimeMillis() - date < Constants.DUPLICATE_PREVENTION_WINDOW_MS)) {
                            Log.d(TAG, "OTPService: New and recent SMS detected via polling. ID: $smsId. Processing...")
                            processSms(body, address, date, "Polling")
                            // Update lastProcessedSmsId to ensure we don't re-process this one
                            lastProcessedSmsId = smsId
                            getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putLong(Constants.KEY_LAST_PROCESSED_SMS_ID, lastProcessedSmsId)
                                .apply()
                            Log.d(TAG, "OTPService: Updated lastProcessedSmsId to $lastProcessedSmsId after processing.")
                        } else {
                            Log.d(TAG, "OTPService: Skipping SMS via polling (already processed or too old). ID: $smsId")
                        }
                    } while (it.moveToNext())
                } else {
                    Log.d(TAG, "OTPService: Polling cursor moved to first but found no data (after initial query).")
                }
            } ?: Log.e(TAG, "OTPService: SMS polling cursor was null. READ_SMS permission might be missing or content provider issue.")
        } catch (e: SecurityException) {
            Log.e(TAG, "OTPService: SecurityException in SMS polling. READ_SMS permission might be revoked.", e)
            OTPForwarder.showNotification(
                this,
                "❌ SMS Polling Error",
                "READ_SMS permission denied. Check app settings.",
                Constants.SMS_DEBUG_CHANNEL_ID,
                priority = NotificationCompat.PRIORITY_HIGH
            )
        } catch (e: Exception) {
            Log.e(TAG, "OTPService: General error in SMS polling", e)
            OTPForwarder.showNotification(
                this,
                "❌ SMS Polling Error",
                "Failed to check SMS: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID,
                priority = NotificationCompat.PRIORITY_HIGH
            )
        }
    }

    /**
     * Centralized function to process an incoming SMS message.
     * Extracts OTP, checks for duplicates, and forwards via Make.com.
     * @param messageBody The body of the SMS.
     * @param sender The sender's address.
     * @param timestamp The timestamp of the SMS.
     * @param source The source of the SMS (e.g., "BroadcastReceiver", "SmsObserver", "Polling").
     * @param forceProcess Boolean to bypass in-memory duplicate check (e.g., for direct BroadcastReceiver calls).
     */
    fun processSms(messageBody: String, sender: String, timestamp: Long, source: String, forceProcess: Boolean = false) {
        Log.d(TAG, "OTPService: Processing SMS from $source. Body: '$messageBody'")

        // Create a unique key for the SMS based on sender, body, and a rounded timestamp
        // Rounding timestamp to avoid issues with slight variations in detection time
        val uniqueKey = "${sender}_${messageBody}_${timestamp / 1000}" // Group by second

        // Check in-memory cache for recent duplicates first
        // Only skip if not forced (e.g., from BroadcastReceiver which might be primary)
        if (!forceProcess && recentSmsCache.containsKey(uniqueKey) && (System.currentTimeMillis() - recentSmsCache[uniqueKey]!! < Constants.DUPLICATE_PREVENTION_WINDOW_MS)) {
            Log.d(TAG, "OTPService: Skipping SMS from $source due to recent in-memory duplicate (key: $uniqueKey): '$messageBody'")
            return
        }

        // Add to in-memory cache
        recentSmsCache[uniqueKey] = System.currentTimeMillis()

        // Clean up old entries from the cache to prevent it from growing indefinitely
        val cutoffTime = System.currentTimeMillis() - Constants.DUPLICATE_PREVENTION_WINDOW_MS
        recentSmsCache.entries.removeIf { it.value < cutoffTime }

        // Show a debug notification that SMS is being processed
        OTPForwarder.showNotification(
            this,
            "SMS Processed ($source)",
            "From: $sender, Content: $messageBody",
            Constants.SMS_DEBUG_CHANNEL_ID,
            priority = NotificationCompat.PRIORITY_LOW
        )

        val otp = OTPForwarder.extractOtpFromMessage(messageBody)
        if (otp != null) {
            Log.d(TAG, "OTPService: OTP '$otp' extracted from $source. Forwarding...")
            OTPForwarder.forwardOtpViaMake(otp, messageBody, sender, this)
        } else {
            Log.d(TAG, "OTPService: No OTP found in message from $source: '$messageBody'")
            OTPForwarder.showNotification(
                this,
                "No OTP Found ($source)",
                "Message from $sender: '$messageBody'",
                Constants.SMS_DEBUG_CHANNEL_ID,
                priority = NotificationCompat.PRIORITY_LOW
            )
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OTPService: onDestroy")

        // Unregister SMS observer
        try {
            smsObserver?.let {
                contentResolver.unregisterContentObserver(it)
                smsObserver = null
                Log.d(TAG, "OTPService: SMS Observer unregistered.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "OTPService: Error unregistering SMS observer", e)
        }

        // Stop polling
        pollingRunnable?.let {
            pollingHandler?.removeCallbacks(it)
            pollingHandler = null
            pollingRunnable = null
            Log.d(TAG, "OTPService: SMS polling stopped.")
        }

        isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Creates the persistent notification for the foreground service.
     */
    private fun createForegroundNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        // The foreground notification itself should be built directly and returned.
        return NotificationCompat.Builder(this, Constants.FOREGROUND_SERVICE_CHANNEL_ID)
            .setContentTitle("📱 OTP Relay Active")
            .setContentText("Monitoring SMS messages...")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification persistent
            .setSilent(true) // Makes the notification silent
            .setPriority(NotificationCompat.PRIORITY_LOW) // Ensure low priority for foreground service
            .build()
    }

    /**
     * Inner class for SMS ContentObserver.
     * Delegates SMS processing back to the OTPService.
     */
    class SmsObserver(private val serviceContext: OTPService, handler: Handler) : ContentObserver(handler) {
        private val TAG = Constants.LOG_TAG
        // Initialize lastSmsId from preferences to persist state across restarts
        private var lastSmsId: Long = serviceContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(Constants.KEY_LAST_PROCESSED_SMS_ID, -1L)


        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            Log.d(TAG, "SmsObserver: onChange called. Self change: $selfChange, URI: $uri")

            // Query the latest SMS message from the inbox
            try {
                val cursor = serviceContext.contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("_id", "address", "body", "date"),
                    null, // No selection for initial broad query
                    null, // No selection args
                    "date DESC LIMIT 5" // Get the 5 very latest messages for broader check
                )

                cursor?.use {
                    Log.d(TAG, "SmsObserver: Cursor found ${it.count} potential messages.")
                    if (it.moveToFirst()) {
                        do {
                            val smsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                            val body = it.getString(it.getColumnIndexOrThrow("body"))
                            val address = it.getString(it.getColumnIndexOrThrow("address"))
                            val date = it.getLong(it.getColumnIndexOrThrow("date"))

                            Log.d(TAG, "SmsObserver: Found SMS - ID: $smsId, From: $address, Body: '$body', Date: $date")

                            // Only process if it's a new SMS (based on ID) and recent (within 1 minute)
                            // Use the service's processSms method which now includes in-memory duplicate check
                            if (smsId != lastSmsId && (System.currentTimeMillis() - date < Constants.NOTIFICATION_MAX_AGE_MS)) {
                                lastSmsId = smsId // Update last processed ID
                                // Persist the updated lastProcessedSmsId for the observer
                                serviceContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit()
                                    .putLong(Constants.KEY_LAST_PROCESSED_SMS_ID, lastSmsId)
                                    .apply()
                                Log.d(TAG, "SmsObserver: New SMS detected via observer. ID: $smsId. Processing...")
                                serviceContext.processSms(body, address, date, "SmsObserver")
                            } else {
                                Log.d(TAG, "SmsObserver: Skipping old or already processed SMS. ID: $smsId")
                            }
                        } while (it.moveToNext())
                    } else {
                        Log.d(TAG, "SmsObserver: Cursor moved to first but found no data.")
                    }
                } ?: Log.e(TAG, "SmsObserver: Cursor was null. READ_SMS permission might be missing or content provider issue.")
            } catch (e: SecurityException) {
                Log.e(TAG, "SmsObserver: SecurityException in SMS observer. READ_SMS permission might be revoked.", e)
                OTPForwarder.showNotification(
                    serviceContext,
                    "❌ SMS Observer Error",
                    "READ_SMS permission denied. Check app settings.",
                    Constants.SMS_DEBUG_CHANNEL_ID,
                    priority = NotificationCompat.PRIORITY_HIGH
                )
            } catch (e: Exception) {
                Log.e(TAG, "SmsObserver: General error in SMS observer onChange", e)
                OTPForwarder.showNotification(
                    serviceContext,
                    "❌ SMS Observer Error",
                    "Failed to read SMS: ${e.message}",
                    Constants.SMS_DEBUG_CHANNEL_ID,
                    priority = NotificationCompat.PRIORITY_HIGH
                )
            }
        }
    }
}
