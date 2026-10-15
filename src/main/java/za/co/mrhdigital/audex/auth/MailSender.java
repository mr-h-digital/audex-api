package za.co.mrhdigital.audex.auth;

/** Sends the few emails the app needs (reset link, username reminder, "your password changed"). */
public interface MailSender {
    void send(String to, String subject, String body);
}
