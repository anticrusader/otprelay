package com.example.otprelay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat

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

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OTPService: onCreate")
        isServiceRunning = true
        // Load the last processed SMS ID from preferences
        lastProcessedSmsId = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(Constants.KEY_LAST_PROCESSED_SMS_ID, -1L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OTPService: onStartCommand. Intent action: ${intent?.action}")

        // Create notification channel and start foreground service immediately
        startForeground(Constants.FOREGROUND_SERVICE_NOTIFICATION_ID, createForegroundNotification())

        // Handle SMS received from SMSReceiver (if any)
        if (intent?.action == ACTION_PROCESS_SMS_BROADCAST) {
            val messageBody = intent.getStringExtra(EXTRA_SMS_MESSAGE_BODY)
            val sender = intent.getStringExtra(EXTRA_SMS_SENDER)
            val timestamp = intent.getLongExtra(EXTRA_SMS_TIMESTAMP, System.currentTimeMillis())

            if (messageBody != null && sender != null) {
                Log.d(TAG, "OTPService: Processing SMS from BroadcastReceiver. Sender: $sender, Body: $messageBody")
                processSms(messageBody, sender, timestamp, "BroadcastReceiver")
            }
        }

        // Register SMS observer (for content provider changes)
        startSmsObserver()

        // Start SMS polling as a last resort for aggressive OEM power managers
        startSmsPolling()

        return START_STICKY // Service will be restarted if killed by system
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
                Log.d(TAG, "OTPService: SMS Observer started.")
            } else {
                Log.d(TAG, "OTPService: SMS Observer already running.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "OTPService: Error starting SMS observer", e)
            OTPForwarder.showNotification(
                this,
                "❌ SMS Observer Error",
                "Could not start SMS observer: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID
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
                    checkForNewSmsViaPolling()
                    pollingHandler?.postDelayed(this, Constants.SMS_POLLING_INTERVAL_MS)
                }
            }
            pollingHandler?.post(pollingRunnable!!)
        } else {
            Log.d(TAG, "OTPService: SMS polling already running.")
        }
    }

    /**
     * Queries the SMS inbox for new messages since the last processed SMS ID.
     * Processes any new messages found.
     */
    private fun checkForNewSmsViaPolling() {
        try {
            val projection = arrayOf("_id", "address", "body", "date")
            val selection = "_id > ?" // Select messages with ID greater than the last processed
            val selectionArgs = arrayOf(lastProcessedSmsId.toString())
            val sortOrder = "_id ASC" // Process oldest new messages first

            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                projection,
                selection,
                selectionArgs,
                sortOrder
            )

            cursor?.use {
                var newSmsFound = false
                while (it.moveToNext()) {
                    val smsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))

                    // Only process messages that are relatively recent (e.g., within last 5 minutes)
                    // This prevents processing a huge backlog on first start
                    if (System.currentTimeMillis() - date < Constants.DUPLICATE_PREVENTION_WINDOW_MS) {
                        Log.d(TAG, "OTPService: New SMS detected via polling. ID: $smsId, From: $address, Body: $body")
                        processSms(body, address, date, "Polling")
                        newSmsFound = true
                    } else {
                        Log.d(TAG, "OTPService: Skipping old SMS via polling. ID: $smsId")
                    }
                    lastProcessedSmsId = smsId // Always update lastProcessedSmsId to the latest seen
                }
                if (newSmsFound) {
                    // Persist the updated lastProcessedSmsId
                    getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(Constants.KEY_LAST_PROCESSED_SMS_ID, lastProcessedSmsId)
                        .apply()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "OTPService: Error in SMS polling", e)
            OTPForwarder.showNotification(
                this,
                "❌ SMS Polling Error",
                "Failed to check SMS: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID
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
     */
    fun processSms(messageBody: String, sender: String, timestamp: Long, source: String) {
        Log.d(TAG, "OTPService: Processing SMS from $source. Body: '$messageBody'")

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

        // Use the centralized showNotification to create the channel and notification
        OTPForwarder.showNotification(
            this,
            "📱 OTP Forwarder Active",
            "Monitoring SMS messages...",
            Constants.FOREGROUND_SERVICE_CHANNEL_ID,
            Constants.FOREGROUND_SERVICE_NOTIFICATION_ID,
            NotificationCompat.PRIORITY_LOW // Foreground service notifications should be low priority and silent
        )

        // Return the built notification from the manager (or rebuild it if needed)
        // For simplicity, we'll just rebuild it here as showNotification creates it.
        return NotificationCompat.Builder(this, Constants.FOREGROUND_SERVICE_CHANNEL_ID)
            .setContentTitle("📱 OTP Forwarder Active")
            .setContentText("Monitoring SMS messages...")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification persistent
            .setSilent(true) // Makes the notification silent
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    /**
     * Inner class for SMS ContentObserver.
     * Delegates SMS processing back to the OTPService.
     */
    class SmsObserver(private val serviceContext: OTPService, handler: Handler) : ContentObserver(handler) {
        private val TAG = Constants.LOG_TAG
        private var lastSmsId = -1L // Keep track of the last processed SMS ID for observer

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            Log.d(TAG, "SmsObserver: onChange called. Self change: $selfChange, URI: $uri")

            // Query the latest SMS message from the inbox
            try {
                val cursor = serviceContext.contentResolver.query(
                    Uri.parse("content://sms/inbox"),
                    arrayOf("_id", "address", "body", "date"),
                    null,
                    null,
                    "date DESC LIMIT 1" // Get the very latest message
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        val smsId = it.getLong(it.getColumnIndexOrThrow("_id"))
                        val body = it.getString(it.getColumnIndexOrThrow("body"))
                        val address = it.getString(it.getColumnIndexOrThrow("address"))
                        val date = it.getLong(it.getColumnIndexOrThrow("date"))

                        // Only process if it's a new SMS (based on ID) and recent (within 1 minute)
                        if (smsId != lastSmsId && (System.currentTimeMillis() - date < Constants.NOTIFICATION_MAX_AGE_MS)) {
                            lastSmsId = smsId // Update last processed ID
                            Log.d(TAG, "SmsObserver: New SMS detected via observer. ID: $smsId, From: $address, Body: $body")
                            serviceContext.processSms(body, address, date, "SmsObserver")
                        } else {
                            Log.d(TAG, "SmsObserver: Skipping old or already processed SMS. ID: $smsId")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SmsObserver: Error in SMS observer onChange", e)
                OTPForwarder.showNotification(
                    serviceContext,
                    "❌ SMS Observer Error",
                    "Failed to read SMS: ${e.message}",
                    Constants.SMS_DEBUG_CHANNEL_ID
                )
            }
        }
    }
}
