package com.example.otprelay

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/**
 * Utility class for sending emails directly via SMTP.
 * Requires JavaMail API dependencies.
 */
object EmailSender {

    private val TAG = Constants.LOG_TAG

    /**
     * Sends an email asynchronously using the provided SMTP settings.
     *
     * @param context The application context (used for notifications).
     * @param subject The subject of the email.
     * @param body The body of the email.
     * @param recipient The email address of the recipient.
     * @param senderEmail The email address from which the email will be sent.
     * @param smtpHost The SMTP server host.
     * @param smtpPort The SMTP server port.
     * @param smtpUsername The username for SMTP authentication.
     * @param smtpPassword The password for SMTP authentication.
     */
    suspend fun sendEmail(
        context: Context,
        subject: String,
        body: String,
        recipient: String, // Use this parameter directly
        senderEmail: String, // Use this parameter directly
        smtpHost: String,    // Use this parameter directly
        smtpPort: Int,       // Use this parameter directly
        smtpUsername: String,// Use this parameter directly
        smtpPassword: String // Use this parameter directly
    ): Boolean = withContext(Dispatchers.IO) { // Perform network operation on IO dispatcher

        // IMPORTANT SECURITY WARNING:
        // Storing SMTP passwords directly in SharedPreferences is NOT secure for production apps.
        // Consider using AndroidX EncryptedSharedPreferences for better security.
        // Even with encryption, it's best practice to avoid storing sensitive credentials on the device
        // if possible, or to use a backend service for sending emails.

        // Validate essential settings (using the passed parameters)
        if (smtpHost.isEmpty() || smtpUsername.isEmpty() || smtpPassword.isEmpty() ||
            recipient.isEmpty() || senderEmail.isEmpty()) {
            Log.e(TAG, "EmailSender: Missing SMTP configuration details. Cannot send email.")
            OTPForwarder.showNotification(
                context,
                "❌ Email Send Failed",
                "Incomplete SMTP settings. Please configure in app settings.",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
            return@withContext false
        }

        val props = Properties().apply {
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.starttls.enable", "true") // Use STARTTLS for port 587
            // IMPORTANT SECURITY WARNING:
            // The following line bypasses SSL certificate validation.
            // DO NOT USE IN PRODUCTION unless you fully understand the security implications
            // and have a robust custom trust management system in place.
            // For production, remove this line and ensure your SMTP server has a valid SSL certificate.
            put("mail.smtp.ssl.trust", smtpHost)
        }

        val session = Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(smtpUsername, smtpPassword)
            }
        })

        return@withContext try {
            val message = MimeMessage(session).apply {
                setFrom(InternetAddress(senderEmail))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient)) // Use 'recipient' parameter
                setSubject(subject)
                setText(body)
            }
            Transport.send(message)
            Log.d(TAG, "EmailSender: Email sent successfully to $recipient from $senderEmail.")
            OTPForwarder.showNotification(
                context,
                "✅ Email Sent",
                "OTP forwarded to $recipient",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
            true
        } catch (e: MessagingException) {
            Log.e(TAG, "EmailSender: Error sending email: ${e.message}", e)
            OTPForwarder.showNotification(
                context,
                "❌ Email Send Failed",
                "Error: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
            false
        } catch (e: Exception) {
            Log.e(TAG, "EmailSender: Unexpected error sending email: ${e.message}", e)
            OTPForwarder.showNotification(
                context,
                "❌ Email Send Failed",
                "Unexpected error: ${e.message}",
                Constants.SMS_DEBUG_CHANNEL_ID
            )
            false
        }
    }
}