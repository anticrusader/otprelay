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

        private const val MESSAGE_PROCESS_SMS_CONTENT_PROVIDER = 1
    }

    // Handler that receives messages from the thread
    private inner class ServiceHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MESSAGE_PROCESS_SMS_CONTENT_PROVIDER -> {
                    Log.d(TAG, "ServiceHandler: Processing SMS from content provider via message.")
                    processNewSmsFromContentProvider()
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "OTPService: onCreate")

        createNotificationChannels() // Ensure channels exist
        startForegroundService() // Start as foreground immediately

        // Start up the thread running the service. A looper is needed for the Handler.
        HandlerThread("OTPServiceThread", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = ServiceHandler(looper)
        }

        // Load the last processed SMS ID from SharedPreferences
        lastProcessedSmsIdFromPrefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(Constants.KEY_LAST_PROCESSED_SMS_ID, -1L)
        Log.d(TAG, "OTPService: Loaded lastProcessedSmsId from prefs: $lastProcessedSmsIdFromPrefs")

        // Register the SMS Content Observer
        registerSmsContentObserver()

        // Start periodic polling for SMS (as a fallback/complement to ContentObserver)
        startSmsPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "OTPService: onStartCommand, action: ${intent?.action}, startId: $startId")

        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                Log.d(TAG, "OTPService: Received ACTION_START_FOREGROUND. Ensuring foreground.")
                startForegroundService() // Ensure it's in foreground mode
            }
            ACTION_STOP_FOREGROUND -> {
                Log.d(TAG, "OTPService: Received ACTION_STOP_FOREGROUND. Stopping service.")
                stopSelf() // Stops the service
            }
            ACTION_PROCESS_SMS_BROADCAST -> {
                val messageBody = intent.getStringExtra(EXTRA_SMS_MESSAGE_BODY)
                val sender = intent.getStringExtra(EXTRA_SMS_SENDER)
                val timestamp = intent.getLongExtra(EXTRA_SMS_TIMESTAMP, System.currentTimeMillis())
                Log.d(TAG, "OTPService: Received ACTION_PROCESS_SMS_BROADCAST for SMS from $sender.")
                if (messageBody != null && sender != null) {
                    processSms(messageBody, sender, timestamp, source = "SMS_Broadcast")
                } else {
                    Log.e(TAG, "OTPService: SMS broadcast intent missing message body or sender.")
                }
            }
            else -> {
                // For direct service starts (e.g., from system, or startService without specific action)
                // Ensure it transitions to foreground if not already.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService()
                }
            }
        }

        return START_STICKY // Service will be recreated by system if killed
    }

    override fun onBind(intent: Intent): IBinder? {
        Log.d(TAG, "OTPService: onBind")
        return serviceBinder // Return null if no binding is needed
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "OTPService: onDestroy. Cleaning up resources.")

        smsContentObserver?.let {
            contentResolver.unregisterContentObserver(it)
            Log.d(TAG, "OTPService: SMS Content Observer unregistered.")
        }

        pollingJob?.cancel() // Cancel the coroutine job for polling
        pollingJob = null
        serviceScope.cancel() // Cancel the entire scope and its children
        Log.d(TAG, "OTPService: Coroutine scope and SMS polling job cancelled.")

        serviceLooper.quitSafely() // Quit the HandlerThread gracefully
        Log.d(TAG, "OTPService: Service HandlerThread quit.")

        // Save the last processed SMS ID to SharedPreferences for next start
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(Constants.KEY_LAST_PROCESSED_SMS_ID, lastProcessedSmsIdFromPrefs)
            .apply()
        Log.d(TAG, "OTPService: Saved lastProcessedSmsId: $lastProcessedSmsIdFromPrefs to SharedPreferences.")

        stopForeground(Service.STOP_FOREGROUND_REMOVE) // Remove the foreground notification
        Log.d(TAG, "OTPService: Foreground service stopped.")

        OTPForwarder.showNotification(
            this,
            "OTP Relay Service Stopped",
            "Service has been stopped.",
            Constants.SMS_DEBUG_CHANNEL_ID,
            priority = NotificationCompat.PRIORITY_LOW,
            notificationId = Constants.FOREGROUND_SERVICE_NOTIFICATION_ID + 2 // A different ID for stopped notification
        )
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                Constants.FOREGROUND_SERVICE_CHANNEL_ID,
                "OTP Relay Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for the foreground service notifications."
                setSound(null, null)
                enableVibration(false)
            }
            val debugChannel = NotificationChannel(
                Constants.SMS_DEBUG_CHANNEL_ID,
                "OTP Relay Debug Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for debugging SMS/OTP processing."
            }

            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(debugChannel)
            Log.d(TAG, "OTPService: Notification channels created/ensured.")
        }
    }

    private fun startForegroundService() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        )

        val notification = NotificationCompat.Builder(this, Constants.FOREGROUND_SERVICE_CHANNEL_ID)
            .setContentTitle("OTP Relay Service Running")
            .setContentText("Monitoring for OTPs...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(Constants.FOREGROUND_SERVICE_NOTIFICATION_ID, notification)
        Log.d(TAG, "OTPService: Foreground service started.")
    }

    /**
     * Registers a ContentObserver to listen for changes in the SMS inbox.
     * This is the preferred method for real-time SMS detection.
     */
    private fun registerSmsContentObserver() {
        if (smsContentObserver == null) {
            smsContentObserver = object : ContentObserver(serviceHandler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    Log.d(TAG, "ContentObserver: onChange detected on URI: $uri, selfChange: $selfChange")
                    // Post a message to the handler to process SMS on the service's background thread
                    serviceHandler.obtainMessage(MESSAGE_PROCESS_SMS_CONTENT_PROVIDER).sendToTarget()
                }
            }
            try {
                contentResolver.registerContentObserver(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    true, // notifyForDescendants - true for inbox SMS
                    smsContentObserver!!
                )
                Log.d(TAG, "OTPService: SMS Content Observer registered successfully.")
            } catch (e: SecurityException) {
                Log.e(TAG, "OTPService: SMS permission denied for ContentObserver. Cannot register. ${e.message}")
                OTPForwarder.showNotification(
                    this,
                    "SMS Permission Denied",
                    "Cannot monitor SMS. Please grant READ_SMS permission.",
                    Constants.SMS_DEBUG_CHANNEL_ID
                )
            } catch (e: Exception) {
                Log.e(TAG, "OTPService: Failed to register SMS Content Observer: ${e.message}", e)
            }
        }
    }

    /**
     * Starts a periodic polling job to check for new SMS messages.
     * This acts as a fallback or complement to the ContentObserver,
     * ensuring messages are processed even if observer issues arise.
     */
    private fun startSmsPolling() {
        // Cancel any existing polling job before starting a new one
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                Log.d(TAG, "SMS Polling: Initiating periodic SMS check.")
                processNewSmsFromContentProvider()
                delay(Constants.SMS_POLLING_INTERVAL_MS)
            }
        }
        Log.d(TAG, "OTPService: Periodic SMS polling started.")
    }

    /**
     * Queries the SMS content provider for new messages and processes them.
     * This function is called by both the ContentObserver and the periodic poller.
     */
    private fun processNewSmsFromContentProvider() {
        val uri = Telephony.Sms.Inbox.CONTENT_URI
        val sortOrder = "${Telephony.Sms.DATE} DESC LIMIT 10" // Get the 10 most recent messages
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE // Timestamp when the SMS was received
        )

        var cursor: android.database.Cursor? = null
        try {
            cursor = contentResolver.query(uri, projection, null, null, sortOrder)

            cursor?.let {
                val idColumn = it.getColumnIndex(Telephony.Sms._ID)
                val addressColumn = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyColumn = it.getColumnIndex(Telephony.Sms.BODY)
                val dateColumn = it.getColumnIndex(Telephony.Sms.DATE)

                if (idColumn == -1 || addressColumn == -1 || bodyColumn == -1 || dateColumn == -1) {
                    Log.e(TAG, "OTPService: One or more SMS columns not found. Check projection.")
                    OTPForwarder.showNotification(
                        this,
                        "SMS Processing Error",
                        "Required SMS data not found. Check app permissions and Android version compatibility.",
                        Constants.SMS_DEBUG_CHANNEL_ID
                    )
                    return
                }

                val messagesToProcess = mutableListOf<SmsData>()

                if (it.moveToFirst()) {
                    do {
                        val smsId = it.getLong(idColumn)
                        val sender = it.getString(addressColumn)
                        val body = it.getString(bodyColumn)
                        val timestamp = it.getLong(dateColumn)

                        // Only process SMS messages that are newer than the last processed ID
                        // AND within a reasonable time window (e.g., last 1 minute)
                        if (smsId > lastProcessedSmsIdFromPrefs &&
                            (System.currentTimeMillis() - timestamp < Constants.SMS_MAX_AGE_FOR_PROCESSING_MS)
                        ) {
                            // Check against in-memory cache to prevent immediate duplicates from ContentObserver/Polling
                            if (!processedSmsIds.containsKey(smsId) ||
                                (System.currentTimeMillis() - (processedSmsIds[smsId] ?: 0L) > Constants.DUPLICATE_PREVENTION_WINDOW_MS)) {
                                messagesToProcess.add(SmsData(smsId, sender, body, timestamp))
                            } else {
                                Log.d(TAG, "Skipping recently processed SMS ID $smsId from content provider (in-service cache hit).")
                            }
                        } else if (smsId <= lastProcessedSmsIdFromPrefs) {
                            Log.d(TAG, "Skipping old SMS ID $smsId (lastProcessedId: $lastProcessedSmsIdFromPrefs).")
                        } else { // This else block catches messages that are newer by ID but too old by timestamp
                            Log.d(TAG, "Skipping SMS ID $smsId (timestamp: $timestamp), older than ${Constants.SMS_MAX_AGE_FOR_PROCESSING_MS}ms.")
                        }
                    } while (it.moveToNext())
                }

                // Process messages in ascending order of SMS ID to maintain sequence
                messagesToProcess.sortBy { smsData -> smsData.id }

                var highestProcessedIdInThisBatch: Long = lastProcessedSmsIdFromPrefs

                for (smsData in messagesToProcess) {
                    Log.d(TAG, "OTPService: Processing new SMS ID: ${smsData.id} from ${smsData.sender} (Content Provider).")
                    processSms(smsData.body, smsData.sender, smsData.timestamp, source = "SMS_ContentProvider")

                    // Add to in-memory cache after processing attempt
                    processedSmsIds[smsData.id] = System.currentTimeMillis()
                    Log.d(TAG, "OTPService: Added SMS ID ${smsData.id} to in-memory cache.")

                    if (smsData.id > highestProcessedIdInThisBatch) {
                        highestProcessedIdInThisBatch = smsData.id
                    }
                }

                // Update the lastProcessedSmsIdFromPrefs only after processing all relevant messages in this batch
                if (highestProcessedIdInThisBatch > lastProcessedSmsIdFromPrefs) {
                    lastProcessedSmsIdFromPrefs = highestProcessedIdInThisBatch
                    // Persist to SharedPreferences immediately for robustness
                    getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putLong(Constants.KEY_LAST_PROCESSED_SMS_ID, lastProcessedSmsIdFromPrefs)
                        .apply()
                    Log.d(TAG, "OTPService: Updated lastProcessedSmsIdFromPrefs to $lastProcessedSmsIdFromPrefs.")
                }

                // Prune old entries from cache regardless of new SMS processing
                val cutoffTime = System.currentTimeMillis() - Constants.DUPLICATE_PREVENTION_WINDOW_MS
                processedSmsIds.entries.removeIf { it.value < cutoffTime }
                Log.d(TAG, "OTPService: Pruned processedSmsIds cache. Current size: ${processedSmsIds.size}")

            } ?: Log.d(TAG, "OTPService: No new SMS messages or cursor is null.")

        } catch (e: SecurityException) {
            Log.e(TAG, "OTPService: SMS permission not granted to read SMS: ${e.message}")
            OTPForwarder.showNotification(
                this,
                "SMS Permission Required",
                "Please grant READ_SMS permission to allow OTP Relay to read new messages.",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
            // If permission is denied, stop polling and observer as they won't work
            smsContentObserver?.let {
                contentResolver.unregisterContentObserver(it)
                smsContentObserver = null
                Log.d(TAG, "OTPService: Unregistered observer due to permission denial.")
            }
            pollingJob?.cancel()
            pollingJob = null
            Log.d(TAG, "OTPService: Cancelled polling due to permission denial.")

        } catch (e: Exception) {
            Log.e(TAG, "OTPService: Error querying SMS content provider: ${e.message}", e)
            OTPForwarder.showNotification(
                this,
                "SMS Reading Error",
                "Failed to read SMS messages: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
        } finally {
            cursor?.close()
        }
    }


    /**
     * Common function to process an SMS message (from ContentObserver, Polling, or Broadcast).
     * It extracts OTP and initiates forwarding.
     * @param messageBody The full body of the SMS message.
     * @param sender The sender's address/number.
     * @param timestamp The timestamp of the SMS.
     * @param source A string indicating the source of this SMS (e.g., "SMS_ContentProvider", "SMS_Broadcast").
     */
    private fun processSms(messageBody: String, sender: String, timestamp: Long, source: String) {
        val otp = OTPForwarder.extractOtpFromMessage(messageBody, this.applicationContext)

        if (otp != null) {
            Log.d(TAG, "OTPService: OTP '$otp' extracted from SMS from $sender (Source: $source). Forwarding...")
            // The OTPForwarder itself contains its own duplicate check based on the OTP value and sender.
            OTPForwarder.forwardOtpViaMake(otp, messageBody, sender, this.applicationContext)
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