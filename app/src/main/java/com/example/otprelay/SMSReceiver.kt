package com.example.otprelay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.app.NotificationCompat

class SMSReceiver : BroadcastReceiver() {
    private val TAG = Constants.LOG_TAG

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "SMSReceiver: onReceive called - Action: ${intent.action}")

        // This is crucial for BroadcastReceivers that perform asynchronous operations.
        // It tells the system that your receiver needs more time to complete its work.
        val pendingResult = goAsync()

        // Show a quick debug notification that an SMS broadcast was received
        OTPForwarder.showNotification(
            context,
            "SMS Broadcast Received",
            "Processing incoming message...",
            Constants.SMS_DEBUG_CHANNEL_ID,
            priority = NotificationCompat.PRIORITY_LOW // Low priority for debug
        )

        if (Telephony.Sms.Intents.SMS_RECEIVED_ACTION == intent.action) {
            Log.d(TAG, "SMSReceiver: SMS_RECEIVED_ACTION confirmed.")

            val bundle = intent.extras
            if (bundle == null) {
                Log.e(TAG, "SMSReceiver: Bundle is null, cannot process SMS. Finishing async operation.")
                pendingResult.finish()
                return
            }

            val pdus = bundle.get("pdus") as Array<*>?
            if (pdus == null || pdus.isEmpty()) {
                Log.e(TAG, "SMSReceiver: PDUs array is null or empty. Finishing async operation.")
                pendingResult.finish()
                return
            }

            Log.d(TAG, "SMSReceiver: Number of PDUs: ${pdus.size}")

            val smsMessages = mutableListOf<SmsMessage>()
            try {
                // Get the SMS format from the bundle (required for createFromPdu on modern Android)
                val format = bundle.getString("format")

                pdus.forEach { pdu ->
                    val smsMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && format != null) {
                        SmsMessage.createFromPdu(pdu as ByteArray, format)
                    } else {
                        @Suppress("DEPRECATION") // For older API levels
                        SmsMessage.createFromPdu(pdu as ByteArray)
                    }
                    smsMessages.add(smsMessage)
                }
            } catch (e: Exception) {
                Log.e(TAG, "SMSReceiver: Error creating SmsMessage from PDU", e)
                OTPForwarder.showNotification(
                    context,
                    "❌ SMS Error",
                    "Failed to parse SMS: ${e.message}",
                    Constants.SMS_DEBUG_CHANNEL_ID
                )
                pendingResult.finish() // Finish even on error
                return
            }

            // Combine messages if multi-part SMS
            val fullMessageBody = smsMessages.joinToString("") { it.messageBody }
            val senderAddress = smsMessages.firstOrNull()?.originatingAddress ?: "Unknown"
            val messageTimestamp = smsMessages.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

            Log.d(TAG, "SMSReceiver: Combined message from $senderAddress: $fullMessageBody (Timestamp: $messageTimestamp)")

            // Delegate the actual OTP processing and forwarding to OTPService
            // This ensures the work is done in a persistent service context
            val serviceIntent = Intent(context, OTPService::class.java).apply {
                action = OTPService.ACTION_PROCESS_SMS_BROADCAST
                putExtra(OTPService.EXTRA_SMS_MESSAGE_BODY, fullMessageBody)
                putExtra(OTPService.EXTRA_SMS_SENDER, senderAddress)
                // Use the actual SMS timestamp for better duplicate prevention, if available.
                // Fallback to current time if not.
                putExtra(OTPService.EXTRA_SMS_TIMESTAMP, messageTimestamp)
                // Add source type so OTPService knows it's an SMS
                putExtra(OTPService.EXTRA_SOURCE_TYPE, OTPForwarder.SourceType.SMS.name)
            }

            // Start the service as a foreground service to ensure it has enough time to process
            // The service itself will handle starting as foreground.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d(TAG, "SMSReceiver: Delegated SMS processing to OTPService.")

            // Mark the broadcast as finished.
            // This is critical when using goAsync() to prevent ANRs.
            pendingResult.finish()
        } else {
            // If it's not the SMS_RECEIVED_ACTION, finish immediately.
            pendingResult.finish()
        }
    }
}