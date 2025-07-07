package com.example.otprelay

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.*

class SmsTestActivity : AppCompatActivity() {

    private val TAG = Constants.LOG_TAG

    private lateinit var resultTextView: TextView
    private lateinit var testSmsAccessButton: Button
    private lateinit var testLastSmsButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sms_test)

        resultTextView = findViewById(R.id.resultTextView)
        testSmsAccessButton = findViewById(R.id.testSmsAccessButton)
        testLastSmsButton = findViewById(R.id.testLastSmsButton)

        testSmsAccessButton.setOnClickListener {
            testSmsContentProviderAccess()
        }

        testLastSmsButton.setOnClickListener {
            testLastFiveSmsMessages()
        }

        // Initial check of permissions on start
        checkSmsPermissionsStatus()
    }

    /**
     * Checks and displays the status of READ_SMS and RECEIVE_SMS permissions.
     */
    private fun checkSmsPermissionsStatus() {
        val readSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
        val receiveSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)

        val status = StringBuilder()
        status.append("Permissions Status:\n")
        status.append("READ_SMS: ${if (readSms == PackageManager.PERMISSION_GRANTED) "✅ GRANTED" else "❌ DENIED"}\n")
        status.append("RECEIVE_SMS: ${if (receiveSms == PackageManager.PERMISSION_GRANTED) "✅ GRANTED" else "❌ DENIED"}\n\n")

        resultTextView.text = status.toString()
    }

    /**
     * Tests access to various SMS content provider URIs.
     */
    private fun testSmsContentProviderAccess() {
        val result = StringBuilder()
        result.append("Testing SMS Content Provider Access...\n\n")

        try {
            val uris = listOf(
                "content://sms/inbox",
                "content://sms",
                "content://sms/sent",
                "content://sms/draft"
            )

            for (uriString in uris) {
                result.append("Testing URI: $uriString\n")
                try {
                    // Attempt to query the URI
                    val cursor = contentResolver.query(
                        Uri.parse(uriString),
                        null, // No specific projection
                        null, // No selection
                        null, // No selection arguments
                        "_id DESC LIMIT 1" // Get just one entry to check access
                    )

                    cursor?.use {
                        result.append("  ✅ Access SUCCESS - Count: ${it.count}\n")
                        // List available columns for debugging
                        if (it.columnNames.isNotEmpty()) {
                            result.append("  Columns: ${it.columnNames.joinToString(", ")}\n")
                        }
                    } ?: result.append("  ❌ Cursor is NULL (Access might be denied or URI invalid)\n")

                } catch (e: SecurityException) {
                    result.append("  ❌ Security Exception: ${e.message}\n")
                    result.append("  (SMS permission might be revoked by system or not granted)\n")
                    Log.e(TAG, "SmsTestActivity: SecurityException accessing $uriString", e)
                } catch (e: Exception) {
                    result.append("  ❌ Error: ${e.message} (${e.javaClass.simpleName})\n")
                    Log.e(TAG, "SmsTestActivity: Error accessing $uriString", e)
                }
                result.append("\n")
            }

        } catch (e: Exception) {
            result.append("❌ General Error during SMS access test: ${e.message}\n")
            Log.e(TAG, "SmsTestActivity: General error in testSmsContentProviderAccess", e)
        }

        resultTextView.text = result.toString()
    }

    /**
     * Fetches and displays details of the last five SMS messages from the inbox.
     * Also attempts to extract OTPs from them.
     */
    private fun testLastFiveSmsMessages() {
        val result = StringBuilder()
        result.append("Fetching Last 5 SMS Messages from Inbox...\n\n")

        try {
            val projection = arrayOf("_id", "address", "body", "date", "read", "type")
            val sortOrder = "date DESC LIMIT 5" // Get the 5 most recent messages

            val cursor = contentResolver.query(
                Uri.parse("content://sms/inbox"),
                projection,
                null,
                null,
                sortOrder
            )

            cursor?.use {
                result.append("Found ${it.count} messages:\n\n")

                var count = 0
                while (it.moveToNext()) {
                    count++
                    val id = it.getLong(it.getColumnIndexOrThrow("_id"))
                    val address = it.getString(it.getColumnIndexOrThrow("address")) ?: "Unknown"
                    val body = it.getString(it.getColumnIndexOrThrow("body")) ?: "Empty"
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))

                    result.append("--- Message #$count (ID: $id) ---\n")
                    result.append("From: $address\n")
                    result.append("Body: $body\n")
                    result.append("Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(date))}\n")

                    // Attempt to extract OTP using the centralized OTPForwarder
                    val otp = OTPForwarder.extractOtpFromMessage(body)
                    if (otp != null) {
                        result.append("🔑 OTP FOUND: $otp\n")
                    } else {
                        result.append("No OTP found.\n")
                    }
                    result.append("\n")
                }

                if (count == 0) {
                    result.append("❌ No messages found in inbox (or permission denied).\n")
                }

            } ?: result.append("❌ Failed to query SMS inbox - cursor is null (Permission denied or URI issue).\n")

        } catch (e: SecurityException) {
            result.append("❌ Security Exception: ${e.message}\n")
            result.append("Please ensure READ_SMS permission is granted.\n")
            Log.e(TAG, "SmsTestActivity: SecurityException in testLastFiveSmsMessages", e)
        } catch (e: Exception) {
            result.append("❌ Error: ${e.message} (${e.javaClass.simpleName})\n")
            Log.e(TAG, "SmsTestActivity: General error in testLastFiveSmsMessages", e)
        }

        resultTextView.text = result.toString()
    }
}
