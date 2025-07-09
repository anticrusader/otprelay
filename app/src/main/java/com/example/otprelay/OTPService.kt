package com.example.otprelay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

// New data class to hold SMS information
data class SmsData(val id: Long, val sender: String, val body: String, val timestamp: Long)

class OTPService : Service() {

    private val TAG = Constants.LOG_TAG

    // Binder for clients (currently not used for binding logic, can be null)
    private var serviceBinder: IBinder? = null

    // For background operations
    private lateinit var serviceLooper: Looper
    private lateinit var serviceHandler: ServiceHandler // Custom Handler class for OTPService

    // For ContentObserver
    private var smsContentObserver: ContentObserver? = null

    // In-memory cache for SMS IDs processed by the ContentObserver/Polling to prevent immediate duplicates
    // Key: SMS _id from content provider
    // Value: Timestamp of processing
    private val processedSmsIds = ConcurrentHashMap<Long, Long>() // Changed key to Long for SMS ID

    // Last known SMS ID processed, saved in SharedPreferences for persistence across restarts
    private var lastProcessedSmsIdFromPrefs: Long = -1L

    // Coroutine scope for periodic polling
    private var pollingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // Scope for service-level coroutines

    // Action constants for Intents (for external components to interact, if needed)
    companion object {
        const val ACTION_START_FOREGROUND = "com.example.otprelay.ACTION_START_FOREGROUND"
        const val ACTION_STOP_FOREGROUND = "com.example.otprelay.ACTION_STOP_FOREGROUND"
        const val ACTION_PROCESS_SMS_BROADCAST = "com.example.otprelay.ACTION_PROCESS_SMS_BROADCAST"
        const val EXTRA_SMS_MESSAGE_BODY = "extra_sms_message_body"
        const val EXTRA_SMS_SENDER = "extra_sms_sender"
        const val EXTRA_SMS_TIMESTAMP = "extra_sms_timestamp"
        const val EXTRA_SOURCE_TYPE = "extra_source_type" // Added this constant

        private const val MESSAGE_PROCESS_SMS_CONTENT_PROVIDER = 1
        private const val SMS_POLLING_INTERVAL_MS = 60000L // Poll every 60 seconds
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OTPService: onCreate")

        // Load the last processed SMS ID from preferences
        val sharedPrefs = applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        lastProcessedSmsIdFromPrefs = sharedPrefs.getLong(Constants.KEY_LAST_PROCESSED_SMS_ID, -1L)
        Log.d(TAG, "OTPService: Loaded lastProcessedSmsIdFromPrefs: $lastProcessedSmsIdFromPrefs")

        // Start a new HandlerThread for background operations
        HandlerThread("OTPServiceHandlerThread", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = ServiceHandler(looper)
        }

        // Initialize notification channel for foreground service
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OTPService: onStartCommand, action: ${intent?.action}, startId: $startId")

        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                startForegroundService()
                startSmsMonitoring() // Start both observer and polling
            }
            ACTION_STOP_FOREGROUND -> {
                stopForegroundService()
            }
            ACTION_PROCESS_SMS_BROADCAST -> {
                val messageBody = intent.getStringExtra(EXTRA_SMS_MESSAGE_BODY)
                val sender = intent.getStringExtra(EXTRA_SMS_SENDER)
                val timestamp = intent.getLongExtra(EXTRA_SMS_TIMESTAMP, System.currentTimeMillis())
                val sourceType = intent.getStringExtra(EXTRA_SOURCE_TYPE) ?: OTPForwarder.SourceType.SMS.name
                if (messageBody != null && sender != null) {
                    // Use the handler to process the SMS on the background thread
                    serviceHandler.post {
                        processSms(messageBody, sender, timestamp, sourceType)
                    }
                }
            }
        }

        // If we get killed, after returning from here, restart
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // We don't provide binding, so return null
        return serviceBinder
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OTPService: onDestroy")
        stopSmsMonitoring()
        serviceLooper.quitSafely()
        serviceScope.cancel() // Cancel all coroutines launched in serviceScope
        Log.d(TAG, "OTPService: Service destroyed.")
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, Constants.FOREGROUND_SERVICE_CHANNEL_ID)
            .setContentTitle("OTP Relay Service")
            .setContentText("Monitoring for OTPs...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Makes the notification non-dismissable
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(Constants.FOREGROUND_SERVICE_NOTIFICATION_ID, notification)
        Log.d(TAG, "OTPService: Started foreground service.")
    }

    private fun stopForegroundService() {
        stopForeground(true) // Remove the notification and stop the service
        stopSelf() // Stop the service
        Log.d(TAG, "OTPService: Stopped foreground service.")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                Constants.FOREGROUND_SERVICE_CHANNEL_ID,
                Constants.FOREGROUND_SERVICE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW // Low importance for ongoing service
            ).apply {
                description = "Channel for the foreground OTP Relay service status."
            }

            val debugChannel = NotificationChannel(
                Constants.SMS_DEBUG_CHANNEL_ID,
                Constants.SMS_DEBUG_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT // Default importance for debug messages
            ).apply {
                description = "Channel for debug messages and forwarded OTP confirmations."
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(debugChannel)
        }
    }

    // Handler that receives messages from the thread
    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_PROCESS_SMS_CONTENT_PROVIDER -> {
                    // Extract SmsData from Message object
                    val smsData = msg.obj as SmsData
                    processSms(smsData.body, smsData.sender, smsData.timestamp, OTPForwarder.SourceType.SMS.name)
                }
            }
        }
    }

    private fun startSmsMonitoring() {
        // 1. Register ContentObserver for real-time SMS changes
        if (smsContentObserver == null) {
            smsContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    Log.d(TAG, "SMS ContentObserver: onChange triggered for URI: $uri")
                    // Schedule a check for new SMS messages on the service's background thread
                    serviceHandler.post {
                        checkForNewSms(true) // Indicate it's from observer
                    }
                }
            }
            try {
                contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, smsContentObserver!!)
                Log.d(TAG, "SMS ContentObserver registered.")
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for SMS ContentObserver: ${e.message}")
                OTPForwarder.showNotification(this, "SMS Permission Denied", "Cannot read SMS messages. Please grant READ_SMS permission.", Constants.SMS_DEBUG_CHANNEL_ID)
            }
        }

        // 2. Start periodic polling as a fallback (for devices where ContentObserver might be unreliable or for missed SMS)
        if (pollingJob == null || pollingJob?.isActive == false) {
            pollingJob = serviceScope.launch {
                while (isActive) {
                    delay(SMS_POLLING_INTERVAL_MS)
                    Log.d(TAG, "SMS Polling: Checking for new messages...")
                    checkForNewSms(false) // Indicate it's from polling
                }
            }
            Log.d(TAG, "SMS Polling started.")
        }
    }

    private fun stopSmsMonitoring() {
        smsContentObserver?.let {
            try {
                contentResolver.unregisterContentObserver(it)
                Log.d(TAG, "SMS ContentObserver unregistered.")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering ContentObserver: ${e.message}")
            }
        }
        smsContentObserver = null

        pollingJob?.cancel()
        pollingJob = null
        Log.d(TAG, "SMS Polling stopped.")
    }

    /**
     * Checks for new SMS messages from the content provider.
     * This function should be called on a background thread.
     */
    private fun checkForNewSms(fromObserver: Boolean) {
        val messages = mutableListOf<SmsData>()
        val cursor = try {
            contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.Inbox._ID, Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.BODY, Telephony.Sms.Inbox.DATE),
                null,
                null,
                "${Telephony.Sms.Inbox.DATE} DESC LIMIT 5" // Get the 5 most recent messages
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException when querying SMS inbox: ${e.message}")
            OTPForwarder.showNotification(this, "SMS Read Error", "Permission to read SMS denied.", Constants.SMS_DEBUG_CHANNEL_ID)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying SMS inbox: ${e.message}", e)
            null
        }

        cursor?.use {
            val idColumn = it.getColumnIndex(Telephony.Sms.Inbox._ID)
            val addressColumn = it.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
            val bodyColumn = it.getColumnIndex(Telephony.Sms.Inbox.BODY)
            val dateColumn = it.getColumnIndex(Telephony.Sms.Inbox.DATE)

            if (idColumn == -1 || addressColumn == -1 || bodyColumn == -1 || dateColumn == -1) {
                Log.e(TAG, "One or more SMS columns not found.")
                return
            }

            while (it.moveToNext()) {
                val id = it.getLong(idColumn)
                val sender = it.getString(addressColumn)
                val body = it.getString(bodyColumn)
                val timestamp = it.getLong(dateColumn)
                messages.add(0, SmsData(id, sender, body, timestamp)) // Add to front to process oldest first
            }
        } ?: run {
            Log.w(TAG, "SMS cursor is null or empty. No SMS to process.")
            return
        }

        // Only process messages newer than the last processed ID
        // Or, if from observer, process all up to lastProcessedSmsIdFromPrefs (if it was set post-app install)
        val newMessages = messages.filter { smsData ->
            val isNew = smsData.id > lastProcessedSmsIdFromPrefs
            val isAlreadyProcessedInSession = processedSmsIds.containsKey(smsData.id)
            isNew && !isAlreadyProcessedInSession
        }.sortedBy { it.id } // Ensure processing in chronological order by ID

        if (newMessages.isNotEmpty()) {
            Log.d(TAG, "Found ${newMessages.size} new SMS messages from ${if (fromObserver) "observer" else "polling"}.")
            newMessages.forEach { smsData ->
                // Add to in-memory cache to prevent immediate re-processing
                processedSmsIds[smsData.id] = System.currentTimeMillis()
                processSms(smsData.body, smsData.sender, smsData.timestamp, OTPForwarder.SourceType.SMS.name)
            }
            // Update the last processed SMS ID in preferences only if new messages were processed
            lastProcessedSmsIdFromPrefs = newMessages.last().id
            applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putLong(Constants.KEY_LAST_PROCESSED_SMS_ID, lastProcessedSmsIdFromPrefs)
                .apply()
        } else {
            Log.d(TAG, "No new SMS messages found from ${if (fromObserver) "observer" else "polling"}.")
        }
    }

    /**
     * Processes an SMS message to extract and forward OTP if found.
     * This function should be called on a background thread.
     * @param messageBody The body of the SMS message.
     * @param sender The sender's address/number.
     * @param timestamp The timestamp of the SMS.
     * @param source A string indicating the source of this SMS (e.g., "SMS_ContentProvider", "SMS_Broadcast").
     */
    private fun processSms(messageBody: String, sender: String, timestamp: Long, source: String) {
        val sharedPrefs = this.applicationContext.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        // Retrieve the keywords and custom regexes from preferences
        val smsKeywords = sharedPrefs.getStringSet(Constants.KEY_SMS_KEYWORDS, Constants.DEFAULT_SMS_KEYWORDS)?.toSet() ?: emptySet()
        val customOtpRegexes = sharedPrefs.getStringSet(Constants.KEY_CUSTOM_OTP_REGEXES, Constants.DEFAULT_OTP_REGEXES)?.toSet() ?: emptySet()
        val otpMinLength = sharedPrefs.getInt(Constants.KEY_OTP_MIN_LENGTH, Constants.DEFAULT_OTP_MIN_LENGTH)
        val otpMaxLength = sharedPrefs.getInt(Constants.KEY_OTP_MAX_LENGTH, Constants.DEFAULT_OTP_MAX_LENGTH)

        // Pre-check with keywords to quickly filter out irrelevant messages
        val lowerCaseMessage = messageBody.lowercase(Locale.getDefault())
        val containsKeyword = smsKeywords.any { lowerCaseMessage.contains(it) }

        if (!containsKeyword) {
            Log.d(TAG, "OTPService: SMS from $sender does not contain configured keywords. Skipping OTP extraction.")
            return
        }

        // Pass the keywords and custom regexes to extractOtpFromMessage
        val otp = OTPForwarder.extractOtpFromMessage(messageBody, this.applicationContext, customOtpRegexes, otpMinLength, otpMaxLength)

        if (otp != null) {
            Log.d(TAG, "OTPService: OTP '$otp' extracted from SMS from $sender (Source: $source). Forwarding...")
            // Call the renamed function and pass the source type
            OTPForwarder.forwardOtp(otp, messageBody, sender, this.applicationContext, source)
        } else {
            Log.d(TAG, "OTPService: No OTP found in SMS from $sender (Source: $source): '$messageBody'")
            OTPForwarder.showNotification(
                this,
                "No OTP Found in SMS",
                "From $sender: '$messageBody'",
                Constants.SMS_DEBUG_CHANNEL_ID,
                priority = NotificationCompat.PRIORITY_LOW
            )
        }
    }
}